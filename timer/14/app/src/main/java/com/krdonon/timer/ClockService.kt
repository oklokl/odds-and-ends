package com.krdonon.timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.Locale

/**
 * 메인 타이머/스톱워치 + 보조(여러 개) 타이머를 알림으로 표시하는 ForegroundService.
 * - 메인 타이머: 잠금화면에서도 보이는 RemoteViews + 안전 폴백(표준 알림)
 * - 스톱워치, 보조 타이머: 기존 동작 유지
 * - 프래그먼트와 상태 동기화(브로드캐스트 + SharedPreferences)
 */
class ClockService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ===== 메인 타이머 =====
    private var timerJob: Job? = null
    private var timerEndElapsed: Long = 0L          // elapsedRealtime 기준 종료시각
    private var isTimerPaused: Boolean = false
    private var pausedRemainingMs: Long = 0L

    // ===== 스톱워치 =====
    private var stopwatchJob: Job? = null
    private var stopwatchBase: Long = 0L            // elapsedRealtime 기준 시작시각

    // ===== 보조 타이머(여러 개) =====
    private val extraJobs = mutableMapOf<String, Job>()
    private val extraWhen = mutableMapOf<String, Long>()   // id -> 락된 when(표시 고정용)
    private val extraOrder = mutableMapOf<String, Int>()   // id -> 등록 순번
    private var extraSeq = 0
    private var lastSummaryAt = 0L

    // ===== 포그라운드 상태 =====
    private var isInForeground = false
    private var foregroundLeader: Leader? = null
    enum class Leader { TIMER, STOPWATCH }

    // ===== 프래그먼트 동기화 =====
    private val ACTION_TIMER_STATE = "com.krdonon.timer.action.TIMER_STATE"
    private val EXTRA_STATE = "state"
    private val EXTRA_REMAIN_MS = "remain_ms"
    private val STATE_RUNNING = "RUNNING"
    private val STATE_PAUSED  = "PAUSED"
    private val STATE_STOPPED = "STOPPED"
    private val STATE_FINISHED = "FINISHED"

    private val SYNC_PREFS = "clock_sync_prefs"
    private val KEY_LAST_STATE = "key_state"
    private val KEY_LAST_REMAIN = "key_remain_ms"
    private var lastPublishMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TIMER -> {
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L).coerceAtLeast(0L)
                isTimerPaused = false
                startTimer(durationMs)
                publishTimerState(STATE_RUNNING, durationMs, force = true)
            }
            ACTION_STOP_TIMER   -> { stopTimer(); publishTimerState(STATE_STOPPED, 0L, force = true) }
            ACTION_PAUSE_TIMER  -> { pauseTimer(); publishTimerState(STATE_PAUSED, pausedRemainingMs, force = true) }
            ACTION_RESUME_TIMER -> {
                if (isTimerPaused && pausedRemainingMs > 0L) {
                    publishTimerState(STATE_RUNNING, pausedRemainingMs, force = true)
                }
                resumeTimer()
            }

            ACTION_START_STOPWATCH -> {
                val base = intent.getLongExtra(EXTRA_STOPWATCH_BASE, 0L)
                startStopwatch(baseElapsed = base)
            }
            ACTION_STOP_STOPWATCH -> stopStopwatch()

            ACTION_START_EXTRA -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_STICKY
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "타이머"
                val ms = intent.getLongExtra(EXTRA_DURATION_MS, 0L).coerceAtLeast(0L)
                startExtra(id, label, ms)
            }
            ACTION_STOP_EXTRA -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_STICKY
                stopExtra(id)
            }

            ACTION_STOP_ALL -> {
                stopTimer(); stopStopwatch(); stopAllExtras()
                publishTimerState(STATE_STOPPED, 0L, force = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        stopwatchJob?.cancel()
        stopAllExtras()
        scope.cancel()
        super.onDestroy()
    }

    // -------------------- 채널 --------------------
    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            fun ch(id: String, name: String, importance: Int) = NotificationChannel(
                id, name, importance
            ).apply {
                description = name
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 타이머는 잠금화면 가시성을 위해 DEFAULT
            nm.createNotificationChannel(ch(TIMER_CHANNEL, "타이머 진행", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(ch(STOPWATCH_CHANNEL, "스톱워치 진행", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(ch(EXTRA_CHANNEL, "보조 타이머 진행", NotificationManager.IMPORTANCE_LOW))
        }
    }

    // ===================== Timer =====================
    private fun startTimer(durationMs: Long) {
        ensureChannels()
        timerEndElapsed = SystemClock.elapsedRealtime() + durationMs
        ensureForeground(Leader.TIMER)

        timerJob?.cancel()
        timerJob = scope.launch {
            var last = 0L
            while (isActive) {
                val remain = timerEndElapsed - SystemClock.elapsedRealtime()
                if (remain <= 0L) {
                    notifyTimer(0L)
                    publishTimerState(STATE_FINISHED, 0L, force = true)
                    delay(300)
                    cancelTimerNotification()
                    break
                } else {
                    val t = System.currentTimeMillis()
                    if (t - last >= 1000) {
                        notifyTimer(remain)
                        last = t
                    }
                    // 초당 1회 상태 송출(프래그먼트 백업 동기화)
                    publishTimerTickIfNeeded(remain)
                }
                delay(100)
            }
            timerJob = null
            onChannelPossiblyIdle()
        }
    }

    private fun pauseTimer() {
        if (timerJob == null || isTimerPaused) return
        pausedRemainingMs = (timerEndElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        isTimerPaused = true
        timerJob?.cancel()
        timerJob = null
        foregroundLeader = Leader.TIMER // FG 유지
        notifyTimer(pausedRemainingMs)
        onChannelPossiblyIdle()
    }

    private fun resumeTimer() {
        if (!isTimerPaused || pausedRemainingMs <= 0L) return
        isTimerPaused = false
        startTimer(pausedRemainingMs)
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        isTimerPaused = false
        pausedRemainingMs = 0L
        cancelTimerNotification()
        onChannelPossiblyIdle()
    }

    // ===================== Stopwatch =====================
    private fun startStopwatch(baseElapsed: Long = 0L) {
        ensureChannels()
        stopwatchBase = SystemClock.elapsedRealtime() - baseElapsed
        ensureForeground(Leader.STOPWATCH)

        stopwatchJob?.cancel()
        stopwatchJob = scope.launch {
            var last = 0L
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - stopwatchBase
                val t = System.currentTimeMillis()
                if (t - last >= 1000) {
                    notifyStopwatch(elapsed)
                    last = t
                }
                delay(100)
            }
        }
    }

    private fun stopStopwatch() {
        stopwatchJob?.cancel()
        stopwatchJob = null
        cancelStopwatchNotification()
        onChannelPossiblyIdle()
    }

    // ===================== Extras =====================
    private fun startExtra(id: String, label: String, durationMs: Long) {
        ensureChannels()
        extraJobs[id]?.cancel()

        val endElapsed = SystemClock.elapsedRealtime() + durationMs
        val notifyId = extraNotifyId(id)

        if (extraWhen[id] == null) extraWhen[id] = System.currentTimeMillis()
        if (extraOrder[id] == null) extraOrder[id] = extraSeq++

        postOrUpdateExtraSummary(force = true)

        extraJobs[id] = scope.launch {
            var last = 0L
            while (isActive) {
                val remain = endElapsed - SystemClock.elapsedRealtime()
                if (remain <= 0L) {
                    notifySafe(notifyId, buildExtraNotification(id, label, 0L))
                    delay(300)
                    cancelSafe(notifyId)
                    break
                } else {
                    val t = System.currentTimeMillis()
                    if (t - last >= 1000) {
                        notifySafe(notifyId, buildExtraNotification(id, label, remain))
                        last = t
                    }
                }
                postOrUpdateExtraSummary(force = false)
                delay(100)
            }
            extraJobs.remove(id)
            extraWhen.remove(id)
            extraOrder.remove(id)
            if (extraJobs.isEmpty()) cancelSafe(EXTRA_SUMMARY_ID) else postOrUpdateExtraSummary(force = true)
            onChannelPossiblyIdle()
        }
    }

    private fun stopExtra(id: String) {
        extraJobs.remove(id)?.cancel()
        cancelSafe(extraNotifyId(id))
        extraWhen.remove(id)
        extraOrder.remove(id)
        if (extraJobs.isEmpty()) cancelSafe(EXTRA_SUMMARY_ID) else postOrUpdateExtraSummary(force = true)
        onChannelPossiblyIdle()
    }

    private fun stopAllExtras() {
        extraJobs.values.forEach { it.cancel() }
        extraJobs.clear()
        extraWhen.clear()
        extraOrder.clear()
        cancelSafe(EXTRA_SUMMARY_ID)
        for (i in 0 until 100) cancelSafe(EXTRA_BASE_NID + i)
    }

    private fun extraNotifyId(id: String): Int =
        EXTRA_BASE_NID + (id.hashCode() and 0x7fffffff) % 100

    private fun postOrUpdateExtraSummary(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSummaryAt < 5000L) return
        lastSummaryAt = now

        val n = NotificationCompat.Builder(this, EXTRA_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("보조 타이머")
            .setContentText("진행 중: ${extraJobs.size}개")
            .setGroup(EXTRA_GROUP)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notifySafe(EXTRA_SUMMARY_ID, n)
    }

    // ===================== Foreground 유지 =====================
    private fun ensureForeground(preferred: Leader) {
        if (!isInForeground) {
            foregroundLeader = preferred
            val initial: Notification = when (preferred) {
                Leader.TIMER -> buildTimerNotificationSafe(0L)   // 안전 폴백으로 시작
                Leader.STOPWATCH -> buildStopwatchNotification(0L)
            }
            runCatching { startForeground(FOREGROUND_ID, initial) }
            isInForeground = true
        }
    }

    private fun onChannelPossiblyIdle() {
        val timerRunning = timerJob != null
        val stopwatchRunning = stopwatchJob != null
        val extraRunning = extraJobs.isNotEmpty()

        when {
            timerRunning || isTimerPaused -> {
                foregroundLeader = Leader.TIMER
                val remain = if (isTimerPaused) pausedRemainingMs
                else (timerEndElapsed - SystemClock.elapsedRealtime())
                runCatching {
                    startForeground(FOREGROUND_ID, buildTimerNotificationSafe(remain.coerceAtLeast(0L)))
                }
            }
            stopwatchRunning -> {
                foregroundLeader = Leader.STOPWATCH
                val elapsed = (SystemClock.elapsedRealtime() - stopwatchBase).coerceAtLeast(0L)
                runCatching { startForeground(FOREGROUND_ID, buildStopwatchNotification(elapsed)) }
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                isInForeground = false
                foregroundLeader = null
                if (!extraRunning) stopSelf()
            }
        }
    }

    // ===================== Notifications =====================
    private fun notifyTimer(remainingMs: Long) {
        val id = if (foregroundLeader == Leader.TIMER) FOREGROUND_ID else NID_TIMER
        notifySafe(id, buildTimerNotificationSafe(remainingMs))
    }

    private fun cancelTimerNotification() {
        val id = if (foregroundLeader == Leader.TIMER) FOREGROUND_ID else NID_TIMER
        cancelSafe(id)
    }

    private fun notifyStopwatch(elapsedMs: Long) {
        val id = if (foregroundLeader == Leader.STOPWATCH) FOREGROUND_ID else NID_STOPWATCH
        notifySafe(id, buildStopwatchNotification(elapsedMs))
    }

    private fun cancelStopwatchNotification() {
        val id = if (foregroundLeader == Leader.STOPWATCH) FOREGROUND_ID else NID_STOPWATCH
        cancelSafe(id)
    }

    // ======= 안전 래퍼: RemoteViews 실패 시 표준 알림으로 폴백 =======
    private fun buildTimerNotificationSafe(remainingMs: Long): Notification {
        return try {
            buildTimerNotificationRemoteViews(remainingMs)
        } catch (_: Throwable) {
            val running = (timerJob != null) && !isTimerPaused
            val content = if (running) "남은 시간: ${formatHMSms4(remainingMs)}"
            else "일시정지 • ${formatHMSms4(remainingMs)}"

            val b = NotificationCompat.Builder(this, TIMER_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.label_timer))
                .setContentText(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(mainPendingIntent(null))
                .setAutoCancel(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            if (running) {
                // 표준 알림의 크로노미터는 wall clock 기준
                b.setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(System.currentTimeMillis() + remainingMs)
                b.addAction(0, getString(R.string.btn_pause), serviceActionPendingIntent(ACTION_PAUSE_TIMER))
            } else {
                b.setShowWhen(false).setUsesChronometer(false)
                if (remainingMs > 0L) {
                    b.addAction(0, getString(R.string.btn_resume), serviceActionPendingIntent(ACTION_RESUME_TIMER))
                }
            }
            b.addAction(0, getString(R.string.btn_stop), serviceActionPendingIntent(ACTION_STOP_TIMER))
            b.build()
        }
    }

    // ======= (핵심) RemoteViews 버전 =======
    private fun buildTimerNotificationRemoteViews(remainingMs: Long): Notification {
        val running = (timerJob != null) && !isTimerPaused

        // Chronometer(알림 RemoteViews)는 반드시 elapsedRealtime 기준
        val baseElapsed = SystemClock.elapsedRealtime() + remainingMs.coerceAtLeast(0L)

        // --- RemoteViews 인스턴스 ---
        val compact = RemoteViews(packageName, R.layout.notification_timer_compact)
        val expanded = RemoteViews(packageName, R.layout.notification_timer_expanded)

        // 타이틀
        compact.setTextViewText(R.id.title, getString(R.string.label_timer))
        expanded.setTextViewText(R.id.title_big, getString(R.string.label_timer))

        // 크로노미터(카운트다운)
        if (Build.VERSION.SDK_INT >= 24) {
            compact.setChronometerCountDown(R.id.chronometer, true)
            expanded.setChronometerCountDown(R.id.chronometer_big, true)
        }
        compact.setChronometer(R.id.chronometer, baseElapsed, null, running)
        expanded.setChronometer(R.id.chronometer_big, baseElapsed, null, running)

        // 버튼 인텐트
        val piPause = serviceActionPendingIntent(ACTION_PAUSE_TIMER)
        val piResume = serviceActionPendingIntent(ACTION_RESUME_TIMER)
        val piStop  = serviceActionPendingIntent(ACTION_STOP_TIMER)

        // compact: ImageView 버튼(가장 호환성 좋음)
        if (running) {
            compact.setImageViewResource(R.id.btn_toggle, R.drawable.ic_pause)
            compact.setOnClickPendingIntent(R.id.btn_toggle, piPause)
            expanded.setTextViewText(R.id.btn_toggle_big, getString(R.string.btn_pause))
            expanded.setOnClickPendingIntent(R.id.btn_toggle_big, piPause)
            compact.setViewVisibility(R.id.btn_toggle, View.VISIBLE)
            expanded.setViewVisibility(R.id.btn_toggle_big, View.VISIBLE)
        } else {
            if (remainingMs > 0L) {
                compact.setImageViewResource(R.id.btn_toggle, R.drawable.ic_play)
                compact.setOnClickPendingIntent(R.id.btn_toggle, piResume)
                expanded.setTextViewText(R.id.btn_toggle_big, getString(R.string.btn_resume))
                expanded.setOnClickPendingIntent(R.id.btn_toggle_big, piResume)
                compact.setViewVisibility(R.id.btn_toggle, View.VISIBLE)
                expanded.setViewVisibility(R.id.btn_toggle_big, View.VISIBLE)
            } else {
                compact.setViewVisibility(R.id.btn_toggle, View.GONE)
                expanded.setViewVisibility(R.id.btn_toggle_big, View.GONE)
            }
        }
        compact.setOnClickPendingIntent(R.id.btn_stop, piStop)
        expanded.setTextViewText(R.id.btn_stop_big, getString(R.string.btn_stop))
        expanded.setOnClickPendingIntent(R.id.btn_stop_big, piStop)

        // 본문(시스템 영역 텍스트)
        val content = if (running) "남은 시간: ${formatHMSms4(remainingMs)}"
        else "일시정지 • ${formatHMSms4(remainingMs)}"

        // 퍼블릭 버전(잠금화면 민감정보 숨김 모드 대비)
        val publicVersion = NotificationCompat.Builder(this, TIMER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.label_timer))
            .setContentText(content)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val builder = NotificationCompat.Builder(this, TIMER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.label_timer))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(mainPendingIntent(null))
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setPublicVersion(publicVersion)

        // 표준 알림의 크로노미터는 wall clock 기준 -> setWhen은 currentTimeMillis + 남은시간
        if (running) {
            builder.setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + remainingMs.coerceAtLeast(0L))
        } else {
            builder.setShowWhen(false).setUsesChronometer(false)
        }

        return builder.build()
    }

    // 스톱워치 알림(표준)
    private fun buildStopwatchNotification(elapsedMs: Long): Notification {
        val content = "경과 시간: ${formatHMSms4(elapsedMs)}"
        return NotificationCompat.Builder(this, STOPWATCH_CHANNEL)
            .setContentTitle("스톱워치")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - elapsedMs)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent(null))
            .build()
    }

    // 보조 타이머 알림(정렬 고정 + 깜빡임 방지)
    private fun buildExtraNotification(id: String, label: String, remainingMs: Long): Notification {
        val content = "${formatHMSms4(remainingMs)} 남음"
        val fixedWhen = extraWhen[id] ?: System.currentTimeMillis()
        val order = extraOrder[id] ?: 0
        val sortKey = String.format(Locale.US, "%06d", Int.MAX_VALUE - order)

        return NotificationCompat.Builder(this, EXTRA_CHANNEL)
            .setContentTitle(label)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(fixedWhen)
            .setGroup(EXTRA_GROUP)
            .setSortKey(sortKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent(null))
            .build()
    }

    // ========== 상태 송출 유틸 ==========
    private fun publishTimerState(state: String, remainMs: Long, force: Boolean = false) {
        // 1) Prefs 저장 (프래그먼트가 언제든 읽어와 동기화)
        getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_STATE, state)
            .putLong(KEY_LAST_REMAIN, remainMs.coerceAtLeast(0L))
            .apply()

        // 2) 브로드캐스트 (프래그먼트가 살아있다면 즉시 반영)
        val intent = Intent(ACTION_TIMER_STATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_REMAIN_MS, remainMs.coerceAtLeast(0L))
        }
        sendBroadcast(intent)

        if (force) lastPublishMs = SystemClock.elapsedRealtime()
    }

    private fun publishTimerTickIfNeeded(remainMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishMs >= 1000L) {
            publishTimerState(STATE_RUNNING, remainMs, force = false)
            lastPublishMs = now
        }
    }

    // ========== 공통 유틸 ==========
    private fun mainPendingIntent(action: String?): PendingIntent {
        val open = Intent(this, com.krdonon.timer.MainActivity::class.java).apply {
            if (action != null) this.action = action
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, open, flags)
    }

    private fun serviceActionPendingIntent(action: String): PendingIntent {
        val i = Intent(this, ClockService::class.java).apply { this.action = action }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), i, flags)
    }

    private fun formatHMSms4(msInput: Long): String {
        val ms = msInput.coerceAtLeast(0L)
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val tenTh = ((ms % 1000) * 10).toInt()
        return String.format(Locale.getDefault(), "%02d:%02d:%02d:%04d", h, m, s, tenTh)
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    private fun notifySafe(id: Int, notification: Notification) {
        if (!canPostNotifications()) return
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun cancelSafe(id: Int) {
        runCatching { NotificationManagerCompat.from(this).cancel(id) }
    }

    companion object {
        // actions
        private const val ACTION_START_TIMER = "com.krdonon.timer.action.START_TIMER"
        private const val ACTION_STOP_TIMER = "com.krdonon.timer.action.STOP_TIMER"
        private const val ACTION_PAUSE_TIMER = "com.krdonon.timer.action.PAUSE_TIMER"
        private const val ACTION_RESUME_TIMER = "com.krdonon.timer.action.RESUME_TIMER"
        private const val ACTION_START_STOPWATCH = "com.krdonon.timer.action.START_STOPWATCH"
        private const val ACTION_STOP_STOPWATCH = "com.krdonon.timer.action.STOP_STOPWATCH"
        private const val ACTION_START_EXTRA = "com.krdonon.timer.action.START_EXTRA"
        private const val ACTION_STOP_EXTRA = "com.krdonon.timer.action.STOP_EXTRA"
        private const val ACTION_STOP_ALL = "com.krdonon.timer.action.STOP_ALL"

        // extras
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_STOPWATCH_BASE = "stopwatch_base"
        private const val EXTRA_ID = "id"
        private const val EXTRA_LABEL = "label"

        // ids
        private const val FOREGROUND_ID = 42
        private const val NID_TIMER = 1001
        private const val NID_STOPWATCH = 1002
        private const val EXTRA_BASE_NID = 2000
        private const val EXTRA_SUMMARY_ID = 2999

        // channels
        private const val TIMER_CHANNEL = "timer_channel_v2"
        private const val STOPWATCH_CHANNEL = "stopwatch_channel"
        private const val EXTRA_CHANNEL = "extra_timer_channel"

        // 보조 타이머 그룹
        private const val EXTRA_GROUP = "extra_timer_group"

        // ===== helper APIs =====
        fun startTimer(context: Context, durationMs: Long) {
            val i = Intent(context, ClockService::class.java).apply {
                action = ACTION_START_TIMER
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun stopTimer(context: Context) {
            val i = Intent(context, ClockService::class.java).apply { action = ACTION_STOP_TIMER }
            context.startService(i)
        }

        fun pauseTimer(context: Context) {
            val i = Intent(context, ClockService::class.java).apply { action = ACTION_PAUSE_TIMER }
            context.startService(i)
        }

        fun resumeTimer(context: Context) {
            val i = Intent(context, ClockService::class.java).apply { action = ACTION_RESUME_TIMER }
            context.startService(i)
        }

        fun startStopwatch(context: Context, baseElapsed: Long = 0L) {
            val i = Intent(context, ClockService::class.java).apply {
                action = ACTION_START_STOPWATCH
                putExtra(EXTRA_STOPWATCH_BASE, baseElapsed)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun stopStopwatch(context: Context) {
            val i = Intent(context, ClockService::class.java).apply { action = ACTION_STOP_STOPWATCH }
            context.startService(i)
        }

        fun startExtraTimer(context: Context, id: String, label: String, durationMs: Long) {
            val i = Intent(context, ClockService::class.java).apply {
                action = ACTION_START_EXTRA
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            context.startService(i)
        }

        fun stopExtraTimer(context: Context, id: String) {
            val i = Intent(context, ClockService::class.java).apply {
                action = ACTION_STOP_EXTRA
                putExtra(EXTRA_ID, id)
            }
            context.startService(i)
        }

        fun stopAll(context: Context) {
            val i = Intent(context, ClockService::class.java).apply { action = ACTION_STOP_ALL }
            context.startService(i)
        }
    }
}
