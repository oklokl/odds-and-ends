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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // ✅ 서비스 인텐트(액션) 처리 직렬화: 기존처럼 "처리 중이면 스킵"(drop)하면
    // MAIN_TIMER_ALARM_FIRED 같은 중요한 종료 통지가 유실되어 알림이 음수로 남는
    // 간헐적 현상이 발생할 수 있음. Mutex로 순차 처리하여 유실을 방지한다.
    private val actionMutex = Mutex()

    // ===== 메인 타이머 =====
    private var timerJob: Job? = null
    private var timerEndElapsed: Long = 0L
    // 🔹 wall-clock(epoch millis) 기준 종료 시각 (알림/화면에 "오후 3:12" 같은 표기용)
    //    + AlarmManager(RTC) 기반 정확 알람 매칭 검증용
    private var timerEndAtWall: Long = 0L
    private var isTimerPaused: Boolean = false
    private var pausedRemainingMs: Long = 0L

    // 🔹 표시용: 시작 시 설정된 전체 기간(알림에 '30분 / 오후 3:12' 표시용)
    private var timerTotalDurationMs: Long = 0L

    // ===== 스톱워치 =====
    private var stopwatchJob: Job? = null
    private var stopwatchBase: Long = 0L

    // ===== 스톱워치2(전환 화면) =====
    private var stopwatch2Job: Job? = null
    private var stopwatch2Base: Long = 0L

    // ===== 보조 타이머 =====
    private data class ExtraTimerState(
        val id: String,
        val label: String,
        var endElapsed: Long,
        // ✅ UI/알림(종료 시각 표기/복원)용 wall-clock(epoch millis)
        var endAtWall: Long,
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
    private var currentForegroundId: Int = -1
    enum class Leader { TIMER, STOPWATCH, STOPWATCH2, EXTRA }

    private fun fgIdFor(leader: Leader): Int = when (leader) {
        Leader.TIMER -> FOREGROUND_ID_TIMER
        Leader.EXTRA -> FOREGROUND_ID_EXTRA
        Leader.STOPWATCH -> FOREGROUND_ID_STOPWATCH
        Leader.STOPWATCH2 -> FOREGROUND_ID_STOPWATCH2
    }

    private fun startOrSwitchForeground(leader: Leader, notification: Notification) {
        val newId = fgIdFor(leader)
        // 기존 FG 알림이 다른 ID였다면 먼저 제거해서 "옛 RemoteViews(타이머)" 잔존을 차단
        if (isInForeground && currentForegroundId != -1 && currentForegroundId != newId) {
            cancelSafe(currentForegroundId)
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(newId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(newId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(newId, notification)
            }
        }

        isInForeground = true
        foregroundLeader = leader
        currentForegroundId = newId
    }

    // ===== 상태 동기화 =====
    private val ACTION_TIMER_STATE = "com.krdonon.timer.action.TIMER_STATE"
    private val EXTRA_STATE = "state"
    private val EXTRA_REMAIN_MS = "remain_ms"
    // RUNNING 상태에서 UI가 부드럽게 흘러가도록 기준 시각(ELAPSED_REALTIME)을 함께 전달
    private val EXTRA_END_ELAPSED = "end_elapsed"
    private val EXTRA_SESSION_ID = "session_id"
    private val STATE_RUNNING = "RUNNING"
    private val STATE_PAUSED  = "PAUSED"
    private val STATE_STOPPED = "STOPPED"
    private val STATE_FINISHED = "FINISHED"

    // ✅ AlarmManager(잠금/Doze)로 타이머가 울렸을 때, 코루틴 루프가 깨어있지 않으면
    // RemoteViews Chronometer가 0을 지나 음수로 흘러가는 현상이 발생할 수 있음.
    // AlarmReceiver가 이 액션으로 ClockService에 종료 처리를 통지한다.
    // (companion object의 ACTION_MAIN_TIMER_ALARM_FIRED 사용)

    // ===== 지속 저장용 SharedPreferences =====
    private val PERSIST_PREFS = "clock_persist_prefs"
    private val KEY_TIMER_END_ELAPSED = "timer_end_elapsed"
    private val KEY_TIMER_END_WALL = "timer_end_wall"
    private val KEY_TIMER_PAUSED = "timer_paused"
    private val KEY_TIMER_PAUSED_REMAIN = "timer_paused_remain"
    private val KEY_TIMER_TOTAL_DURATION = "timer_total_duration"
    private val KEY_TIMER_SESSION_ID = "timer_session_id"
    private val KEY_STOPWATCH_BASE = "stopwatch_base"
    private val KEY_STOPWATCH_RUNNING = "stopwatch_running"
    private val KEY_STOPWATCH2_BASE = "stopwatch2_base"
    private val KEY_STOPWATCH2_RUNNING = "stopwatch2_running"
    private val KEY_STOPWATCH2_ACCUMULATED = "stopwatch2_accumulated"
    private val KEY_EXTRA_TIMERS_JSON = "extra_timers_json"

    // ===== Keep-Alive 알람 =====
    private val KEEP_ALIVE_INTERVAL_MS = 30 * 60 * 1000L
    private val KEEP_ALIVE_REQUEST_CODE = 99999


    // ===== Extra Timer Finish Alarm (for reliability) =====
    private val ACTION_EXTRA_ALARM = "com.krdonon.timer.action.EXTRA_ALARM"
    private val EXTRA_ALARM_REQUEST_BASE = 71000

    private val SYNC_PREFS = "clock_sync_prefs"
    private val KEY_LAST_STATE = "key_state"
    private val KEY_LAST_REMAIN = "key_remain_ms"
    private val KEY_LAST_SESSION_ID = "key_session_id"
    private var lastPublishMs = 0L

    // ✅ 중복 브로드캐스트 방지
    private var lastPublishedState = ""
    private var lastPublishedRemain = -1L
    private var timerSessionId = 0L

    // 🔹 알림을 눌러 앱으로 진입할 때(SystemUI 패널 애니메이션 중), 알림이 계속 갱신되면
    // "접히려다 마는" 현상이 발생할 수 있어 짧은 시간 동안 notify()를 중단한다.
    @Volatile private var suspendNotifyUntilElapsed: Long = 0L


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i(applicationContext, "ClockService", "service created")
        restorePersistedState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ✅ 인텐트를 버리지 않고, Mutex로 순차 처리
        scope.launch {
            actionMutex.withLock {
                handleAction(intent)
            }
        }

        AppLog.d(applicationContext, "ClockService", "onStartCommand action=${intent?.action}")
        return START_STICKY
    }

    // 🔹 액션 처리를 별도 메서드로 분리
    private suspend fun handleAction(intent: Intent?) {
        when (intent?.action) {
            ACTION_MAIN_TIMER_ALARM_FIRED -> {
                AppLog.d(applicationContext, "ClockService", "ACTION_MAIN_TIMER_ALARM_FIRED")
                // AlarmReceiver가 호출: 메인 타이머 종료 시각에 Doze로 루프가 정지돼도
                // 알림/상태를 확실히 종료 처리하기 위함.
                val endAt = intent.getLongExtra(EXTRA_TIMER_END_AT_WALL, -1L)
                withContext(Dispatchers.Main) {
                    handleMainTimerExpiredFromAlarm(endAt)
                }
            }
            ACTION_SUSPEND_NOTIFY_UPDATES -> {
                val ms = intent.getLongExtra(EXTRA_SUSPEND_MS, 900L).coerceIn(0L, 5_000L)
                val until = SystemClock.elapsedRealtime() + ms
                if (until > suspendNotifyUntilElapsed) suspendNotifyUntilElapsed = until
                AppLog.d(applicationContext, "ClockService", "suspend notify updates ${ms}ms")
            }
            ACTION_START_TIMER -> {
                val targetAtWallMs = intent.getLongExtra(EXTRA_TARGET_AT_WALL_MS, -1L)
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L).coerceAtLeast(0L)
                AppLog.d(applicationContext, "ClockService", "ACTION_START_TIMER durationMs=$durationMs targetAtWallMs=$targetAtWallMs")
                withContext(Dispatchers.Main) {
                    isTimerPaused = false
                    if (targetAtWallMs > 0L) {
                        startTimerAt(targetAtWallMs)
                    } else {
                        startTimer(durationMs)
                    }
                    // 🔹 약간의 딜레이 후 상태 전송으로 안정성 향상
                    delay(50)
                    val remainForPublish = (timerEndAtWall - System.currentTimeMillis()).coerceAtLeast(0L)
                    publishTimerState(STATE_RUNNING, remainForPublish, force = true)
                }
            }
            ACTION_STOP_TIMER -> {
                AppLog.d(applicationContext, "ClockService", "ACTION_STOP_TIMER")
                withContext(Dispatchers.Main) {
                    stopTimer()
                }
            }
            ACTION_PAUSE_TIMER -> {
                AppLog.d(applicationContext, "ClockService", "ACTION_PAUSE_TIMER")
                withContext(Dispatchers.Main) {
                    pauseTimer()
                }
            }
            ACTION_RESUME_TIMER -> {
                AppLog.d(applicationContext, "ClockService", "ACTION_RESUME_TIMER paused=$isTimerPaused remain=$pausedRemainingMs")
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

            ACTION_START_STOPWATCH2 -> {
                val base = intent.getLongExtra(EXTRA_STOPWATCH2_BASE, 0L)
                withContext(Dispatchers.Main) {
                    startStopwatch2(baseElapsed = base)
                }
            }
            ACTION_PAUSE_STOPWATCH2 -> {
                val elapsed = intent.getLongExtra(EXTRA_STOPWATCH2_ELAPSED, 0L)
                withContext(Dispatchers.Main) {
                    pauseStopwatch2(elapsedMs = elapsed)
                }
            }
            ACTION_RESET_STOPWATCH2 -> {
                withContext(Dispatchers.Main) {
                    resetStopwatch2()
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

    private fun newTimerSessionId(): Long = System.currentTimeMillis()

    // ===================== 상태 지속 저장/복원 =====================
    private fun persistTimerState() {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TIMER_END_ELAPSED, timerEndElapsed)
            .putLong(KEY_TIMER_END_WALL, timerEndAtWall)
            .putBoolean(KEY_TIMER_PAUSED, isTimerPaused)
            .putLong(KEY_TIMER_PAUSED_REMAIN, pausedRemainingMs)
            .putLong(KEY_TIMER_TOTAL_DURATION, timerTotalDurationMs)
            .putLong(KEY_TIMER_SESSION_ID, timerSessionId)
            .apply()
    }

    private fun clearTimerState() {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TIMER_END_ELAPSED)
            .remove(KEY_TIMER_END_WALL)
            .remove(KEY_TIMER_PAUSED)
            .remove(KEY_TIMER_PAUSED_REMAIN)
            .remove(KEY_TIMER_TOTAL_DURATION)
            .remove(KEY_TIMER_SESSION_ID)
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

    private fun persistStopwatch2State(running: Boolean, accumulatedMs: Long) {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STOPWATCH2_BASE, stopwatch2Base)
            .putBoolean(KEY_STOPWATCH2_RUNNING, running)
            .putLong(KEY_STOPWATCH2_ACCUMULATED, accumulatedMs)
            .apply()
    }

    private fun clearStopwatch2State() {
        getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_STOPWATCH2_BASE)
            .remove(KEY_STOPWATCH2_RUNNING)
            .remove(KEY_STOPWATCH2_ACCUMULATED)
            .apply()
    }

    private fun persistExtraTimers() {
        val jsonArray = JSONArray()
        extraTimers.values.forEach { timer ->
            val obj = JSONObject().apply {
                put("id", timer.id)
                put("label", timer.label)
                put("endElapsed", timer.endElapsed)
                // ✅ UI/알림(종료 날짜 표기)와 TimerFragment 복원은 wall-clock(epoch millis)을 사용
                put("endAtWall", timer.endAtWall)
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
        val savedEndWall = prefs.getLong(KEY_TIMER_END_WALL, 0L)
        val wasPaused = prefs.getBoolean(KEY_TIMER_PAUSED, false)
        val savedPausedRemain = prefs.getLong(KEY_TIMER_PAUSED_REMAIN, 0L)
        val savedTotalDuration = prefs.getLong(KEY_TIMER_TOTAL_DURATION, 0L)
        val savedSessionId = prefs.getLong(KEY_TIMER_SESSION_ID, 0L)
        if (savedTotalDuration > 0L) timerTotalDurationMs = savedTotalDuration

        if (wasPaused && savedPausedRemain > 0L) {
            timerSessionId = savedSessionId
            isTimerPaused = true
            pausedRemainingMs = savedPausedRemain
            if (timerTotalDurationMs <= 0L) timerTotalDurationMs = savedPausedRemain
            ensureForeground(Leader.TIMER)
            notifyTimer(pausedRemainingMs)
        } else if (savedEndElapsed > 0L) {
            // ✅ 타이머는 반드시 monotonic clock(ELAPSED_REALTIME)을 기준으로 계산해야
            // 시스템 시간 보정(NTP/사용자 변경)에도 숫자가 뒤로 튀지 않습니다.
            val remain = savedEndElapsed - SystemClock.elapsedRealtime()
            if (remain > 0L) {
                timerSessionId = savedSessionId
                timerEndElapsed = savedEndElapsed
                // ✅ wall 값이 저장돼 있으면 그대로 사용, 없으면(elapsed만 있던 구버전 호환)
                // 현재 remain을 기준으로 재구성
                timerEndAtWall = if (savedEndWall > 0L) savedEndWall else (System.currentTimeMillis() + remain)
                if (timerTotalDurationMs <= 0L) timerTotalDurationMs = remain
                startTimerJob()
                scheduleKeepAliveAlarm()
            } else {
                // ✅ 프로세스가 죽었다가 다시 살아날 때,
                // 이미 끝난 타이머의 알림(RemoteViews Chronometer)이 SystemUI에 남아
                // 음수로 계속 내려갈 수 있음 → 복원 단계에서 함께 정리
                timerSessionId = savedSessionId
                clearTimerState()
                publishTimerState(STATE_FINISHED, 0L, force = true)
                timerSessionId = 0L
                cancelSafe(NID_TIMER)
                // Foreground ID는 아직 다른 리더가 시작되기 전이므로 제거해도 안전
                cancelSafe(FOREGROUND_ID_TIMER)
            }
        }

        val swBase = prefs.getLong(KEY_STOPWATCH_BASE, 0L)
        val swRunning = prefs.getBoolean(KEY_STOPWATCH_RUNNING, false)
        if (swRunning && swBase > 0L) {
            stopwatchBase = swBase
            startStopwatchJob()
        }

        val sw2Running = prefs.getBoolean(KEY_STOPWATCH2_RUNNING, false)
        val sw2Base = prefs.getLong(KEY_STOPWATCH2_BASE, 0L)
        val sw2Accum = prefs.getLong(KEY_STOPWATCH2_ACCUMULATED, 0L)
        if (sw2Running && sw2Base > 0L) {
            stopwatch2Base = sw2Base
            // ✅ 프로세스 재생성에서도 백그라운드 유지(포그라운드 승격)
            ensureForeground(Leader.STOPWATCH2)
            notifyStopwatch2((SystemClock.elapsedRealtime() - stopwatch2Base).coerceAtLeast(0L))
            startStopwatch2Job()
        } else if (!sw2Running && sw2Accum > 0L) {
            // paused 상태: 알림은 띄우지 않고 값만 유지
            stopwatch2Base = 0L
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
                    val endAtWall = if (obj.has("endAtWall")) obj.optLong("endAtWall", 0L) else 0L
                    val durationMs = if (obj.has("durationMs")) obj.optLong("durationMs", 0L) else 0L

                    val remain = endElapsed - SystemClock.elapsedRealtime()
                    if (remain > 0L) {
                        val total = if (durationMs > 0L) durationMs else remain
                        // 구버전 호환: endAtWall이 없으면 남은 시간 기준으로 재구성
                        val wall = if (endAtWall > 0L) endAtWall else (System.currentTimeMillis() + remain)
                        startExtraFromEndElapsed(id, label, endElapsed, wall, total)
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
        timerSessionId = newTimerSessionId()
        AppLog.i(applicationContext, "ClockService", "new timer session id=$timerSessionId durationMs=$durationMs")
        // ✅ ELAPSED_REALTIME 기반으로 종료 시각 저장
        timerEndElapsed = SystemClock.elapsedRealtime() + durationMs
        // ✅ 표시/알림/AlarmManager(RTC)용 wall-clock 종료 시각도 함께 저장
        timerEndAtWall = System.currentTimeMillis() + durationMs
        persistTimerState()
        ensureForeground(Leader.TIMER)
        startTimerJob()
        scheduleKeepAliveAlarm()
    }

    private fun startTimerAt(targetAtWallMs: Long) {
        ensureChannels()
        val remain = (targetAtWallMs - System.currentTimeMillis()).coerceAtLeast(0L)
        timerTotalDurationMs = remain
        timerSessionId = newTimerSessionId()
        AppLog.i(applicationContext, "ClockService", "new timer session id=$timerSessionId targetAtWallMs=$targetAtWallMs remain=$remain")
        timerEndElapsed = SystemClock.elapsedRealtime() + remain
        timerEndAtWall = targetAtWallMs
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
                val nowElapsed = SystemClock.elapsedRealtime()
                val remain = timerEndElapsed - nowElapsed
                if (remain <= 0L) {
                    val finishedSessionId = timerSessionId
                    com.krdonon.timer.alarm.AlarmService.start(this@ClockService, "메인 타이머")

                    // ✅ 메인 타이머 종료 시점에 알림 갱신(FOREGROUND_ID=42) enqueue가 몰리면
                    // 시스템이 "enqueue rate" 제한으로 갱신을 버리는 경우가 있습니다.
                    // 그 결과, 마지막 알림의 Chronometer가 0을 지나 "-00:12"처럼 음수로 계속 진행하는
                    // 현상이 발생할 수 있어, 종료 순간에는 불필요한 notify(0) enqueue를 최소화합니다.
                    publishTimerState(STATE_FINISHED, 0L, force = true)
                    AppLog.i(applicationContext, "ClockService", "timer finished session=$finishedSessionId")

                    val hasExtras = extraTimers.isNotEmpty()
                    if (hasExtras) {
                        // 타이머 종료 후에도 보조 타이머가 남아있으면 Foreground를 EXTRA로 즉시 넘겨
                        // 메인 타이머 알림(42)이 음수로 흘러가지 않도록 한다.
                        // 혹시 남아있을 수 있는 백그라운드 타이머 알림은 정리
                        cancelSafe(NID_TIMER)
                        // 실기기에서 Foreground(타이머) 알림이 남아있을 수 있어 추가로 정리
                        cancelSafe(FOREGROUND_ID_TIMER)
                        startOrSwitchForeground(Leader.EXTRA, buildExtraSummaryForegroundNotification())
                    } else {
                        // 보조 타이머가 없으면 메인 타이머 알림을 바로 제거
                        cancelTimerNotification()
                    }

                    timerTotalDurationMs = 0L
                    clearTimerState()
                    timerSessionId = 0L
                    cancelKeepAliveAlarm()
                    break
                } else {
                    val t = nowElapsed
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
        pausedRemainingMs = (timerEndElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        isTimerPaused = true
        // 일시정지 중에는 종료 시각이 의미가 없으므로 0으로(표시 혼동 방지)
        timerEndAtWall = 0L
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

        timerEndElapsed = SystemClock.elapsedRealtime() + pausedRemainingMs
        timerEndAtWall = System.currentTimeMillis() + pausedRemainingMs
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
        timerEndAtWall = 0L
        timerTotalDurationMs = 0L
        clearTimerState()
        cancelKeepAliveAlarm()
        cancelTimerNotification()

        // 🔹 상태 전송 강화: 즉시 전송 후 추가 확인
        val stoppedSessionId = timerSessionId
        publishTimerState(STATE_STOPPED, 0L, force = true)

        // 🔹 100ms 후 한번 더 전송하여 확실성 보장
        scope.launch {
            delay(100)
            publishTimerState(STATE_STOPPED, 0L, force = true)
            AppLog.i(applicationContext, "ClockService", "timer stopped session=$stoppedSessionId")
            timerSessionId = 0L
        }

        onChannelPossiblyIdle()
    }

    /**
     * AlarmReceiver(정확 알람)에서 호출.
     *
     * 실기기에서 화면이 잠기거나 Doze에 들어가면 CountDownTimer/코루틴 루프가 멈춰서
     * 메인 타이머 종료 분기(remain<=0)를 실행하지 못할 수 있습니다.
     * 이 경우 알림(RemoteViews Chronometer)이 0을 지나 음수로 내려가며,
     * 알림 버튼도 먹통처럼 보이는 증상이 발생합니다.
     *
     * 알람이 울리는 순간 ClockService가 종료 처리를 수행하도록 별도의 경로를 둡니다.
     */
    private fun handleMainTimerExpiredFromAlarm(endAtWallMsFromAlarm: Long) {
        // 1) 정말로 "현재 메인 타이머"의 알람인지 확인(오래된 알람/중복 알람 방지)
        val prefs = getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE)
        val persistedEnd = prefs.getLong(KEY_TIMER_END_ELAPSED, 0L)
        val persistedWall = prefs.getLong(KEY_TIMER_END_WALL, 0L)
        val activeEndElapsed = if (timerEndElapsed > 0L) timerEndElapsed else persistedEnd
        val activeEndWall = if (timerEndAtWall > 0L) timerEndAtWall else persistedWall

        if (activeEndElapsed <= 0L) {
            // 이미 정리된 상태. 혹시 남아있을 수 있는 타이머 알림만 제거
            cancelSafe(NID_TIMER)
            if (foregroundLeader == Leader.TIMER || foregroundLeader == null) {
                cancelSafe(FOREGROUND_ID_TIMER)
            }
            return
        }

        if (endAtWallMsFromAlarm > 0L) {
            // ✅ AlarmReceiver는 wall-clock(epoch millis)을 담아 오므로, 비교도 wall 값으로 해야 한다.
            val diff = kotlin.math.abs(activeEndWall - endAtWallMsFromAlarm)
            if (diff > 2_000L) {
                // 다른 타이머(구 알람)일 가능성
                android.util.Log.d(
                    "ClockService",
                    "⚠️ MAIN_TIMER_ALARM_FIRED 무시: end mismatch activeWall=$activeEndWall alarm=$endAtWallMsFromAlarm"
                )
                return
            }
        }

        // 2) 메인 타이머 종료 처리(알림/상태 정리)
        val finishedSessionId = timerSessionId
        timerJob?.cancel()
        timerJob = null
        isTimerPaused = false
        pausedRemainingMs = 0L
        timerTotalDurationMs = 0L
        timerEndElapsed = 0L

        clearTimerState()
        cancelKeepAliveAlarm()
        publishTimerState(STATE_FINISHED, 0L, force = true)
        AppLog.i(applicationContext, "ClockService", "timer finished from alarm session=$finishedSessionId endAt=$endAtWallMsFromAlarm")

        val hasExtras = extraTimers.isNotEmpty()
        if (hasExtras) {
            // 메인 타이머 알림(Chronometer)을 확실히 제거/교체하기 위해 Foreground를 EXTRA로 전환
            cancelSafe(NID_TIMER)
            cancelSafe(FOREGROUND_ID_TIMER)
            startOrSwitchForeground(Leader.EXTRA, buildExtraSummaryForegroundNotification())
            postOrUpdateExtraSummary(force = true)
        } else {
            // 보조 타이머가 없으면 타이머 알림을 제거하고 서비스 상태 재평가
            cancelTimerNotification()
            onChannelPossiblyIdle()
        }
        timerSessionId = 0L
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

    // ===================== Stopwatch2 =====================
    private fun startStopwatch2(baseElapsed: Long = 0L) {
        ensureChannels()
        // baseElapsed: 이미 경과한 시간(ms)
        stopwatch2Base = SystemClock.elapsedRealtime() - baseElapsed
        persistStopwatch2State(running = true, accumulatedMs = 0L)
        ensureForeground(Leader.STOPWATCH2)
        startStopwatch2Job()
    }

    private fun startStopwatch2Job() {
        stopwatch2Job?.cancel()
        stopwatch2Job = scope.launch {
            var last = 0L
            while (isActive) {
                val elapsed = (SystemClock.elapsedRealtime() - stopwatch2Base).coerceAtLeast(0L)
                val t = System.currentTimeMillis()
                if (t - last >= 1000) {
                    notifyStopwatch2(elapsed)
                    persistStopwatch2State(running = true, accumulatedMs = 0L)
                    last = t
                }
                delay(100)
            }
        }
    }

    private fun pauseStopwatch2(elapsedMs: Long) {
        stopwatch2Job?.cancel()
        stopwatch2Job = null
        stopwatch2Base = 0L
        persistStopwatch2State(running = false, accumulatedMs = elapsedMs.coerceAtLeast(0L))
        cancelStopwatch2Notification()
        onChannelPossiblyIdle()
    }

    private fun resetStopwatch2() {
        stopwatch2Job?.cancel()
        stopwatch2Job = null
        stopwatch2Base = 0L
        clearStopwatch2State()
        cancelStopwatch2Notification()
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
        // ✅ 보조 타이머도 ELAPSED_REALTIME 기반으로 계산(숫자 뒤로 튐 방지)
        val endElapsed = SystemClock.elapsedRealtime() + durationMs
        val endAtWall = System.currentTimeMillis() + durationMs
        startExtraFromEndElapsed(id, label, endElapsed, endAtWall, durationMs)
    }

    private fun startExtraFromEndElapsed(
        id: String,
        label: String,
        endElapsed: Long,
        endAtWall: Long,
        totalDurationMs: Long
    ) {
        ensureChannels()

        extraTimers[id]?.job?.cancel()

        // endAtWall이 0이거나 비정상이면(remain 기반 복원) 지금 시각에서 재구성
        val safeEndAtWall = if (endAtWall > 0L) endAtWall else run {
            val remain = (endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            System.currentTimeMillis() + remain
        }
        val state = ExtraTimerState(id, label, endElapsed, safeEndAtWall, totalDurationMs.coerceAtLeast(0L))
        extraTimers[id] = state

        val notifyId = extraNotifyId(id)

        if (extraWhen[id] == null) extraWhen[id] = System.currentTimeMillis()
        if (extraOrder[id] == null) extraOrder[id] = extraSeq++

        postOrUpdateExtraSummary(force = true)
        persistExtraTimers()
        scheduleKeepAliveAlarm()
        scheduleExtraFinishAlarm(id, label, endElapsed)

        state.job = scope.launch {
            var last = 0L
            while (isActive) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val remain = state.endElapsed - nowElapsed
                if (remain <= 0L) {
                    cancelExtraFinishAlarm(id)
                    com.krdonon.timer.alarm.AlarmService.start(this@ClockService, label)

                    notifySafe(notifyId, buildExtraNotification(id, label, 0L))
                    delay(300)
                    cancelSafe(notifyId)
                    break
                } else {
                    val t = nowElapsed
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
        cancelExtraFinishAlarm(id)
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
        extraTimers.keys.forEach { cancelExtraFinishAlarm(it) }
        extraTimers.clear()
        extraWhen.clear()
        extraOrder.clear()
        cancelSafe(EXTRA_SUMMARY_ID)
        for (i in 0 until 100) cancelSafe(EXTRA_BASE_NID + i)
        clearExtraTimers()
    }

    private fun extraNotifyId(id: String): Int =
        EXTRA_BASE_NID + (id.hashCode() and 0x7fffffff) % 100


    private fun buildExtraSummaryForegroundNotification(): Notification {
        // Foreground 유지용: 그룹 요약 알림을 그대로 사용 (배터리/정확도 균형)
        return NotificationCompat.Builder(this, EXTRA_CHANNEL)
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
    }

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
            val initial: Notification = when (preferred) {
                Leader.TIMER -> buildTimerNotificationSafe(0L)
                Leader.STOPWATCH -> buildStopwatchNotification(0L)
                Leader.STOPWATCH2 -> buildStopwatch2Notification(0L)
                Leader.EXTRA -> buildExtraSummaryForegroundNotification()
            }
            startOrSwitchForeground(preferred, initial)
        }
    }

    private fun onChannelPossiblyIdle() {
        val timerRunning = timerJob != null
        val stopwatchRunning = stopwatchJob != null
        val stopwatch2Running = stopwatch2Job != null
        val extraRunning = extraTimers.isNotEmpty()

        when {
            timerRunning || isTimerPaused -> {
                // TIMER가 Foreground(FOREGROUND_ID)로 승격되는 경우, 이전에 백그라운드 ID(NID_TIMER)로 남아있던
                // 타이머 알림이 그대로 유지되면 "타이머 알림 2개"처럼 보일 수 있음. 안전하게 정리.
                cancelSafe(NID_TIMER)
                val remain = if (isTimerPaused) pausedRemainingMs
                else (timerEndElapsed - SystemClock.elapsedRealtime())

                startOrSwitchForeground(Leader.TIMER, buildTimerNotificationSafe(remain.coerceAtLeast(0L)))
            }
            stopwatchRunning -> {
                cancelSafe(NID_STOPWATCH)
                val elapsed = (SystemClock.elapsedRealtime() - stopwatchBase).coerceAtLeast(0L)
                startOrSwitchForeground(Leader.STOPWATCH, buildStopwatchNotification(elapsed))
            }
            stopwatch2Running -> {
                cancelSafe(NID_STOPWATCH2)
                val elapsed = (SystemClock.elapsedRealtime() - stopwatch2Base).coerceAtLeast(0L)
                startOrSwitchForeground(Leader.STOPWATCH2, buildStopwatch2Notification(elapsed))
            }
            extraRunning -> {
                // ✅ 이미 EXTRA가 Foreground 리더면 불필요한 startForeground enqueue를 피한다.
                // (메인 타이머 종료 시점에 enqueue가 몰리면 SystemUI가 갱신을 드롭할 수 있음)
                if (foregroundLeader != Leader.EXTRA) {
                    startOrSwitchForeground(Leader.EXTRA, buildExtraSummaryForegroundNotification())
                } else {
                    // Foreground는 이미 유지 중. 요약 알림만 최신으로 맞춘다.
                    postOrUpdateExtraSummary(force = true)
                }
            }

            else -> {
                // ✅ 아무 것도 실행 중이 아니면 Foreground/알림을 정리하고 서비스 종료
                foregroundLeader = null
                cancelSafe(NID_TIMER)
                cancelSafe(NID_STOPWATCH)
                cancelSafe(NID_STOPWATCH2)
                cancelSafe(EXTRA_SUMMARY_ID)

                cancelKeepAliveAlarm()

                if (isInForeground) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(true)
                        }
                    }
                    isInForeground = false
                }
                // 리더별 Foreground ID들도 모두 정리
                cancelSafe(FOREGROUND_ID_TIMER)
                cancelSafe(FOREGROUND_ID_EXTRA)
                cancelSafe(FOREGROUND_ID_STOPWATCH)
                cancelSafe(FOREGROUND_ID_STOPWATCH2)
                currentForegroundId = -1
                stopSelf()
            }
        }
    }

    // ===================== Notifications =====================
    private fun notifyTimer(remainingMs: Long) {
        val id = if (foregroundLeader == Leader.TIMER) FOREGROUND_ID_TIMER else NID_TIMER
        if (id == FOREGROUND_ID_TIMER) {
            // 타이머가 Foreground 알림으로 표시될 때는 백그라운드 ID를 제거해 중복을 방지
            cancelSafe(NID_TIMER)
        }
        notifySafe(id, buildTimerNotificationSafe(remainingMs))
    }

    private fun cancelTimerNotification() {
        val id = if (foregroundLeader == Leader.TIMER) FOREGROUND_ID_TIMER else NID_TIMER
        cancelSafe(id)
    }

    private fun notifyStopwatch(elapsedMs: Long) {
        val id = if (foregroundLeader == Leader.STOPWATCH) FOREGROUND_ID_STOPWATCH else NID_STOPWATCH
        if (id == FOREGROUND_ID_STOPWATCH) {
            // foreground로 표시될 때는 백그라운드용 스톱워치 알림을 제거해 중복을 방지
            cancelSafe(NID_STOPWATCH)
        }
        notifySafe(id, buildStopwatchNotification(elapsedMs))
    }

    private fun cancelStopwatchNotification() {
        val id = if (foregroundLeader == Leader.STOPWATCH) FOREGROUND_ID_STOPWATCH else NID_STOPWATCH
        cancelSafe(id)
    }

    private fun notifyStopwatch2(elapsedMs: Long) {
        val id = if (foregroundLeader == Leader.STOPWATCH2) FOREGROUND_ID_STOPWATCH2 else NID_STOPWATCH2
        if (id == FOREGROUND_ID_STOPWATCH2) {
            cancelSafe(NID_STOPWATCH2)
        }
        notifySafe(id, buildStopwatch2Notification(elapsedMs))
    }

    private fun cancelStopwatch2Notification() {
        val id = if (foregroundLeader == Leader.STOPWATCH2) FOREGROUND_ID_STOPWATCH2 else NID_STOPWATCH2
        cancelSafe(id)
    }

    private fun buildTimerNotificationSafe(remainingMs: Long): Notification {
        return try {
            buildTimerNotificationRemoteViews(remainingMs)
        } catch (_: Throwable) {
            val running = (timerJob != null) && !isTimerPaused
            val infoLine = buildTimerInfoLine(timerTotalDurationMs, timerEndAtWall, running)
            val content = if (running) {
                if (infoLine.isNotBlank()) "남은 시간 ${formatHMS(remainingMs)} • $infoLine" else "남은 시간 ${formatHMS(remainingMs)}"
            } else {
                if (infoLine.isNotBlank()) "일시정지 • ${formatHMS(remainingMs)} • $infoLine" else "일시정지 • ${formatHMS(remainingMs)}"
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
                .setContentIntent(mainPendingIntent(MainActivity.ACTION_OPEN_TIMER))
                .setAutoCancel(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            if (running) {
                b.setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    // 남은 시간이 음수가 되면 Chronometer가 "-00:12"처럼 계속 내려갈 수 있어 clamp 처리
                    .setWhen(System.currentTimeMillis() + remainingMs.coerceAtLeast(0L))
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
        val infoLine = buildTimerInfoLine(timerTotalDurationMs, timerEndAtWall, running)
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

        val infoLineForText = buildTimerInfoLine(timerTotalDurationMs, timerEndAtWall, running)

        val content = if (running) {
            if (infoLineForText.isNotBlank()) "남은 시간 ${formatHMS(remainingMs)} • $infoLineForText" else "남은 시간 ${formatHMS(remainingMs)}"
        } else {
            if (infoLineForText.isNotBlank()) "일시정지 • ${formatHMS(remainingMs)} • $infoLineForText" else "일시정지 • ${formatHMS(remainingMs)}"
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
            .setContentIntent(mainPendingIntent(MainActivity.ACTION_OPEN_TIMER))
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
            .setContentIntent(mainPendingIntent(MainActivity.ACTION_OPEN_STOPWATCH))
            .build()
    }

    private fun buildStopwatch2Notification(elapsedMs: Long): Notification {
        val content = "경과 시간 ${formatHMS(elapsedMs)}"
        return NotificationCompat.Builder(this, STOPWATCH_CHANNEL)
            .setContentTitle("스톱워치 2")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - elapsedMs)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent(MainActivity.ACTION_OPEN_STOPWATCH))
            .build()
    }

    private fun buildExtraNotification(id: String, label: String, remainingMs: Long): Notification {
        val state = extraTimers[id]
        // ✅ '종료 2월 26일 오후 4:00' 같은 표기는 wall-clock 기준이어야 한다.
        // endElapsed(부팅 이후 ms)를 그대로 포맷하면 과거 날짜로 잘못 보인다.
        val endAt = state?.endAtWall ?: (System.currentTimeMillis() + remainingMs.coerceAtLeast(0L))
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
            .setContentIntent(mainPendingIntent(MainActivity.ACTION_OPEN_TIMER))
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
            .putLong(KEY_LAST_SESSION_ID, timerSessionId)
            .commit() // 🔹 apply() 대신 commit()으로 즉시 저장

        // 명시적 브로드캐스트 전송
        val intent = Intent(ACTION_TIMER_STATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_REMAIN_MS, remain)
            putExtra(EXTRA_SESSION_ID, timerSessionId)
            if (state == STATE_RUNNING) {
                // ELAPSED_REALTIME 기반 종료시각을 같이 보내서 UI에서 프레임 단위로 부드럽게 표시 가능
                putExtra(EXTRA_END_ELAPSED, timerEndElapsed)
            }
            setPackage(packageName)
            // 🔹 플래그 추가로 안정성 향상
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        sendBroadcast(intent)

        if (force) lastPublishMs = SystemClock.elapsedRealtime()

        AppLog.d(applicationContext, "ClockService", "broadcast sent state=$state remain=$remain session=$timerSessionId")
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
        val open = Intent(this, MainActivity::class.java).apply {
            if (action != null) this.action = action

            // 어떤 화면(스톱워치/타이머)로 들어갈지 명확히 전달
            putExtra("open_tab_action", action ?: "")

            // 알림을 눌러 진입했음을 전달 (알림 갱신 잠시 중단)
            putExtra("from_notification", true)

            // 기존 Activity가 있으면 재사용(singleTop) + 최상단으로 올림(clearTop)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // ⚠️ PendingIntent는 (requestCode + Intent)이 같으면 서로 덮어쓴다.
        // 알림 종류(타이머/스톱워치/기본)에 따라 requestCode를 분리해서
        // "타이머 알림을 눌렀는데 스톱워치로 열리는" 문제를 방지한다.
        val requestCode = when (action) {
            MainActivity.ACTION_OPEN_TIMER -> 2001
            MainActivity.ACTION_OPEN_STOPWATCH -> 2002
            else -> 2000
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, requestCode, open, flags)
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
        if (SystemClock.elapsedRealtime() < suspendNotifyUntilElapsed) return
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun cancelSafe(id: Int) {
        runCatching { NotificationManagerCompat.from(this).cancel(id) }
    }

    companion object {
        private const val ACTION_START_TIMER = "com.krdonon.timer.action.START_TIMER"
        private const val ACTION_MAIN_TIMER_ALARM_FIRED = "com.krdonon.timer.action.MAIN_TIMER_ALARM_FIRED"
        private const val ACTION_STOP_TIMER = "com.krdonon.timer.action.STOP_TIMER"
        private const val ACTION_PAUSE_TIMER = "com.krdonon.timer.action.PAUSE_TIMER"
        private const val ACTION_RESUME_TIMER = "com.krdonon.timer.action.RESUME_TIMER"
        private const val ACTION_START_STOPWATCH = "com.krdonon.timer.action.START_STOPWATCH"
        private const val ACTION_STOP_STOPWATCH = "com.krdonon.timer.action.STOP_STOPWATCH"
        private const val ACTION_START_STOPWATCH2 = "com.krdonon.timer.action.START_STOPWATCH2"
        private const val ACTION_PAUSE_STOPWATCH2 = "com.krdonon.timer.action.PAUSE_STOPWATCH2"
        private const val ACTION_RESET_STOPWATCH2 = "com.krdonon.timer.action.RESET_STOPWATCH2"
        private const val ACTION_START_EXTRA = "com.krdonon.timer.action.START_EXTRA"
        private const val ACTION_STOP_EXTRA = "com.krdonon.timer.action.STOP_EXTRA"
        private const val ACTION_STOP_ALL = "com.krdonon.timer.action.STOP_ALL"
        private const val ACTION_KEEP_ALIVE = "com.krdonon.timer.action.KEEP_ALIVE"
        const val ACTION_SUSPEND_NOTIFY_UPDATES = "com.krdonon.timer.action.SUSPEND_NOTIFY_UPDATES"

        // AlarmReceiver -> ClockService 종료 통지용
        const val EXTRA_TIMER_END_AT_WALL = "timer_end_at_wall"

        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_TARGET_AT_WALL_MS = "target_at_wall_ms"
        private const val EXTRA_STOPWATCH_BASE = "stopwatch_base"
        private const val EXTRA_STOPWATCH2_BASE = "stopwatch2_base"
        private const val EXTRA_STOPWATCH2_ELAPSED = "stopwatch2_elapsed"
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SUSPEND_MS = "suspend_ms"

        // ✅ Foreground 알림 ID를 리더별로 분리한다.
        // (실기기에서 startForeground 업데이트가 드롭되면 이전 RemoteViews(타이머)가 남아
        // Chronometer가 음수로 내려가는 현상을 근본적으로 차단)
        const val FOREGROUND_ID_TIMER = 42
        const val FOREGROUND_ID_EXTRA = 43
        const val FOREGROUND_ID_STOPWATCH = 44
        const val FOREGROUND_ID_STOPWATCH2 = 45
        private const val NID_TIMER = 1001
        private const val NID_STOPWATCH = 1002
        private const val NID_STOPWATCH2 = 1003
        private const val EXTRA_BASE_NID = 2000
        const val EXTRA_SUMMARY_ID = 2999

        private const val TIMER_CHANNEL = "timer_channel_v2"
        private const val STOPWATCH_CHANNEL = "stopwatch_channel"
        private const val EXTRA_CHANNEL = "extra_timer_channel"

        private const val EXTRA_GROUP = "extra_timer_group"

        internal const val PERSIST_PREFS_STATIC = "clock_persist_prefs"
        internal const val KEY_EXTRA_TIMERS_JSON_STATIC = "extra_timers_json"

        fun extraNotifyIdStatic(id: String): Int =
            EXTRA_BASE_NID + (id.hashCode() and 0x7fffffff) % 100

        fun startTimer(context: Context, durationMs: Long) {
            val i = Intent(context, ClockService::class.java).apply {
                action = ACTION_START_TIMER
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun startTimerAt(context: Context, targetAtWallMs: Long) {
            val remain = (targetAtWallMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val i = Intent(context, ClockService::class.java).apply {
                action = ACTION_START_TIMER
                putExtra(EXTRA_TARGET_AT_WALL_MS, targetAtWallMs)
                putExtra(EXTRA_DURATION_MS, remain)
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

        fun startStopwatch2(context: Context, baseElapsed: Long = 0L) {
            val i = Intent(context, ClockService::class.java).apply {
                action = ACTION_START_STOPWATCH2
                putExtra(EXTRA_STOPWATCH2_BASE, baseElapsed)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun pauseStopwatch2(context: Context, elapsedMs: Long) {
            val i = Intent(context, ClockService::class.java).apply {
                action = ACTION_PAUSE_STOPWATCH2
                putExtra(EXTRA_STOPWATCH2_ELAPSED, elapsedMs)
            }
            context.startService(i)
        }

        fun resetStopwatch2(context: Context) {
            val i = Intent(context, ClockService::class.java).apply { action = ACTION_RESET_STOPWATCH2 }
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

        /**
         * AlarmReceiver(정확 알람)에서 호출: 메인 타이머가 울리는 순간, 알림/상태를 확실히 정리.
         * endAtWallMs: 알람에 담긴 종료 시각(현재 타이머와 매칭 검증용)
         */
        fun onMainTimerAlarmFired(context: Context, endAtWallMs: Long) {
            val i = Intent(context, ClockService::class.java).apply {
                action = ACTION_MAIN_TIMER_ALARM_FIRED
                putExtra(EXTRA_TIMER_END_AT_WALL, endAtWallMs)
            }
            // 중요: 이 액션은 "정리/종료" 전용이다.
            // startForegroundService()로 올리면 서비스가 새로 생성된 경우 5초 안에
            // startForeground()를 호출해야 하는데, 종료 처리 경로에서는 그 보장이 없어
            // ForegroundServiceDidNotStartInTimeException 이 발생할 수 있다.
            // 따라서 일반 startService()로 전달하고, 실패 시에도 앱이 죽지 않도록 감싼다.
            runCatching { context.startService(i) }
        }
    }

    // ===== Extra timer finish alarm =====
    private fun extraAlarmRequestCode(id: String): Int =
        EXTRA_ALARM_REQUEST_BASE + ((id.hashCode() and 0x7fffffff) % 10000)

    private fun scheduleExtraFinishAlarm(id: String, label: String, endAtElapsed: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, com.krdonon.timer.ExtraTimerAlarmReceiver::class.java).apply {
            action = ACTION_EXTRA_ALARM
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_LABEL, label)
        }
        val pi = PendingIntent.getBroadcast(
            this,
            extraAlarmRequestCode(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // AlarmManager는 여기서 RTC로 스케줄링하고 있으므로, ELAPSED 기반 endAt을
        // 벽시계 시간으로 변환해서 등록한다.
        val nowElapsed = SystemClock.elapsedRealtime()
        val remain = (endAtElapsed - nowElapsed).coerceAtLeast(0L)
        val endAtWall = System.currentTimeMillis() + remain

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtWall, pi)
                } else {
                    // exact 권한이 없으면 best-effort
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtWall, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtWall, pi)
            }
        } catch (_: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, endAtWall, pi)
        }
    }

    private fun cancelExtraFinishAlarm(id: String) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, com.krdonon.timer.ExtraTimerAlarmReceiver::class.java).apply {
            action = ACTION_EXTRA_ALARM
            putExtra(EXTRA_ID, id)
        }
        val pi = PendingIntent.getBroadcast(
            this,
            extraAlarmRequestCode(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { am.cancel(pi) }
        runCatching { pi.cancel() }
    }

}