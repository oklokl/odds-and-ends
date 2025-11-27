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
import androidx.core.app.NotificationCompat
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.text.SimpleDateFormat
import java.util.*

class MetronomeService : Service() {

    private val binder = LocalBinder()
    private var soundManager: SoundManager? = null
    private var handler: Handler? = null
    private var metronomeRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var state = MetronomeState()
    private var lastNotificationUpdate = 0L

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
        acquireWakeLock()
        initVibrator()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                togglePlayPause()
            }

            ACTION_STOP -> {
                stopMetronome()
                stopForeground(true)
                stopSelf()
                sendBroadcast(Intent(ACTION_METRONOME_STOPPED))
            }
        }
        return START_NOT_STICKY
    }

    // -----------------------------
    // Wakelock 관리
    // -----------------------------
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Metronome::MetronomeWakeLock"
        )
        // 필요 시 시간 조정 가능
        wakeLock?.acquire(10 * 60 * 1000L)
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

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    // -----------------------------
    // 상태 업데이트 (ViewModel -> Service)
    // -----------------------------
    fun updateState(newState: MetronomeState) {
        state = newState

        if (state.isPlaying) {
            startMetronome()
        } else {
            stopMetronome()
        }
    }

    fun getState(): MetronomeState = state

    // -----------------------------
    // 메트로놈 재생/정지
    // -----------------------------
    fun startMetronome() {
        clearMetronomeRunnable()

        if (!state.isPlaying) {
            state = state.copy(
                isPlaying = true,
                currentBeat = 0,
                subBeatIndex = 0
            )
        }

        startForeground(NOTIFICATION_ID, createNotification())

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

    private fun stopMetronome() {
        clearMetronomeRunnable()

        if (state.isPlaying) {
            state = state.copy(
                isPlaying = false,
                currentBeat = 0,
                subBeatIndex = 0
            )
        }
    }

    private fun togglePlayPause() {
        state = state.copy(isPlaying = !state.isPlaying)
        if (state.isPlaying) {
            startMetronome()
        } else {
            stopMetronome()
            updateNotification()
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
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
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
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            "실행 중 • ${timeFormat.format(Date())}"
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
        stopMetronome()
        soundManager?.release()
        flashManager?.release()
        releaseWakeLock()
        handler = null
        super.onDestroy()
    }
}
