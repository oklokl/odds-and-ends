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
 * 개선된 ClockService: 알림바 버튼 안정성 강화
 * ✅ PendingIntent 고유 requestCode 사용
 * ✅ 상태 변경 시 동기화 강화
 * ✅ 브로드캐스트 전송 안정화
 */
class ClockService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ===== 메인 타이머 =====
    private var timerJob: Job? = null
    private var timerEndElapsed: Long = 0L
    private var isTimerPaused: Boolean = false
    private var pausedRemainingMs: Long = 0L

    // 🔹 표시용: 시작 시 설정된 전체 기간(알림에 '30분 / 오후 3:12' 표시용)
    private var timerTotalDurationMs: Long = 0L

    // ===== 스톱워치 =====
    private var stopwatchJob: Job? = null
    private var stopwatchBase: Long = 0L

    // ===== 보조 타이머 =====
    private data class ExtraTimerState(
        val id: String,
        val label: String,
        var endElapsed: Long,
        var totalDurationMs: Long,
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
    private val KEY_TIMER_TOTAL_DURATION = "timer_total_duration"
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

    // 🔹 액션 처리 중복 방지 플래그
    private var isProcessingAction = false
    private val actionProcessingLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        restorePersistedState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 🔹 액션 처리 안정화: 동기화 블록으로 중복 처리 방지
        synchronized(actionProcessingLock) {
            if (isProcessingAction) {
                android.util.Log.d("ClockService", "⚠️ 이미 액션 처리 중, 스킵: ${intent?.action}")
                return START_STICKY
            }
            isProcessingAction = true
        }

        // 🔹 액션 처리를 코루틴으로 비동기 처리하여 안정성 향상
        scope.launch {
            try {
                handleAction(intent)
            } finally {
                synchronized(actionProcessingLock) {
                    isProcessingAction = false
                }
            }
        }

        return START_STICKY
    }

    // 🔹 액션 처리를 별도 메서드로 분리
    private suspend fun handleAction(intent: Intent?) {
        when (intent?.action) {
            ACTION_START_TIMER -> {
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L).coerceAtLeast(0L)
                withContext(Dispatchers.Main) {
                    isTimerPaused = false
                    startTimer(durationMs)
                    // 🔹 약간의 딜레이 후 상태 전송으로 안정성 향상
                    delay(50)
                    publishTimerState(STATE_RUNNING, durationMs, force = true)
                }
            }
            ACTION_STOP_TIMER -> {
                withContext(Dispatchers.Main) {
                    stopTimer()
                }
            }
            ACTION_PAUSE_TIMER -> {
                withContext(Dispatchers.Main) {
                    pauseTimer()
                }
            }
            ACTION_RESUME_TIMER -> {
                withContext(Dispatchers.Main) {
                    if (isTimerPaused && pausedRemainingMs > 0L) {
                        publishTimerState(STATE_RUNNING, pausedRemainingMs, force = true)
                    }
                    resumeTimer()
                }
            }

            ACTION_START_STOPWATCH -> {
                val base = intent.getLongExtra(EXTRA_STOPWATCH_BASE, 0L)
                withContext(Dispatchers.Main) {
                    startStopwatch(baseElapsed = base)
                }
            }
            ACTION_STOP_STOPWATCH -> {
                withContext(Dispatchers.Main) {
                    stopStopwatch()
                }
            }

            ACTION_START_EXTRA -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "타이머"
                val ms = intent.getLongExtra(EXTRA_DURATION_MS, 0L).coerceAtLeast(0L)
                withContext(Dispatchers.Main) {
                    startExtra(id, label, ms)
                }
            }
            ACTION_STOP_EXTRA -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                withContext(Dispatchers.Main) {
                    stopExtra(id)
                }
            }

            ACTION_STOP_ALL -> {
                withContext(Dispatchers.Main) {
                    stopTimer()
                    stopStopwatch()
                    stopAllExtras()
                }
            }

            ACTION_KEEP_ALIVE -> {
                withContext(Dispatchers.Main) {
                    restorePersistedState()
                    scheduleKeepAliveAlarm()
                }
            }
        }
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
            .putLong(KEY_TIMER_TOTAL_DURATION, timerTotalDurationMs)
            .apply()
    }

    private fun clearTimerState() {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TIMER_END_ELAPSED)
            .remove(KEY_TIMER_PAUSED)
            .remove(KEY_TIMER_PAUSED_REMAIN)
            .remove(KEY_TIMER_TOTAL_DURATION)
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
                put("durationMs", timer.totalDurationMs)
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
        val savedTotalDuration = prefs.getLong(KEY_TIMER_TOTAL_DURATION, 0L)
        if (savedTotalDuration > 0L) timerTotalDurationMs = savedTotalDuration

        if (wasPaused && savedPausedRemain > 0L) {
            isTimerPaused = true
            pausedRemainingMs = savedPausedRemain
            if (timerTotalDurationMs <= 0L) timerTotalDurationMs = savedPausedRemain
            ensureForeground(Leader.TIMER)
            notifyTimer(pausedRemainingMs)
        } else if (savedEndElapsed > 0L) {
            val remain = savedEndElapsed - System.currentTimeMillis()
            if (remain > 0L) {
                timerEndElapsed = savedEndElapsed
                if (timerTotalDurationMs <= 0L) timerTotalDurationMs = remain
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
                    val durationMs = if (obj.has("durationMs")) obj.optLong("durationMs", 0L) else 0L

                    val remain = endElapsed - System.currentTimeMillis()
                    if (remain > 0L) {
                        val total = if (durationMs > 0L) durationMs else remain
                        startExtraFromEndElapsed(id, label, endElapsed, total)
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
        timerTotalDurationMs = durationMs.coerceAtLeast(0L)
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
                    timerTotalDurationMs = 0L
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

        // 🔹 상태 전송 강화: 즉시 전송 후 추가 확인
        publishTimerState(STATE_PAUSED, pausedRemainingMs, force = true)

        // 🔹 100ms 후 한번 더 전송하여 확실성 보장
        scope.launch {
            delay(100)
            publishTimerState(STATE_PAUSED, pausedRemainingMs, force = true)
        }

        onChannelPossiblyIdle()
    }

    private fun resumeTimer() {
        if (!isTimerPaused || pausedRemainingMs <= 0L) return
        isTimerPaused = false

        ensureChannels()
        // 🔹 총 기간 정보가 없으면(구버전 복원 등) 남은 시간을 총 기간으로 사용
        if (timerTotalDurationMs <= 0L) timerTotalDurationMs = pausedRemainingMs

        timerEndElapsed = System.currentTimeMillis() + pausedRemainingMs
        pausedRemainingMs = 0L

        persistTimerState()
        ensureForeground(Leader.TIMER)
        startTimerJob()
        scheduleKeepAliveAlarm()
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        isTimerPaused = false
        pausedRemainingMs = 0L
        timerTotalDurationMs = 0L
        clearTimerState()
        cancelKeepAliveAlarm()
        cancelTimerNotification()

        // 🔹 상태 전송 강화: 즉시 전송 후 추가 확인
        publishTimerState(STATE_STOPPED, 0L, force = true)

        // 🔹 100ms 후 한번 더 전송하여 확실성 보장
        scope.launch {
            delay(100)
            publishTimerState(STATE_STOPPED, 0L, force = true)
        }

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
        startExtraFromEndElapsed(id, label, endElapsed, durationMs)
    }

    private fun startExtraFromEndElapsed(id: String, label: String, endElapsed: Long, totalDurationMs: Long) {
        ensureChannels()

        extraTimers[id]?.job?.cancel()

        val state = ExtraTimerState(id, label, endElapsed, totalDurationMs.coerceAtLeast(0L))
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
            val infoLine = buildTimerInfoLine(timerTotalDurationMs, timerEndElapsed, running)
            val content = if (running) {
                if (infoLine.isNotBlank()) "남은 시간 ${'$'}{formatHMS(remainingMs)} • ${'$'}infoLine" else "남은 시간 ${'$'}{formatHMS(remainingMs)}"
            } else {
                if (infoLine.isNotBlank()) "일시정지 • ${'$'}{formatHMS(remainingMs)} • ${'$'}infoLine" else "일시정지 • ${'$'}{formatHMS(remainingMs)}"
            }

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

        // 알림(삼성 스타일): 남은 시간과 함께 "언제 울리는지"(종료 시각)를 보여준다.
        // 긴 타이머(예: 34시간)는 텍스트가 길어지기 쉬워서 종료 시각을 우선 표시한다.
        val infoLine = buildTimerInfoLine(timerTotalDurationMs, timerEndElapsed, running)
        compact.setTextViewText(R.id.subtitle, infoLine)
        expanded.setTextViewText(R.id.subtitle_big, infoLine)

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
            expanded.setImageViewResource(R.id.btn_toggle_big, R.drawable.ic_pause)
            expanded.setOnClickPendingIntent(R.id.btn_toggle_big, piPause)
            compact.setViewVisibility(R.id.btn_toggle, View.VISIBLE)
            expanded.setViewVisibility(R.id.btn_toggle_big, View.VISIBLE)
        } else {
            if (remainingMs > 0L) {
                compact.setImageViewResource(R.id.btn_toggle, R.drawable.ic_play)
                compact.setOnClickPendingIntent(R.id.btn_toggle, piResume)
                expanded.setImageViewResource(R.id.btn_toggle_big, R.drawable.ic_play)
                expanded.setOnClickPendingIntent(R.id.btn_toggle_big, piResume)
                compact.setViewVisibility(R.id.btn_toggle, View.VISIBLE)
                expanded.setViewVisibility(R.id.btn_toggle_big, View.VISIBLE)
            } else {
                compact.setViewVisibility(R.id.btn_toggle, View.GONE)
                expanded.setViewVisibility(R.id.btn_toggle_big, View.GONE)
            }
        }
        compact.setOnClickPendingIntent(R.id.btn_stop, piStop)
        expanded.setImageViewResource(R.id.btn_stop_big, R.drawable.ic_stop)
        expanded.setOnClickPendingIntent(R.id.btn_stop_big, piStop)

        val infoLineForText = buildTimerInfoLine(timerTotalDurationMs, timerEndElapsed, running)

        val content = if (running) {
            if (infoLineForText.isNotBlank()) "남은 시간 ${'$'}{formatHMS(remainingMs)} • ${'$'}infoLineForText" else "남은 시간 ${'$'}{formatHMS(remainingMs)}"
        } else {
            if (infoLineForText.isNotBlank()) "일시정지 • ${'$'}{formatHMS(remainingMs)} • ${'$'}infoLineForText" else "일시정지 • ${'$'}{formatHMS(remainingMs)}"
        }

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
        val state = extraTimers[id]
        val endAt = state?.endElapsed ?: (System.currentTimeMillis() + remainingMs.coerceAtLeast(0L))
        val total = state?.totalDurationMs ?: remainingMs.coerceAtLeast(0L)

        val infoLine = if (total > 0L) {
            // 예: "종료 오후 3:12 (30분)", "종료 내일 1월 4일 오후 2:40 (34시간 48분)"
            "종료 ${formatEndAtKorean(endAt)} (${formatDurationShortKorean(total)})"
        } else ""

        // 🔹 삼성 타이머처럼 "타이머 00:29:47" 형태로 타이틀에 남은 시간을 함께 표기
        val title = if (remainingMs > 0L) "${label}  ${formatHMS(remainingMs)}" else label
        val content = if (infoLine.isNotBlank()) infoLine else "${formatHMS(remainingMs)} 남음"

        val fixedWhen = extraWhen[id] ?: System.currentTimeMillis()
        val order = extraOrder[id] ?: 0
        val sortKey = String.format(Locale.US, "%06d", Int.MAX_VALUE - order)

        return NotificationCompat.Builder(this, EXTRA_CHANNEL)
            .setContentTitle(title)
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

        // 🔹 강제 전송이 아니면 중복 체크
        if (!force && state == lastPublishedState && remain == lastPublishedRemain) {
            return
        }

        lastPublishedState = state
        lastPublishedRemain = remain

        // SharedPreferences에 상태 저장
        getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_STATE, state)
            .putLong(KEY_LAST_REMAIN, remain)
            .commit() // 🔹 apply() 대신 commit()으로 즉시 저장

        // 명시적 브로드캐스트 전송
        val intent = Intent(ACTION_TIMER_STATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_REMAIN_MS, remain)
            setPackage(packageName)
            // 🔹 플래그 추가로 안정성 향상
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
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

    // 🔹 개선된 PendingIntent 생성: 고유한 requestCode 사용
    private fun serviceActionPendingIntent(action: String): PendingIntent {
        val i = Intent(this, ClockService::class.java).apply {
            this.action = action
            // 🔹 액션을 확실히 전달하기 위한 추가 데이터
            putExtra("action_timestamp", System.currentTimeMillis())
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // 🔹 각 액션마다 다른 requestCode 사용하여 충돌 방지
        val requestCode = when (action) {
            ACTION_PAUSE_TIMER -> 1001
            ACTION_RESUME_TIMER -> 1002
            ACTION_STOP_TIMER -> 1003
            else -> action.hashCode()
        }

        return PendingIntent.getService(this, requestCode, i, flags)
    }

    private fun formatHMS(msInput: Long): String {
        val ms = msInput.coerceAtLeast(0L)
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }



    // ========== 표시 포맷 유틸 (알림/화면 공통 컨셉) ==========
    private fun formatDurationKorean(durationMs: Long): String {
        val ms = durationMs.coerceAtLeast(0L)
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60

        val parts = mutableListOf<String>()
        if (h > 0) parts += "${h}시간"
        if (m > 0) parts += "${m}분"
        if (h == 0L && m == 0L) {
            parts += "${s}초"
        } else if (s > 0) {
            parts += "${s}초"
        }
        return if (parts.isEmpty()) "0초" else parts.joinToString(" ")
    }

    private fun formatEndAtKorean(endAtMillis: Long): String {
        val now = java.util.Calendar.getInstance()
        val today0 = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val end = java.util.Calendar.getInstance().apply { timeInMillis = endAtMillis }
        val end0 = (end.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val diffDays = ((end0 - today0) / 86_400_000L).toInt()

        val date = java.util.Date(endAtMillis)
        val timeFmt = java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA)
        val dayFmt = java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREA)

        return when (diffDays) {
            0 -> timeFmt.format(date)
            1 -> "내일 ${dayFmt.format(date)} ${timeFmt.format(date)}"
            else -> "${dayFmt.format(date)} ${timeFmt.format(date)}"
        }
    }

    /**
     * 사용자 요구사항:
     * - 알림(접힘/펼침) 하단에 "설정한 시간"과 "종료(울림) 시각"을 같이 보여주기
     * - 내일/다음날이면 날짜까지 포함
     * - 텍스트가 길어 잘리기 쉬우므로 "종료 시각"을 먼저 보여주고, ( ) 안에 기간을 붙인다.
     */
    private fun buildTimerInfoLine(totalDurationMs: Long, endAtMillis: Long, running: Boolean): String {
        if (totalDurationMs <= 0L || endAtMillis <= 0L) return ""
        return if (running) {
            "종료 ${formatEndAtKorean(endAtMillis)} (${formatDurationShortKorean(totalDurationMs)})"
        } else {
            "일시정지"
        }
    }

    /**
     * 알림 표시용 기간 문자열(짧게): 초 단위는 과도하게 길어져서 기본적으로 숨김.
     *  - 30분
     *  - 2시간 10분
     *  - 34시간 48분
     */
    private fun formatDurationShortKorean(durationMs: Long): String {
        val ms = durationMs.coerceAtLeast(0L)
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60

        val parts = mutableListOf<String>()
        if (h > 0) parts += "${h}시간"
        if (m > 0) parts += "${m}분"
        if (h == 0L && m == 0L) {
            parts += "${s}초"
        }

        return if (parts.isEmpty()) "0초" else parts.joinToString(" ")
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