package com.krdonon.metronome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.locks.LockSupport
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle

@Suppress("DEPRECATION")
class MetronomeService : Service() {

    private val binder = LocalBinder()
    private var soundManager: SoundManager? = null

    // 메인 스레드용 핸들러 (UI, 알림, 비동기 플래시 등)
    private var mainHandler: Handler? = null

    // 고정밀 전용 실시간 오디오 스레드 및 핸들러 (UI 버벅임과 완전 격리)
    private var audioThread: HandlerThread? = null
    private var audioHandler: Handler? = null
    private var scheduledTickRunnable: Runnable? = null
    private var audioRebuildRunnable: Runnable? = null

    // 템포 변경/정지 시 이미 대기 중인 틱을 무효화하기 위한 세대 번호
    @Volatile
    private var scheduleGeneration: Long = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false

    @Volatile
    private var state = MetronomeState()

    // ViewModel 등으로 상태 변경을 즉시 알리기 위한 리스너
    var onStateChangedListener: ((MetronomeState) -> Unit)? = null

    // 메트로놈이 재생을 시작한 시각 (elapsedRealtime 기준)
    private var metronomeStartElapsedMs: Long = 0L

    private var flashManager: FlashManager? = null

    // 플래시 설정 캐시 (매 틱마다 I/O 방지하여 지연 제거)
    @Volatile
    private var isFlashEnabledCache: Boolean = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // 진동
    private var vibrator: Vibrator? = null

    // 미디어 세션 (시스템 알림바 미디어 플레이어 및 파형 애니메이션 연동)
    private var mediaSession: MediaSessionCompat? = null

    // 다음 틱의 목표 시각. 실제 스케줄링은 ns 단위 목표 + 짧은 정밀 대기로 처리합니다.
    private var idealNextTickNanos: Long = 0L

    private var timeUpdateRunnable: Runnable? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "metronome_playback_channel"

        // Handler는 ms 단위이므로 목표 시각보다 조금 일찍 깨운 뒤 짧게 정밀 대기합니다.
        private const val EARLY_WAKE_NANOS = 2_000_000L       // 2 ms
        private const val SPIN_WINDOW_NANOS = 250_000L       // 0.25 ms

        // 늦어진 틱을 다음 박에서 무리하게 따라잡으면 '느림 -> 갑자기 빠름'으로 들립니다.
        // 2 ms를 넘게 늦으면 현재 시각을 새 기준점으로 잡아 다음 간격을 온전히 보장합니다.
        private const val MAX_PHASE_CATCH_UP_NANOS = 2_000_000L

        const val ACTION_PLAY_PAUSE = AppConstants.ACTION_PLAY_PAUSE
        const val ACTION_STOP = AppConstants.ACTION_STOP
        const val ACTION_METRONOME_STOPPED = AppConstants.ACTION_METRONOME_STOPPED
    }

    inner class LocalBinder : Binder() {
        fun getService(): MetronomeService = this@MetronomeService
    }

    override fun onCreate() {
        super.onCreate()

        mainHandler = Handler(Looper.getMainLooper())

        // 1. 최고 우선순위 오디오 전용 스레드 가동 (THREAD_PRIORITY_URGENT_AUDIO)
        audioThread = HandlerThread("MetronomeAudioEngine", Process.THREAD_PRIORITY_URGENT_AUDIO).apply {
            start()
        }
        audioHandler = Handler(audioThread!!.looper)

        soundManager = SoundManager(this).also {
            it.preloadAsync(initialSetName = "set0")
        }

        // 플래시 설정값 캐시 및 변경 리스너 등록 (매 박 디스크 읽기 제거)
        initFlashSettingsCache()

        state = state.copy(
            soundSetIndex = soundManager?.getCurrentSetIndex() ?: 0,
            soundSetName = soundManager?.getCurrentSetName() ?: "set0"
        )
        flashManager = FlashManager(this)

        initMediaSession()
        createNotificationChannel()
        initVibrator()
    }

    private fun initFlashSettingsCache() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        isFlashEnabledCache = prefs.getBoolean(Prefs.KEY_FLASH_STRONG_BEAT, false)

        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == Prefs.KEY_FLASH_STRONG_BEAT) {
                isFlashEnabledCache = sp.getBoolean(Prefs.KEY_FLASH_STRONG_BEAT, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                togglePlayPause()
                if (!state.isPlaying) {
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                stopMetronome(removeNotification = true)
                stopSelf()
                sendBroadcast(Intent(ACTION_METRONOME_STOPPED))
            }
        }
        return START_NOT_STICKY
    }

    // -----------------------------
    // MediaSession 초기화
    // -----------------------------
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MetronomeMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (!state.isPlaying) {
                        startMetronome()
                    }
                }

                override fun onPause() {
                    if (state.isPlaying) {
                        stopMetronome(removeNotification = true)
                        stopSelf()
                    }
                }

                override fun onStop() {
                    stopMetronome(removeNotification = true)
                    stopSelf()
                    sendBroadcast(Intent(ACTION_METRONOME_STOPPED))
                }
            })

            isActive = true
        }
        updatePlaybackState()
        updateMediaMetadata()
    }

    /**
     * 알림바 미디어 플레이어의 파형(Squiggly waveform) 및 상태 애니메이션을 구동합니다.
     */
    private fun updatePlaybackState() {
        val session = mediaSession ?: return
        val isPlaying = state.isPlaying
        val position = if (isPlaying && metronomeStartElapsedMs != 0L) {
            SystemClock.elapsedRealtime() - metronomeStartElapsedMs
        } else {
            0L
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                position,
                if (isPlaying) 1.0f else 0.0f,
                SystemClock.elapsedRealtime()
            )
            .build()

        session.setPlaybackState(playbackState)
    }

    private fun getElapsedTimeText(): String {
        val elapsedMs = if (metronomeStartElapsedMs != 0L) {
            SystemClock.elapsedRealtime() - metronomeStartElapsedMs
        } else {
            0L
        }
        val totalSeconds = elapsedMs / 1000
        val totalMinutes = totalSeconds / 60
        val hours = totalMinutes / 60
        val minutes = (totalMinutes % 60).toInt()
        return java.lang.String.format(java.util.Locale.getDefault(), "%d:%02d", hours, minutes)
    }

    private fun updateMediaMetadata() {
        val session = mediaSession ?: return
        val title = getString(R.string.app_name)
        val timeText = if (state.isPlaying) getElapsedTimeText() else ""
        val timeSuffix = if (timeText.isNotEmpty()) " • 실행 중 $timeText" else ""
        val tempoInfo = "${state.beatsPerMeasure}/${state.beatUnit} • ${state.bpm} BPM$timeSuffix"
        val soundInfo = "Sound: ${state.soundSetName}"

        val durationMs = 24L * 60 * 60 * 1000L

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, tempoInfo)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, soundInfo)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            .build()

        session.setMetadata(metadata)
    }

    private fun startTimeUpdateTimer() {
        stopTimeUpdateTimer()
        timeUpdateRunnable = object : Runnable {
            override fun run() {
                if (state.isPlaying && isForeground) {
                    updateMediaMetadata()
                    updatePlaybackState()
                    updateNotification()
                    mainHandler?.postDelayed(this, 60000L)
                }
            }
        }
        mainHandler?.postDelayed(timeUpdateRunnable!!, 60000L)
    }

    private fun stopTimeUpdateTimer() {
        timeUpdateRunnable?.let { mainHandler?.removeCallbacks(it) }
        timeUpdateRunnable = null
    }

    // -----------------------------
    // Wakelock 관리
    // -----------------------------
    private fun ensureWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Metronome::MetronomeWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }

        try {
            if (wakeLock?.isHeld != true) {
                // 재생 중에는 서비스가 직접 해제할 때까지 유지합니다.
                // 기존 10분 타임아웃은 장시간 재생에서 타이밍 스레드가 절전 영향을 받을 수 있었습니다.
                wakeLock?.acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (_: Exception) {
        }
    }

    // -----------------------------
    // 진동 초기화 및 사용
    // -----------------------------
    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrate(isStrong: Boolean) {
        val duration = if (isStrong) 60L else 25L
        val amplitude = if (isStrong) 255 else 120

        try {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(duration, amplitude)
            )
        } catch (_: Exception) {
        }
    }

    // -----------------------------
    // 알림 채널
    // -----------------------------
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    // -----------------------------
    // 상태 업데이트 (ViewModel -> Service)
    // -----------------------------
    fun updateState(newState: MetronomeState) {
        val prev = state
        val wasPlaying = prev.isPlaying
        val willPlay = newState.isPlaying

        state = newState

        // 1) 재생/정지 전환 처리
        if (wasPlaying != willPlay) {
            if (willPlay) {
                startMetronome()
            } else {
                stopMetronome(removeNotification = true)
                stopSelf()
            }
            return
        }

        // 2) 재생 중 파라미터 변경 실시간 반영
        if (willPlay) {
            val timingOrPatternChanged =
                prev.bpm != newState.bpm ||
                        prev.beatUnit != newState.beatUnit ||
                        prev.beatsPerMeasure != newState.beatsPerMeasure
            val vibrationModeChanged = prev.isVibrationMode != newState.isVibrationMode

            if (timingOrPatternChanged || vibrationModeChanged) {
                // AudioTrack 루프도 같은 기준점에서 다시 시작하므로 화면 박자도 1박으로 재정렬합니다.
                state = state.copy(currentBeat = 0, subBeatIndex = 0)
                requestAudioLoopRebuild()
                rescheduleNextTickForNewTempo()
            }

            // BPM, 박자, 사운드셋 변경 시 알림 및 미디어 세션 메타데이터 갱신
            if (timingOrPatternChanged ||
                prev.soundSetName != newState.soundSetName
            ) {
                updateMediaMetadata()
                updateNotification()
            }
        }
    }

    fun getState(): MetronomeState = state

    // -----------------------------
    // 메트로놈 고정밀 재생/정지
    // -----------------------------
    /**
     * 한 박 간격을 ns 단위 Long으로 계산합니다.
     * 반올림 오차는 1ns 미만이라 장시간 재생에서도 실질적인 누적 오차가 없습니다.
     */
    private fun computeIntervalNanos(s: MetronomeState): Long {
        val quarterNoteNanos = 60_000_000_000.0 / s.bpm.coerceAtLeast(1)
        val unit = s.beatUnit.coerceIn(1, 16)
        return (quarterNoteNanos * (4.0 / unit)).toLong().coerceAtLeast(1L)
    }

    /** 목표 시각 직전까지 park하고 마지막 짧은 구간만 스핀해 Handler의 ms 오차를 줄입니다. */
    private fun waitUntilNanos(targetNanos: Long, generation: Long): Boolean {
        while (state.isPlaying && generation == scheduleGeneration) {
            val remaining = targetNanos - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L) return true

            if (remaining > SPIN_WINDOW_NANOS) {
                LockSupport.parkNanos(remaining - SPIN_WINDOW_NANOS)
            } else {
                // 마지막 0.25ms 이내는 이 루프 자체로 짧게 스핀합니다.
                // Thread.yield()는 다시 스케줄될 때 수 ms 늦어질 수 있어 사용하지 않습니다.
            }
        }
        return false
    }

    /**
     * 한 번의 틱을 목표 절대 시각에 예약합니다.
     * 지연이 생겼을 때 다음 박을 과도하게 앞당겨 따라잡지 않는 것이 핵심입니다.
     */
    private fun scheduleTick(targetNanos: Long, generation: Long) {
        val ah = audioHandler ?: return
        if (!state.isPlaying || generation != scheduleGeneration) return

        val now = SystemClock.elapsedRealtimeNanos()
        val untilTarget = targetNanos - now
        val handlerDelayNanos = (untilTarget - EARLY_WAKE_NANOS).coerceAtLeast(0L)
        val handlerDelayMs = handlerDelayNanos / 1_000_000L

        val runnable = Runnable {
            if (!state.isPlaying || generation != scheduleGeneration) return@Runnable
            if (!waitUntilNanos(targetNanos, generation)) return@Runnable
            if (!state.isPlaying || generation != scheduleGeneration) return@Runnable

            val actualTickNanos = SystemClock.elapsedRealtimeNanos()
            val lateByNanos = (actualTickNanos - targetNanos).coerceAtLeast(0L)

            // 이 틱은 화면 박자/플래시/진동만 갱신합니다. 실제 소리는 AudioTrack 루프가 담당합니다.
            playBeat()

            if (!state.isPlaying || generation != scheduleGeneration) return@Runnable

            val intervalNanos = computeIntervalNanos(state)
            idealNextTickNanos = if (lateByNanos > MAX_PHASE_CATCH_UP_NANOS) {
                // 큰 지연 뒤에 다음 간격을 짧게 만들어 '따라잡기' 하지 않습니다.
                // 귀에 들리는 박 간격의 안정성을 절대 위상보다 우선합니다.
                actualTickNanos + intervalNanos
            } else {
                targetNanos + intervalNanos
            }

            scheduleTick(idealNextTickNanos, generation)
        }

        scheduledTickRunnable = runnable
        ah.postDelayed(runnable, handlerDelayMs)
    }

    /**
     * 실제 소리는 매 박 SoundPool 호출이 아니라 SoundManager의 연속 PCM 루프가 담당합니다.
     * BPM 슬라이더처럼 값이 연속해서 바뀌는 경우 이전 재구성 요청을 제거해 불필요한
     * AudioTrack 생성/해제를 누적하지 않습니다.
     */
    private fun requestAudioLoopRebuild() {
        val ah = audioHandler ?: return

        audioRebuildRunnable?.let(ah::removeCallbacks)
        val snapshot = state
        val runnable = Runnable {
            if (!state.isPlaying) return@Runnable

            if (snapshot.isVibrationMode) {
                soundManager?.stopLoop()
            } else {
                soundManager?.startLoop(
                    bpm = snapshot.bpm,
                    beatUnit = snapshot.beatUnit,
                    beatsPerMeasure = snapshot.beatsPerMeasure
                )
            }
        }
        audioRebuildRunnable = runnable
        ah.post(runnable)
    }

    private fun rescheduleNextTickForNewTempo() {
        if (!state.isPlaying) return

        val ah = audioHandler ?: return
        scheduledTickRunnable?.let(ah::removeCallbacks)

        val generation = ++scheduleGeneration
        // AudioTrack 루프는 재구성 시 1박(강박)부터 즉시 시작합니다.
        // 화면/진동도 같은 기준점에서 즉시 1박을 처리해 서로 한 박 어긋나지 않게 합니다.
        idealNextTickNanos = SystemClock.elapsedRealtimeNanos()
        scheduleTick(idealNextTickNanos, generation)
    }

    fun startMetronome() {
        clearMetronomeRunnable()
        ensureWakeLock()

        metronomeStartElapsedMs = SystemClock.elapsedRealtime()

        state = state.copy(
            isPlaying = true,
            currentBeat = 0,
            subBeatIndex = 0
        )
        onStateChangedListener?.invoke(state)

        updateMediaMetadata()
        updatePlaybackState()
        startTimeUpdateTimer()

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true

        // 실제 오디오는 한 마디 전체를 PCM으로 합성해 AudioTrack에서 샘플 단위로 반복합니다.
        // Java 타이머는 화면/플래시/진동 표시만 담당하므로 타이머 지터가 소리에 전달되지 않습니다.
        if (!state.isVibrationMode) {
            soundManager?.startLoop(
                bpm = state.bpm,
                beatUnit = state.beatUnit,
                beatsPerMeasure = state.beatsPerMeasure
            )
        } else {
            soundManager?.stopLoop()
        }

        val generation = ++scheduleGeneration
        idealNextTickNanos = SystemClock.elapsedRealtimeNanos()
        scheduleTick(idealNextTickNanos, generation)
    }

    private fun stopMetronome(removeNotification: Boolean) {
        clearMetronomeRunnable()
        stopTimeUpdateTimer()
        soundManager?.stopLoop()

        state = state.copy(
            isPlaying = false,
            currentBeat = 0,
            subBeatIndex = 0
        )
        onStateChangedListener?.invoke(state)

        metronomeStartElapsedMs = 0L
        updatePlaybackState()

        releaseWakeLock()

        if (removeNotification) {
            exitForegroundAndRemoveNotification()
        }
    }

    private fun togglePlayPause() {
        if (state.isPlaying) {
            stopMetronome(removeNotification = true)
        } else {
            startMetronome()
        }
    }

    /**
     * 오디오 전용 고우선순위 스레드에서 실행되는 핵심 틱 함수.
     * 사운드 출력을 최우선으로 즉시 실행하며, 플래시/UI 등 무거운 작업은 비동기로 위임합니다.
     */
    private fun playBeat() {
        val current = state.currentBeat
        val isStrongBeat = (current == 0)

        // 1) 진동 모드만 이 틱에서 직접 실행합니다.
        // 일반 소리는 SoundManager의 AudioTrack PCM 루프가 독립적으로 연속 재생합니다.
        if (state.isVibrationMode) {
            vibrate(isStrongBeat)
        }

        // 2) 플래시: 캐시된 설정 확인 후 메인 핸들러로 비동기 디스패치 (카메라 IPC로 인한 오디오 지연 완전 차단!)
        if (isStrongBeat && isFlashEnabledCache) {
            mainHandler?.post {
                flashManager?.pulse()
            }
        }

        // 3) 다음 박 인덱스 갱신
        val nextBeat = (current + 1) % state.beatsPerMeasure
        state = state.copy(
            currentBeat = nextBeat,
            subBeatIndex = 0
        )
    }

    private fun updateNotification() {
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        cancelNotification()
    }

    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
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

        val timeText = if (state.isPlaying) getElapsedTimeText() else ""
        val timeSuffix = if (timeText.isNotEmpty()) " • 실행 중 $timeText" else ""
        val contentTitle = getString(R.string.app_name)
        val contentText = "${state.beatsPerMeasure}/${state.beatUnit} • ${state.bpm} BPM (${state.soundSetName})$timeSuffix"
        val subText = if (state.isPlaying) "실행 중 • $timeText" else getString(R.string.notification_paused)

        val playPauseIcon = if (state.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (state.isPlaying) {
            getString(R.string.action_pause)
        } else {
            getString(R.string.action_play)
        }

        val mediaStyle = MediaStyle()
            .setShowActionsInCompactView(0, 1)

        mediaSession?.let {
            mediaStyle.setMediaSession(it.sessionToken)
        }

        val elapsedMs = if (metronomeStartElapsedMs != 0L) {
            SystemClock.elapsedRealtime() - metronomeStartElapsedMs
        } else {
            0L
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(mediaStyle)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSubText(subText)
            .setContentIntent(mainPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), stopPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        if (state.isPlaying && metronomeStartElapsedMs != 0L) {
            builder.setUsesChronometer(true)
            builder.setShowWhen(true)
            builder.setWhen(System.currentTimeMillis() - elapsedMs)
        }

        return builder.build()
    }

    private fun clearMetronomeRunnable() {
        scheduleGeneration++
        scheduledTickRunnable?.let {
            audioHandler?.removeCallbacks(it)
        }
        scheduledTickRunnable = null
        audioRebuildRunnable?.let {
            audioHandler?.removeCallbacks(it)
        }
        audioRebuildRunnable = null
        idealNextTickNanos = 0L
    }

    fun nextSoundSet() {
        soundManager?.nextSoundSet()
        state = state.copy(
            soundSetIndex = soundManager?.getCurrentSetIndex() ?: state.soundSetIndex,
            soundSetName = soundManager?.getCurrentSetName() ?: state.soundSetName
        )
        if (state.isPlaying) {
            state = state.copy(currentBeat = 0, subBeatIndex = 0)
            if (!state.isVibrationMode) requestAudioLoopRebuild()
            rescheduleNextTickForNewTempo()
            updateMediaMetadata()
            updateNotification()
        }
    }

    fun setSoundSetIndex(index: Int) {
        soundManager?.setSoundSetIndex(index)
        state = state.copy(
            soundSetIndex = soundManager?.getCurrentSetIndex() ?: state.soundSetIndex,
            soundSetName = soundManager?.getCurrentSetName() ?: state.soundSetName
        )
        if (state.isPlaying) {
            state = state.copy(currentBeat = 0, subBeatIndex = 0)
            if (!state.isVibrationMode) requestAudioLoopRebuild()
            rescheduleNextTickForNewTempo()
            updateMediaMetadata()
            updateNotification()
        }
    }

    fun getSoundSetNames(): List<String> {
        return soundManager?.getSetNames() ?: emptyList()
    }

    fun getCurrentSoundSet(): String {
        return soundManager?.getCurrentSetName() ?: "None"
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        soundManager?.trimMemory()
    }

    override fun onDestroy() {
        clearMetronomeRunnable()
        mainHandler?.removeCallbacksAndMessages(null)
        audioHandler?.removeCallbacksAndMessages(null)
        stopMetronome(removeNotification = true)

        // 플래시 리스너 해제
        prefsListener?.let {
            getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        prefsListener = null

        // 오디오 전용 스레드 안전 종료
        try {
            audioThread?.quitSafely()
        } catch (_: Exception) {
        }
        audioThread = null
        audioHandler = null

        try {
            mediaSession?.apply {
                isActive = false
                release()
            }
        } catch (_: Exception) {
        }
        mediaSession = null

        soundManager?.release()
        soundManager = null

        flashManager?.release()
        flashManager = null

        vibrator = null
        releaseWakeLock()
        wakeLock = null
        mainHandler = null

        super.onDestroy()
    }
}
