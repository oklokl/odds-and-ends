package com.krdonon.timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 개선된 ClockService: 메인 타이머 + 보조 타이머 + 스톱워치 모두 장시간 실행 지원
 * ✅ 무한 브로드캐스트 루프 방지
 * ✅ 중복 상태 전송 방지
 */
class ClockService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ===== 메인 타이머 =====
    private var timerJob: Job? = null
    private var timerEndElapsed: Long = 0L
    private var isTimerPaused: Boolean = false
    private var pausedRemainingMs: Long = 0L

    // ===== 스톱워치 =====
    private var stopwatchJob: Job? = null
    private var stopwatchBase: Long = 0L

    // ===== 보조 타이머 =====
    private data class ExtraTimerState(
        val id: String,
        val label: String,
        var endElapsed: Long,
        var job: Job? = null
    )

    private val extraTimers = mutableMapOf<String, ExtraTimerState>()
    private val extraWhen = mutableMapOf<String, Long>()
    private val extraOrder = mutableMapOf<String, Int>()
    private var extraSeq = 0
    private var lastSummaryAt = 0L

    // ===== 포그라운드 상태 =====
    private var isInForeground = false
    private var foregroundLeader: Leader? = null
    enum class Leader { TIMER, STOPWATCH }

    // ===== 상태 동기화 =====
    private val ACTION_TIMER_STATE = "com.krdonon.timer.action.TIMER_STATE"
    private val EXTRA_STATE = "state"
    private val EXTRA_REMAIN_MS = "remain_ms"
    private val STATE_RUNNING = "RUNNING"
    private val STATE_PAUSED  = "PAUSED"
    private val STATE_STOPPED = "STOPPED"
    private val STATE_FINISHED = "FINISHED"

    // ===== 지속 저장용 SharedPreferences =====
    private val PERSIST_PREFS = "clock_persist_prefs"
    private val KEY_TIMER_END_ELAPSED = "timer_end_elapsed"
    private val KEY_TIMER_PAUSED = "timer_paused"
    private val KEY_TIMER_PAUSED_REMAIN = "timer_paused_remain"
    private val KEY_STOPWATCH_BASE = "stopwatch_base"
    private val KEY_STOPWATCH_RUNNING = "stopwatch_running"
    private val KEY_EXTRA_TIMERS_JSON = "extra_timers_json"

    // ===== Keep-Alive 알람 =====
    private val KEEP_ALIVE_INTERVAL_MS = 30 * 60 * 1000L
    private val KEEP_ALIVE_REQUEST_CODE = 99999

    private val SYNC_PREFS = "clock_sync_prefs"
    private val KEY_LAST_STATE = "key_state"
    private val KEY_LAST_REMAIN = "key_remain_ms"
    private var lastPublishMs = 0L

    // ✅ 중복 브로드캐스트 방지
    private var lastPublishedState = ""
    private var lastPublishedRemain = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        restorePersistedState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TIMER -> {
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L).coerceAtLeast(0L)
                isTimerPaused = false
                startTimer(durationMs)
                publishTimerState(STATE_RUNNING, durationMs, force = true)
            }
            ACTION_STOP_TIMER   -> {
                stopTimer()
            }
            ACTION_PAUSE_TIMER  -> {
                pauseTimer()
            }
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
            }

            ACTION_KEEP_ALIVE -> {
                restorePersistedState()
                scheduleKeepAliveAlarm()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        stopwatchJob?.cancel()
        stopAllExtras()
        cancelKeepAliveAlarm()
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

            nm.createNotificationChannel(ch(TIMER_CHANNEL, "타이머 진행", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(ch(STOPWATCH_CHANNEL, "스톱워치 진행", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(ch(EXTRA_CHANNEL, "보조 타이머 진행", NotificationManager.IMPORTANCE_LOW))
        }
    }

    // ===================== 상태 지속 저장/복원 =====================
    private fun persistTimerState() {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TIMER_END_ELAPSED, timerEndElapsed)
            .putBoolean(KEY_TIMER_PAUSED, isTimerPaused)
            .putLong(KEY_TIMER_PAUSED_REMAIN, pausedRemainingMs)
            .apply()
    }

    private fun clearTimerState() {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TIMER_END_ELAPSED)
            .remove(KEY_TIMER_PAUSED)
            .remove(KEY_TIMER_PAUSED_REMAIN)
            .apply()
    }

    private fun persistStopwatchState() {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STOPWATCH_BASE, stopwatchBase)
            .putBoolean(KEY_STOPWATCH_RUNNING, stopwatchJob != null)
            .apply()
    }

    private fun clearStopwatchState() {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_STOPWATCH_BASE)
            .remove(KEY_STOPWATCH_RUNNING)
            .apply()
    }

    private fun persistExtraTimers() {
        val jsonArray = JSONArray()
        extraTimers.values.forEach { timer ->
            val obj = JSONObject().apply {
                put("id", timer.id)
                put("label", timer.label)
                put("endElapsed", timer.endElapsed)
            }
            jsonArray.put(obj)
        }

        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXTRA_TIMERS_JSON, jsonArray.toString())
            .apply()
    }

    private fun clearExtraTimers() {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_EXTRA_TIMERS_JSON)
            .apply()
    }

    private fun restorePersistedState() {
        val prefs = getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE)

        val savedEndElapsed = prefs.getLong(KEY_TIMER_END_ELAPSED, 0L)
        val wasPaused = prefs.getBoolean(KEY_TIMER_PAUSED, false)
        val savedPausedRemain = prefs.getLong(KEY_TIMER_PAUSED_REMAIN, 0L)

        if (wasPaused && savedPausedRemain > 0L) {
            isTimerPaused = true
            pausedRemainingMs = savedPausedRemain
            ensureForeground(Leader.TIMER)
            notifyTimer(pausedRemainingMs)
        } else if (savedEndElapsed > 0L) {
            val remain = savedEndElapsed - System.currentTimeMillis()
            if (remain > 0L) {
                timerEndElapsed = savedEndElapsed
                startTimerJob()
                scheduleKeepAliveAlarm()
            } else {
                clearTimerState()
            }
        }

        val swBase = prefs.getLong(KEY_STOPWATCH_BASE, 0L)
        val swRunning = prefs.getBoolean(KEY_STOPWATCH_RUNNING, false)
        if (swRunning && swBase > 0L) {
            stopwatchBase = swBase
            startStopwatchJob()
        }

        val extrasJson = prefs.getString(KEY_EXTRA_TIMERS_JSON, null)
        if (!extrasJson.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(extrasJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("id")
                    val label = obj.getString("label")
                    val endElapsed = obj.getLong("endElapsed")

                    val remain = endElapsed - System.currentTimeMillis()
                    if (remain > 0L) {
                        startExtraFromEndElapsed(id, label, endElapsed)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ===================== Timer =====================
    private fun startTimer(durationMs: Long) {
        ensureChannels()
        timerEndElapsed = System.currentTimeMillis() + durationMs
        persistTimerState()
        ensureForeground(Leader.TIMER)
        startTimerJob()
        scheduleKeepAliveAlarm()
    }

    private fun startTimerJob() {
        timerJob?.cancel()
        timerJob = scope.launch {
            var last = 0L
            while (isActive) {
                val remain = timerEndElapsed - System.currentTimeMillis()
                if (remain <= 0L) {
                    com.krdonon.timer.alarm.AlarmService.start(this@ClockService, "메인 타이머")

                    notifyTimer(0L)
                    publishTimerState(STATE_FINISHED, 0L, force = true)
                    delay(300)
                    cancelTimerNotification()
                    clearTimerState()
                    cancelKeepAliveAlarm()
                    break
                } else {
                    val t = System.currentTimeMillis()
                    if (t - last >= 1000) {
                        notifyTimer(remain)
                        persistTimerState()
                        last = t
                    }
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
        pausedRemainingMs = (timerEndElapsed - System.currentTimeMillis()).coerceAtLeast(0L)
        isTimerPaused = true
        timerJob?.cancel()
        timerJob = null
        persistTimerState()
        cancelKeepAliveAlarm()
        foregroundLeader = Leader.TIMER
        notifyTimer(pausedRemainingMs)

        // ✅ 일시정지 후 브로드캐스트 전송 (단 1회만)
        publishTimerState(STATE_PAUSED, pausedRemainingMs, force = true)
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
        clearTimerState()
        cancelKeepAliveAlarm()
        cancelTimerNotification()

        // ✅ 정지 후 브로드캐스트 전송 (단 1회만)
        publishTimerState(STATE_STOPPED, 0L, force = true)
        onChannelPossiblyIdle()
    }

    // ===================== Stopwatch =====================
    private fun startStopwatch(baseElapsed: Long = 0L) {
        ensureChannels()
        stopwatchBase = SystemClock.elapsedRealtime() - baseElapsed
        persistStopwatchState()
        ensureForeground(Leader.STOPWATCH)
        startStopwatchJob()
    }

    private fun startStopwatchJob() {
        stopwatchJob?.cancel()
        stopwatchJob = scope.launch {
            var last = 0L
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - stopwatchBase
                val t = System.currentTimeMillis()
                if (t - last >= 1000) {
                    notifyStopwatch(elapsed)
                    persistStopwatchState()
                    last = t
                }
                delay(100)
            }
        }
    }

    private fun stopStopwatch() {
        stopwatchJob?.cancel()
        stopwatchJob = null
        clearStopwatchState()
        cancelStopwatchNotification()
        onChannelPossiblyIdle()
    }

    // ===================== Keep-Alive 알람 =====================
    private fun scheduleKeepAliveAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ClockService::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }
        val pi = PendingIntent.getService(
            this,
            KEEP_ALIVE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + KEEP_ALIVE_INTERVAL_MS

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelKeepAliveAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ClockService::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }
        val pi = PendingIntent.getService(
            this,
            KEEP_ALIVE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { am.cancel(pi) }
    }

    // ===================== Extras (보조 타이머) =====================
    private fun startExtra(id: String, label: String, durationMs: Long) {
        val endElapsed = System.currentTimeMillis() + durationMs
        startExtraFromEndElapsed(id, label, endElapsed)
    }

    private fun startExtraFromEndElapsed(id: String, label: String, endElapsed: Long) {
        ensureChannels()

        extraTimers[id]?.job?.cancel()

        val state = ExtraTimerState(id, label, endElapsed)
        extraTimers[id] = state

        val notifyId = extraNotifyId(id)

        if (extraWhen[id] == null) extraWhen[id] = System.currentTimeMillis()
        if (extraOrder[id] == null) extraOrder[id] = extraSeq++

        postOrUpdateExtraSummary(force = true)
        persistExtraTimers()
        scheduleKeepAliveAlarm()

        state.job = scope.launch {
            var last = 0L
            while (isActive) {
                val remain = state.endElapsed - System.currentTimeMillis()
                if (remain <= 0L) {
                    com.krdonon.timer.alarm.AlarmService.start(this@ClockService, label)

                    notifySafe(notifyId, buildExtraNotification(id, label, 0L))
                    delay(300)
                    cancelSafe(notifyId)
                    break
                } else {
                    val t = System.currentTimeMillis()
                    if (t - last >= 1000) {
                        notifySafe(notifyId, buildExtraNotification(id, label, remain))
                        persistExtraTimers()
                        last = t
                    }
                }
                postOrUpdateExtraSummary(force = false)
                delay(100)
            }
            extraTimers.remove(id)
            extraWhen.remove(id)
            extraOrder.remove(id)
            persistExtraTimers()
            if (extraTimers.isEmpty()) {
                cancelSafe(EXTRA_SUMMARY_ID)
                clearExtraTimers()
            } else {
                postOrUpdateExtraSummary(force = true)
            }
            onChannelPossiblyIdle()
        }
    }

    private fun stopExtra(id: String) {
        extraTimers.remove(id)?.job?.cancel()
        cancelSafe(extraNotifyId(id))
        extraWhen.remove(id)
        extraOrder.remove(id)
        persistExtraTimers()
        if (extraTimers.isEmpty()) {
            cancelSafe(EXTRA_SUMMARY_ID)
            clearExtraTimers()
        } else {
            postOrUpdateExtraSummary(force = true)
        }
        onChannelPossiblyIdle()
    }

    private fun stopAllExtras() {
        extraTimers.values.forEach { it.job?.cancel() }
        extraTimers.clear()
        extraWhen.clear()
        extraOrder.clear()
        cancelSafe(EXTRA_SUMMARY_ID)
        for (i in 0 until 100) cancelSafe(EXTRA_BASE_NID + i)
        clearExtraTimers()
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
            .setContentText("진행 중: ${extraTimers.size}개")
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
                Leader.TIMER -> buildTimerNotificationSafe(0L)
                Leader.STOPWATCH -> buildStopwatchNotification(0L)
            }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        FOREGROUND_ID,
                        initial,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FOREGROUND_ID,
                        initial,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(FOREGROUND_ID, initial)
                }
            }

            isInForeground = true
        }
    }

    private fun onChannelPossiblyIdle() {
        val timerRunning = timerJob != null
        val stopwatchRunning = stopwatchJob != null
        val extraRunning = extraTimers.isNotEmpty()

        when {
            timerRunning || isTimerPaused -> {
                foregroundLeader = Leader.TIMER
                val remain = if (isTimerPaused) pausedRemainingMs
                else (timerEndElapsed - System.currentTimeMillis())

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            FOREGROUND_ID,
                            buildTimerNotificationSafe(remain.coerceAtLeast(0L)),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            FOREGROUND_ID,
                            buildTimerNotificationSafe(remain.coerceAtLeast(0L)),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(FOREGROUND_ID, buildTimerNotificationSafe(remain.coerceAtLeast(0L)))
                    }
                }
            }
            stopwatchRunning -> {
                foregroundLeader = Leader.STOPWATCH
                val elapsed = (SystemClock.elapsedRealtime() - stopwatchBase).coerceAtLeast(0L)

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            FOREGROUND_ID,
                            buildStopwatchNotification(elapsed),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            FOREGROUND_ID,
                            buildStopwatchNotification(elapsed),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(FOREGROUND_ID, buildStopwatchNotification(elapsed))
                    }
                }
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

    private fun buildTimerNotificationSafe(remainingMs: Long): Notification {
        return try {
            buildTimerNotificationRemoteViews(remainingMs)
        } catch (_: Throwable) {
            val running = (timerJob != null) && !isTimerPaused
            val content = if (running) "남은 시간 ${formatHMS(remainingMs)}"
            else "일시정지 • ${formatHMS(remainingMs)}"

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

    private fun buildTimerNotificationRemoteViews(remainingMs: Long): Notification {
        val running = (timerJob != null) && !isTimerPaused
        val baseElapsed = SystemClock.elapsedRealtime() + remainingMs.coerceAtLeast(0L)

        val compact = RemoteViews(packageName, R.layout.notification_timer_compact)
        val expanded = RemoteViews(packageName, R.layout.notification_timer_expanded)

        compact.setTextViewText(R.id.title, getString(R.string.label_timer))
        expanded.setTextViewText(R.id.title_big, getString(R.string.label_timer))

        if (Build.VERSION.SDK_INT >= 24) {
            compact.setChronometerCountDown(R.id.chronometer, true)
            expanded.setChronometerCountDown(R.id.chronometer_big, true)
        }
        compact.setChronometer(R.id.chronometer, baseElapsed, null, running)
        expanded.setChronometer(R.id.chronometer_big, baseElapsed, null, running)

        val piPause = serviceActionPendingIntent(ACTION_PAUSE_TIMER)
        val piResume = serviceActionPendingIntent(ACTION_RESUME_TIMER)
        val piStop  = serviceActionPendingIntent(ACTION_STOP_TIMER)

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

        val content = if (running) "남은 시간 ${formatHMS(remainingMs)}"
        else "일시정지 • ${formatHMS(remainingMs)}"

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

    private fun buildStopwatchNotification(elapsedMs: Long): Notification {
        val content = "경과 시간 ${formatHMS(elapsedMs)}"
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

    private fun buildExtraNotification(id: String, label: String, remainingMs: Long): Notification {
        val content = "${formatHMS(remainingMs)} 남음"
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
        val remain = remainMs.coerceAtLeast(0L)

        // ✅ 중복 브로드캐스트 방지: 같은 상태면 전송하지 않음
        if (!force && state == lastPublishedState && remain == lastPublishedRemain) {
            return
        }

        lastPublishedState = state
        lastPublishedRemain = remain

        // SharedPreferences에 상태 저장
        getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_STATE, state)
            .putLong(KEY_LAST_REMAIN, remain)
            .apply()

        // 명시적 브로드캐스트 전송
        val intent = Intent(ACTION_TIMER_STATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_REMAIN_MS, remain)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        if (force) lastPublishMs = SystemClock.elapsedRealtime()

        android.util.Log.d("ClockService", "✅ 브로드캐스트 전송: state=$state, remain=$remain")
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

    private fun formatHMS(msInput: Long): String {
        val ms = msInput.coerceAtLeast(0L)
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
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
        private const val ACTION_START_TIMER = "com.krdonon.timer.action.START_TIMER"
        private const val ACTION_STOP_TIMER = "com.krdonon.timer.action.STOP_TIMER"
        private const val ACTION_PAUSE_TIMER = "com.krdonon.timer.action.PAUSE_TIMER"
        private const val ACTION_RESUME_TIMER = "com.krdonon.timer.action.RESUME_TIMER"
        private const val ACTION_START_STOPWATCH = "com.krdonon.timer.action.START_STOPWATCH"
        private const val ACTION_STOP_STOPWATCH = "com.krdonon.timer.action.STOP_STOPWATCH"
        private const val ACTION_START_EXTRA = "com.krdonon.timer.action.START_EXTRA"
        private const val ACTION_STOP_EXTRA = "com.krdonon.timer.action.STOP_EXTRA"
        private const val ACTION_STOP_ALL = "com.krdonon.timer.action.STOP_ALL"
        private const val ACTION_KEEP_ALIVE = "com.krdonon.timer.action.KEEP_ALIVE"

        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_STOPWATCH_BASE = "stopwatch_base"
        private const val EXTRA_ID = "id"
        private const val EXTRA_LABEL = "label"

        private const val FOREGROUND_ID = 42
        private const val NID_TIMER = 1001
        private const val NID_STOPWATCH = 1002
        private const val EXTRA_BASE_NID = 2000
        private const val EXTRA_SUMMARY_ID = 2999

        private const val TIMER_CHANNEL = "timer_channel_v2"
        private const val STOPWATCH_CHANNEL = "stopwatch_channel"
        private const val EXTRA_CHANNEL = "extra_timer_channel"

        private const val EXTRA_GROUP = "extra_timer_group"

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