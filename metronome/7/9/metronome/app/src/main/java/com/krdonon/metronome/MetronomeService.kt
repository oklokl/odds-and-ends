package com.krdonon.metronome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

class MetronomeService : Service() {

    private val binder = LocalBinder()
    private var soundManager: SoundManager? = null
    private var handler: Handler? = null
    private var metronomeRunnable: Runnable? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false

    private var state = MetronomeState()
    private var lastNotificationUpdate = 0L

    // 메트로놈이 재생을 시작한 시각 (elapsedRealtime 기준)
    private var metronomeStartElapsedMs: Long = 0L

    private var flashManager: FlashManager? = null

    // 진동
    private var vibrator: Vibrator? = null

    // 드리프트 보정용: 다음 틱의 이상적인 시각
    private var nextTickTimeMs: Long = 0L

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "metronome_channel"

        const val ACTION_PLAY_PAUSE = "com.krdonon.kmetronome.PLAY_PAUSE"
        const val ACTION_STOP = "com.krdonon.kmetronome.STOP"

        const val ACTION_METRONOME_STOPPED = "com.krdonon.metronome.METRONOME_STOPPED"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MetronomeService = this@MetronomeService
    }

    override fun onCreate() {
        super.onCreate()

        handler = Handler(Looper.getMainLooper())
        soundManager = SoundManager(this).also {
            // 사운드 로딩 후 첫 박 씹힘 방지용 웜업
            it.warmUp()
        }
        flashManager = FlashManager(this)

        createNotificationChannel()
        initVibrator()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                togglePlayPause()
            }

            ACTION_STOP -> {
                // "중지"는 즉시 재생을 종료하고, 알림도 제거, 서비스도 종료
                stopMetronome(removeNotification = true)
                stopSelf()
                sendBroadcast(Intent(ACTION_METRONOME_STOPPED))
            }
        }
        return START_NOT_STICKY
    }

    // -----------------------------
    // Wakelock 관리
    // -----------------------------
    private fun ensureWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Metronome::MetronomeWakeLock"
            ).apply {
                // 참조 카운팅을 끄면(권장) 중복 acquire/release로 인한 불일치 위험을 줄일 수 있습니다.
                setReferenceCounted(false)
            }
        }

        // 재생 중에만 유지 (필요 시 시간 조정 가능)
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    // -----------------------------
    // 진동 초기화 및 사용
    // -----------------------------
    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrate(isStrong: Boolean) {
        val duration = if (isStrong) 60L else 25L
        val amplitude = if (isStrong) 255 else 120

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(duration, amplitude)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(duration)
        }
    }

    // -----------------------------
    // 알림 채널
    // -----------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "메트로놈",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "메트로놈 실행 상태"
                setSound(null, null)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // -----------------------------
    // 상태 업데이트 (ViewModel -> Service)
    // -----------------------------
    fun updateState(newState: MetronomeState) {
        val wasPlaying = state.isPlaying
        state = newState

        if (state.isPlaying) {
            startMetronome()
        } else {
            // ViewModel에서 "일시정지"로 전환될 때도 알림바 제거
            stopMetronome(removeNotification = true)

            // NOTE: 앱(UI)이 서비스에 바인딩되어 있기 때문에 여기서 stopSelf()까지 하면
            // 바인딩이 끊길 수 있습니다. "중지" 액션(ACTION_STOP)에서만 stopSelf()를 수행합니다.
        }

        // (선택) 재생→정지 전환 시 상태를 즉시 반영하고 싶은 경우
        if (wasPlaying && !state.isPlaying) {
            // 이미 stopMetronome에서 처리
        }
    }

    fun getState(): MetronomeState = state

    // -----------------------------
    // 메트로놈 재생/정지
    // -----------------------------
    fun startMetronome() {
        clearMetronomeRunnable()

        ensureWakeLock()

        // 재생 시작 시 기준 시각 저장 (알림에서 경과 시간 계산용)
        metronomeStartElapsedMs = SystemClock.elapsedRealtime()
        lastNotificationUpdate = 0L   // 바로 0:00이 보이도록 초기화

        // UI에서 state.isPlaying=true로 넘어오는 경우도 있으므로, 박/서브박은 항상 초기화
        state = state.copy(
            isPlaying = true,
            currentBeat = 0,
            subBeatIndex = 0
        )

        startForeground(NOTIFICATION_ID, createNotification())
        isForeground = true

        // 4분음표 기준 BPM → ms
        val quarterNoteMs = 60000.0 / state.bpm

        // 단위(분모)에 따라 박 길이 조정
        val unit = state.beatUnit.coerceIn(1, 16)
        val intervalMs = (quarterNoteMs * (4.0 / unit)).toLong().coerceAtLeast(1L)

        val h = handler ?: return

        // 드리프트를 줄이기 위해 "이상적인 다음 시각"을 기준으로 딜레이 계산
        nextTickTimeMs = SystemClock.elapsedRealtime()

        metronomeRunnable = object : Runnable {
            override fun run() {
                if (!state.isPlaying) return

                playBeat()
                updateNotificationIfNeeded()

                // 다음 이상적인 틱 시각
                nextTickTimeMs += intervalMs

                val now = SystemClock.elapsedRealtime()
                var delay = nextTickTimeMs - now
                if (delay < 0L) {
                    // 이미 늦었으면 바로 다음 틱 실행
                    delay = 0L
                }

                h.postDelayed(this, delay)
            }
        }

        h.post(metronomeRunnable!!)
    }

    /**
     * 재생 종료(일시정지/중지 공통)
     * - Runnable 제거
     * - 상태/타이머 초기화
     * - (옵션) Foreground 해제 + 알림 제거
     */
    private fun stopMetronome(removeNotification: Boolean) {
        clearMetronomeRunnable()

        state = state.copy(
            isPlaying = false,
            currentBeat = 0,
            subBeatIndex = 0
        )

        // 정지 시 타이머 초기화
        metronomeStartElapsedMs = 0L
        lastNotificationUpdate = 0L

        releaseWakeLock()

        if (removeNotification) {
            exitForegroundAndRemoveNotification()
        }
    }

    private fun togglePlayPause() {
        if (state.isPlaying) {
            // "일시정지"도 사용자 요구사항대로 알림바를 제거
            stopMetronome(removeNotification = true)
        } else {
            startMetronome()
        }
    }

    /**
     * 한 박마다 호출되는 핵심 로직
     * - 소리 / 진동 모드 처리
     * - 강박 + 설정 ON 이면 항상 플래시 실행 (소리/진동 구분 없음)
     */
    private fun playBeat() {
        val isStrongBeat = (state.currentBeat == 0)

        // 1) 모드별 동작
        if (state.isVibrationMode) {
            // 진동 모드: 소리 없이 진동만
            vibrate(isStrongBeat)
        } else {
            // 소리 모드
            if (isStrongBeat) {
                soundManager?.playStrongBeat()
            } else {
                soundManager?.playWeakBeat()
            }
        }

        // 2) 플래시 동작 (소리/진동과 상관없이 강박 + 설정 ON 이면 실행)
        if (isStrongBeat && isFlashOnStrongBeatEnabled()) {
            flashManager?.pulse()
        }

        // 3) 다음 박 계산
        val nextBeat = (state.currentBeat + 1) % state.beatsPerMeasure

        state = state.copy(
            currentBeat = nextBeat,
            subBeatIndex = 0
        )
    }

    // -----------------------------
    // 알림 내용 갱신
    // -----------------------------
    private fun updateNotificationIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate >= 60000) { // 1분마다 업데이트
            lastNotificationUpdate = now
            updateNotification()
        }
    }

    private fun updateNotification() {
        // Foreground를 종료했는데 notify()를 호출하면 알림이 다시 살아날 수 있으므로 방어
        if (!isForeground) return

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun cancelNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun exitForegroundAndRemoveNotification() {
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForeground = false
        } else {
            // 이미 Foreground가 아니더라도 혹시 남아있는 알림을 제거
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }

        cancelNotification()
    }

    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = Intent(this, MetronomeService::class.java).apply {
            action = ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MetronomeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = if (state.isPlaying) {
            val base = if (metronomeStartElapsedMs != 0L) {
                SystemClock.elapsedRealtime() - metronomeStartElapsedMs
            } else {
                0L
            }

            val totalSeconds = base / 1000
            val totalMinutes = totalSeconds / 60

            val hours: Long = totalMinutes / 60
            val minutes: Int = (totalMinutes % 60).toInt()

            // 시간은 자리수 제한 없이 출력 (0, 1, … 124, 1000, …)
            val timeText = String.format("%d:%02d", hours, minutes)
            "실행 중 • $timeText"
        } else {
            "일시정지"
        }

        val contentText = "${state.beatsPerMeasure}/${state.beatUnit} • ${state.bpm} BPM"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("메트로놈")
            .setContentText("$statusText\n$contentText")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$statusText\n$contentText")
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainPendingIntent)
            .addAction(
                if (state.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (state.isPlaying) "일시정지" else "재생",
                playPausePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "중지",
                stopPendingIntent
            )
            // 재생 중에만 ongoing
            .setOngoing(state.isPlaying)
            .setSilent(true)
            .build()
    }

    // -----------------------------
    // Runnable 정리
    // -----------------------------
    private fun clearMetronomeRunnable() {
        metronomeRunnable?.let {
            handler?.removeCallbacks(it)
        }
        metronomeRunnable = null
    }

    private fun isFlashOnStrongBeatEnabled(): Boolean {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.KEY_FLASH_STRONG_BEAT, false)
    }

    // -----------------------------
    // 사운드셋 변경
    // -----------------------------
    fun nextSoundSet() {
        soundManager?.nextSoundSet()
    }

    fun getCurrentSoundSet(): String {
        return soundManager?.getCurrentSetName() ?: "None"
    }

    override fun onDestroy() {
        // 안전하게 정리
        stopMetronome(removeNotification = true)
        soundManager?.release()
        flashManager?.release()
        handler = null
        super.onDestroy()
    }
}
