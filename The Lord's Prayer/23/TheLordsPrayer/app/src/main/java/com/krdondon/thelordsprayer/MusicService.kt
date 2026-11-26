package com.krdondon.thelordsprayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media.session.MediaButtonReceiver
import java.io.File

@Suppress("DEPRECATION")
class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private var pausedByTransientFocusLoss: Boolean = false

    private var resumePositionMs: Int = 0
    private var hasPlaybackNotification: Boolean = false
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager

    /** assets/sounds 아래에 있는 set 폴더 목록 (예: set0, set1 ...) */
    private var availableVoiceSets: List<String> = emptyList()

    /**
     * 현재 선택된 음성 세트 이름.
     * - null: 기본 음성(res/raw/prayer_0815.mp3)
     * - "set0", "set1"...: assets/sounds/{setX} 내부 mp3 중 랜덤 1개 재생
     */
    private var selectedVoiceSet: String? = null

    /** 디버깅 및 재생 트랙 유지용: 현재 선택된 실제 asset 경로 */
    private var currentPlayingAssetPath: String? = null

    /** 알림 및 시스템 미디어 플레이어용 앨범 아트워크 비트맵 캐시 (지연 생성) */
    private var albumArtBitmap: Bitmap? = null

    private var isNoisyReceiverRegistered: Boolean = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                mediaPlayer?.isPlaying == true
            ) {
                Log.d(TAG, "Audio becoming noisy - pausing playback")
                pausedByTransientFocusLoss = false
                pausePlayback()
            }
        }
    }

    companion object {
        private const val TAG = "MusicService"
        const val CHANNEL_ID = "MusicServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val ALBUM_ART_SIZE_PX = 192

        const val ACTION_PLAY = "com.krdondon.thelordsprayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.krdondon.thelordsprayer.ACTION_PAUSE"
        const val ACTION_STOP = "com.krdondon.thelordsprayer.ACTION_STOP"
        const val ACTION_PREVIOUS_VOICE = "com.krdondon.thelordsprayer.ACTION_PREVIOUS_VOICE"
        const val ACTION_NEXT_VOICE = "com.krdondon.thelordsprayer.ACTION_NEXT_VOICE"
        const val ACTION_SELECT_VOICE_SET = "com.krdondon.thelordsprayer.ACTION_SELECT_VOICE_SET"
        const val EXTRA_VOICE_SET_NAME = "extra_voice_set_name"

        const val PREFS_NAME = VoiceSetStore.PREFS_NAME
        const val KEY_SELECTED_VOICE_SET = VoiceSetStore.KEY_SELECTED_VOICE_SET

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        private const val MEDIA_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        refreshVoiceSets()
        selectedVoiceSet = VoiceSetStore.loadSelectedVoiceSet(this)

        initMediaSession()
    }

    private fun initMediaSession() {
        val mediaButtonReceiverPendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_PLAY_PAUSE,
        )

        mediaSession = MediaSessionCompat(this, TAG)
        mediaSession.apply {
            setMediaButtonReceiver(mediaButtonReceiverPendingIntent)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        Log.d(TAG, "onPlay() called from MediaSessionCompat.Callback")
                        startPlayback(calledViaForegroundIntent = false)
                    }

                    override fun onPause() {
                        Log.d(TAG, "onPause() called from MediaSessionCompat.Callback")
                        pausedByTransientFocusLoss = false
                        pausePlayback()
                    }

                    override fun onStop() {
                        Log.d(TAG, "onStop() called from MediaSessionCompat.Callback")
                        stopPlayback()
                    }

                    override fun onSeekTo(pos: Long) {
                        val player = mediaPlayer ?: return
                        val duration = runCatching { player.duration.toLong() }.getOrDefault(0L)
                        val safePos = pos.coerceIn(0L, duration.coerceAtLeast(0L))
                        player.seekTo(safePos.toInt())
                        resumePositionMs = safePos.toInt()
                        val state = if (player.isPlaying) {
                            PlaybackStateCompat.STATE_PLAYING
                        } else {
                            PlaybackStateCompat.STATE_PAUSED
                        }
                        updatePlaybackState(state, safePos)
                    }

                    override fun onSkipToPrevious() {
                        moveToPreviousVoiceSet()
                    }

                    override fun onSkipToNext() {
                        moveToNextVoiceSet()
                    }
                },
            )

            isActive = true
        }

        updateMetadata(durationMs = 0L)
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand() called with action: $action")

        when (action) {
            ACTION_PLAY -> startPlayback(calledViaForegroundIntent = true)
            ACTION_PAUSE -> {
                pausedByTransientFocusLoss = false
                pausePlayback()
            }
            ACTION_STOP -> stopPlayback()
            ACTION_PREVIOUS_VOICE -> moveToPreviousVoiceSet()
            ACTION_NEXT_VOICE -> moveToNextVoiceSet()
            ACTION_SELECT_VOICE_SET -> selectVoiceSet(intent.getStringExtra(EXTRA_VOICE_SET_NAME))
            else -> {
                if (intent != null) {
                    MediaButtonReceiver.handleIntent(mediaSession, intent)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startPlayback(calledViaForegroundIntent: Boolean) {
        if (!requestAudioFocusCompat()) {
            Log.d(TAG, "Audio focus request denied")
            handleStartPlaybackFailure(calledViaForegroundIntent)
            return
        }

        pausedByTransientFocusLoss = false

        if (mediaPlayer == null) {
            mediaPlayer = createMediaPlayerForCurrentVoice()?.also {
                restoreResumePosition(it)
            }
        }

        val player = mediaPlayer
        if (player != null) {
            if (!player.isPlaying) {
                player.start()
            }
            registerNoisyReceiver()
            val duration = runCatching { player.duration.toLong() }.getOrDefault(-1L)
            updateMetadata(duration)
            val currentPos = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, currentPos)

            startForegroundNotification()
            hasPlaybackNotification = true
        } else {
            Log.e(TAG, "MediaPlayer is null. Cannot start playback.")
            abandonAudioFocusCompat()
            handleStartPlaybackFailure(calledViaForegroundIntent)
        }
    }

    /**
     * `startForegroundService()`로 시작된 경우 startForeground()를 호출하지 않고 return하면
     * ForegroundServiceDidNotStartInTimeException이 발생하므로 안전하게 foreground 계약 충족 후 정리합니다.
     */
    private fun handleStartPlaybackFailure(calledViaForegroundIntent: Boolean) {
        if (calledViaForegroundIntent && !hasPlaybackNotification) {
            startForegroundNotification()
            removeForegroundNotification()
        }
        if (!hasPlaybackNotification) {
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
            stopSelf()
        }
    }

    private fun pausePlayback() {
        val player = mediaPlayer ?: return

        unregisterNoisyReceiver()
        runCatching {
            if (player.isPlaying) {
                player.pause()
            }
        }
        resumePositionMs = runCatching { player.currentPosition }.getOrDefault(resumePositionMs)
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, resumePositionMs.toLong())

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification())
        hasPlaybackNotification = true
        detachFromForeground()
    }

    private fun stopPlayback() {
        unregisterNoisyReceiver()
        abandonAudioFocusCompat()
        pausedByTransientFocusLoss = false

        resumePositionMs = 0
        currentPlayingAssetPath = null
        releaseMediaPlayer()

        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
        removeForegroundNotification()
        hasPlaybackNotification = false
        stopSelf()
    }

    private fun moveToPreviousVoiceSet() {
        refreshVoiceSets()

        val prev = VoiceSetStore.getPreviousVoiceSet(availableVoiceSets, selectedVoiceSet)
        selectedVoiceSet = prev
        VoiceSetStore.saveSelectedVoiceSet(this, prev)

        applyVoiceSetChange()
    }

    private fun moveToNextVoiceSet() {
        refreshVoiceSets()

        val next = VoiceSetStore.getNextVoiceSet(availableVoiceSets, selectedVoiceSet)
        selectedVoiceSet = next
        VoiceSetStore.saveSelectedVoiceSet(this, next)

        applyVoiceSetChange()
    }

    private fun selectVoiceSet(requestedSetName: String?) {
        refreshVoiceSets()

        val normalized = requestedSetName?.trim().orEmpty()

        if (normalized.isBlank() || normalized == VoiceSetStore.DEFAULT_VOICE_LABEL) {
            selectedVoiceSet = null
            VoiceSetStore.saveSelectedVoiceSet(this, null)
        } else if (!availableVoiceSets.contains(normalized)) {
            Log.w(TAG, "Requested voice set not found: $normalized. Falling back to default.")
            selectedVoiceSet = null
            VoiceSetStore.saveSelectedVoiceSet(this, null)
        } else {
            selectedVoiceSet = normalized
            VoiceSetStore.saveSelectedVoiceSet(this, normalized)
        }

        applyVoiceSetChange()
    }

    private fun applyVoiceSetChange() {
        val wasPlaying = mediaPlayer?.isPlaying == true
        resumePositionMs = 0
        currentPlayingAssetPath = null
        releaseMediaPlayer()

        if (wasPlaying) {
            startPlayback(calledViaForegroundIntent = false)
            Log.d(TAG, "Voice applied: ${getCurrentVoiceLabel()} (asset=$currentPlayingAssetPath)")
        } else if (hasPlaybackNotification) {
            // 일시정지 상태에서 음성을 바꾼 경우: 새 트랙 길이 메타데이터를 유지하고 MediaSession을 살려둡니다.
            val preparedPlayer = createMediaPlayerForCurrentVoice()
            mediaPlayer = preparedPlayer
            val duration = preparedPlayer?.let {
                runCatching { it.duration.toLong() }.getOrDefault(-1L)
            } ?: -1L
            updateMetadata(duration)
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, 0L)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "Voice updated while paused: ${getCurrentVoiceLabel()} (asset=$currentPlayingAssetPath)")
        } else {
            Log.d(TAG, "Voice selected: ${getCurrentVoiceLabel()} (asset=$currentPlayingAssetPath)")
            stopSelf()
        }
    }

    private fun refreshVoiceSets() {
        availableVoiceSets = VoiceSetStore.listAvailableVoiceSets(assets)

        if (selectedVoiceSet != null && !availableVoiceSets.contains(selectedVoiceSet)) {
            selectedVoiceSet = null
            VoiceSetStore.saveSelectedVoiceSet(this, null)
        }
    }

    private fun getCurrentVoiceLabel(): String {
        return VoiceSetStore.formatVoiceLabel(selectedVoiceSet)
    }

    private fun createMediaPlayerForCurrentVoice(): MediaPlayer? {
        if (selectedVoiceSet == null) {
            currentPlayingAssetPath = null
            return createDefaultMediaPlayer()?.let { configureMediaPlayer(it) }
        }

        val setName = selectedVoiceSet ?: return null
        val folder = "${VoiceSetStore.ASSET_SOUND_ROOT}/$setName"

        val mp3List = try {
            (assets.list(folder) ?: emptyArray())
                .filter { it.endsWith(".mp3", ignoreCase = true) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list assets/$folder: ${e.message}")
            emptyList()
        }

        if (mp3List.isEmpty()) {
            Log.w(TAG, "No mp3 found in assets/$folder. Falling back to default.")
            selectedVoiceSet = null
            VoiceSetStore.saveSelectedVoiceSet(this, null)
            currentPlayingAssetPath = null
            return createDefaultMediaPlayer()?.let { configureMediaPlayer(it) }
        }

        // 이미 선택된 트랙이 있고 해당 세트에 속해있으면 유지, 아니면 새로 랜덤 선택
        val assetPath = currentPlayingAssetPath?.takeIf { it.startsWith("$folder/") }
            ?: "$folder/${mp3List.random()}"
        currentPlayingAssetPath = assetPath

        val assetPlayer = createMediaPlayerFromAssetPath(assetPath)
        if (assetPlayer != null) {
            return configureMediaPlayer(assetPlayer)
        }

        Log.w(TAG, "Failed to create MediaPlayer from $assetPath. Falling back to default.")
        selectedVoiceSet = null
        VoiceSetStore.saveSelectedVoiceSet(this, null)
        currentPlayingAssetPath = null
        return createDefaultMediaPlayer()?.let { configureMediaPlayer(it) }
    }

    private fun createDefaultMediaPlayer(): MediaPlayer? {
        val audioAttributes = createAudioAttributes()
        try {
            resources.openRawResourceFd(R.raw.prayer_0815).use { afd ->
                return MediaPlayer().apply {
                    setAudioAttributes(audioAttributes)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    prepare()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "openRawResourceFd failed, trying MediaPlayer.create: ${e.message}")
        }

        return try {
            MediaPlayer.create(this, R.raw.prayer_0815)?.apply {
                setAudioAttributes(audioAttributes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating default MediaPlayer: ${e.message}", e)
            null
        }
    }

    private fun configureMediaPlayer(player: MediaPlayer): MediaPlayer {
        return player.apply {
            // isLooping=true는 루프 시 OnCompletionListener가 호출되지 않아
            // 잠금화면 미디어 시크바 시간이 첫 루프 이후 어긋날 수 있으므로 직접 루프합니다.
            isLooping = false
            setOnCompletionListener { completedPlayer ->
                runCatching {
                    completedPlayer.seekTo(0)
                    completedPlayer.start()
                    resumePositionMs = 0
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, 0L)
                }.onFailure { error ->
                    Log.e(TAG, "Failed to loop playback: ${error.message}", error)
                    stopPlayback()
                }
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                stopPlayback()
                true
            }
        }
    }

    private fun createAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
    }

    private fun createMediaPlayerFromAssetPath(assetPath: String): MediaPlayer? {
        val audioAttributes = createAudioAttributes()

        // 1) 우선 openFd 방식 (가장 빠름)
        try {
            assets.openFd(assetPath).use { afd ->
                return MediaPlayer().apply {
                    setAudioAttributes(audioAttributes)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    prepare()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "openFd failed for $assetPath. Will copy to cache. (${e.message})")
        }

        // 2) 캐시로 복사 후 재생 (임시 파일 후 원자적 이동으로 불완전 파일 방지)
        return try {
            val sanitizedName = assetPath.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val outFile = File(cacheDir, "voice_$sanitizedName")

            if (!outFile.exists() || outFile.length() == 0L) {
                val tempFile = File(cacheDir, "voice_${sanitizedName}.tmp")
                assets.open(assetPath).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (!tempFile.renameTo(outFile)) {
                    tempFile.copyTo(outFile, overwrite = true)
                    tempFile.delete()
                }
            }

            MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(outFile.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play asset via cache copy for $assetPath: ${e.message}")
            null
        }
    }

    /**
     * API 26+의 adaptive-icon XML은 BitmapFactory.decodeResource가 null을 반환하므로
     * Drawable을 통해 작은 정사각 비트맵으로 안전하게 렌더링합니다.
     */
    private fun getOrCreateAlbumArtBitmap(): Bitmap? {
        albumArtBitmap?.let { return it }

        val drawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(ALBUM_ART_SIZE_PX, ALBUM_ART_SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            albumArtBitmap = bitmap
            bitmap
        }.onFailure {
            Log.w(TAG, "Failed to render album art bitmap: ${it.message}")
        }.getOrNull()
    }

    /**
     * Android 13+ 미디어 컨트롤러의 squiggly wave animation(물결 재생 애니메이션) 및 시크바는
     * DURATION이 양수로 설정되어 있고 재생 상태가 STATE_PLAYING (speed=1f)일 때 정상 작동합니다.
     */
    private fun updateMetadata(durationMs: Long) {
        val voiceLabel = getCurrentVoiceLabel()
        val artist = if (voiceLabel == VoiceSetStore.DEFAULT_VOICE_LABEL) "예수" else "예수 ($voiceLabel)"

        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.notification_title))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (durationMs > 0) durationMs else -1L)

        if (durationMs > 0 || hasPlaybackNotification) {
            getOrCreateAlbumArtBitmap()?.let {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            }
        }

        mediaSession.setMetadata(builder.build())
    }

    private fun updatePlaybackState(state: Int, positionMs: Long? = null) {
        val currentPosition = positionMs ?: runCatching {
            mediaPlayer?.currentPosition?.toLong() ?: resumePositionMs.toLong()
        }.getOrDefault(resumePositionMs.toLong())

        val playbackSpeed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(MEDIA_ACTIONS)
            .setState(state, currentPosition, playbackSpeed, SystemClock.elapsedRealtime())
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun restoreResumePosition(player: MediaPlayer) {
        if (resumePositionMs <= 0) return

        runCatching {
            val durationMs = player.duration
            val target = if (durationMs > 0) {
                resumePositionMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
            } else {
                resumePositionMs
            }
            player.seekTo(target)
        }.onFailure {
            Log.w(TAG, "Failed to restore playback position: ${it.message}")
            resumePositionMs = 0
        }
    }

    private fun releaseMediaPlayer() {
        val player = mediaPlayer ?: return
        runCatching { player.release() }
            .onFailure { Log.w(TAG, "MediaPlayer release failed: ${it.message}") }
        mediaPlayer = null
    }

    private fun startForegroundNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun detachFromForeground() {
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun removeForegroundNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "주님의 기도 미디어 재생 컨트롤"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        val isPlaying = mediaPlayer?.isPlaying ?: false
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseText = if (isPlaying) R.string.notification_pause_action else R.string.notification_play_action

        val pendingPrevVoiceIntent = PendingIntent.getService(
            this,
            9,
            Intent(this, MusicService::class.java).setAction(ACTION_PREVIOUS_VOICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pendingPlayPauseIntent = PendingIntent.getService(
            this,
            10,
            Intent(this, MusicService::class.java).setAction(playPauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pendingNextVoiceIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, MusicService::class.java).setAction(ACTION_NEXT_VOICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pendingStopIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, MusicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingActivityIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val voiceLabel = getCurrentVoiceLabel()
        val contentText = if (voiceLabel == VoiceSetStore.DEFAULT_VOICE_LABEL) "예수" else "예수 ($voiceLabel)"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_cross_statusbar)
            .setLargeIcon(getOrCreateAlbumArtBitmap())
            .setContentIntent(pendingActivityIntent)
            .setDeleteIntent(pendingStopIntent)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 액션 0: 이전 음성
            .addAction(
                R.drawable.ic_skip_previous,
                getString(R.string.notification_previous_action),
                pendingPrevVoiceIntent,
            )
            // 액션 1: 재생 / 일시정지
            .addAction(playPauseIcon, getString(playPauseText), pendingPlayPauseIntent)
            // 액션 2: 다음 음성
            .addAction(
                R.drawable.ic_skip_next,
                getString(R.string.notification_next_action),
                pendingNextVoiceIntent,
            )
            // 액션 3: 정지
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop_action), pendingStopIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(pendingStopIntent),
            )

        return builder.build()
    }

    private fun requestAudioFocusCompat(): Boolean {
        if (hasAudioFocus) return true

        val audioAttributes = createAudioAttributes()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(this)
            .build()

        val result = audioManager.requestAudioFocus(request)
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = request
            hasAudioFocus = true
            true
        } else {
            hasAudioFocus = false
            false
        }
    }

    private fun abandonAudioFocusCompat() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
        hasAudioFocus = false
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByTransientFocusLoss = false
                hasAudioFocus = false
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                }
                abandonAudioFocusCompat()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (mediaPlayer?.isPlaying == true) {
                    pausedByTransientFocusLoss = true
                    pausePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (pausedByTransientFocusLoss) {
                    pausedByTransientFocusLoss = false
                    startPlayback(calledViaForegroundIntent = false)
                }
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (isNoisyReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isNoisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!isNoisyReceiverRegistered) return
        runCatching { unregisterReceiver(noisyReceiver) }
        isNoisyReceiverRegistered = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (mediaPlayer?.isPlaying != true && hasPlaybackNotification) {
            stopPlayback()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && mediaPlayer?.isPlaying != true) {
            releaseMediaPlayer()
            availableVoiceSets = emptyList()
            currentPlayingAssetPath = null
            albumArtBitmap = null
            VoiceSetStore.clearCache()
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        unregisterNoisyReceiver()
        abandonAudioFocusCompat()
        releaseMediaPlayer()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        albumArtBitmap = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
