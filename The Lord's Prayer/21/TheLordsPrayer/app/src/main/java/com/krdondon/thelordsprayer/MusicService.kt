package com.krdondon.thelordsprayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.media.AudioFocusRequest
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import androidx.media.session.MediaButtonReceiver
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.io.File

class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var resumePositionMs: Int = 0
    private var hasPlaybackNotification: Boolean = false
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

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

    /** 알림 및 시스템 미디어 플레이어용 앨범 아트워크 비트맵 캐시 */
    private var albumArtBitmap: Bitmap? = null

    companion object {
        const val CHANNEL_ID = "MusicServiceChannel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.krdondon.thelordsprayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.krdondon.thelordsprayer.ACTION_PAUSE"
        const val ACTION_STOP = "com.krdondon.thelordsprayer.ACTION_STOP"
        const val ACTION_NEXT_VOICE = "com.krdondon.thelordsprayer.ACTION_NEXT_VOICE"
        const val ACTION_SELECT_VOICE_SET = "com.krdondon.thelordsprayer.ACTION_SELECT_VOICE_SET"
        const val EXTRA_VOICE_SET_NAME = "extra_voice_set_name"

        const val PREFS_NAME = "voice_prefs"
        const val KEY_SELECTED_VOICE_SET = "selected_voice_set"

        private const val ASSET_SOUND_ROOT = "sounds"
        private val SET_DIR_REGEX = Regex("""^set\d+$""")

        private const val MEDIA_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        refreshVoiceSets()
        selectedVoiceSet = loadSelectedVoiceSetFromPrefs()

        albumArtBitmap = runCatching {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        }.getOrNull()

        initMediaSession()
    }

    private fun initMediaSession() {
        val mediaButtonReceiverPendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_PLAY_PAUSE,
        )

        mediaSession = MediaSessionCompat(this, "MusicService")
        mediaSession.apply {
            setMediaButtonReceiver(mediaButtonReceiverPendingIntent)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d("MusicService", "onPlay() called from MediaSessionCompat.Callback")
                    startPlayback()
                }

                override fun onPause() {
                    Log.d("MusicService", "onPause() called from MediaSessionCompat.Callback")
                    pausePlayback()
                }

                override fun onStop() {
                    Log.d("MusicService", "onStop() called from MediaSessionCompat.Callback")
                    stopPlayback()
                }

                override fun onSeekTo(pos: Long) {
                    val player = mediaPlayer ?: return
                    val safePos = pos.coerceIn(0L, player.duration.toLong().coerceAtLeast(0L))
                    player.seekTo(safePos.toInt())
                    resumePositionMs = safePos.toInt()
                    val state = if (player.isPlaying) {
                        PlaybackStateCompat.STATE_PLAYING
                    } else {
                        PlaybackStateCompat.STATE_PAUSED
                    }
                    updatePlaybackState(state, safePos)
                }

                override fun onSkipToNext() {
                    moveToNextVoiceSet()
                }
            })

            isActive = true
        }

        updateMetadata(durationMs = 0L)
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MusicService", "onStartCommand() called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_PLAY -> startPlayback()
            ACTION_PAUSE -> pausePlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_NEXT_VOICE -> moveToNextVoiceSet()
            ACTION_SELECT_VOICE_SET -> selectVoiceSet(intent.getStringExtra(EXTRA_VOICE_SET_NAME))
            else -> MediaButtonReceiver.handleIntent(mediaSession, intent)
        }

        return START_NOT_STICKY
    }

    private fun startPlayback() {
        if (!requestAudioFocusCompat()) {
            Log.d("MusicService", "Audio focus request denied")
            return
        }

        if (mediaPlayer == null) {
            mediaPlayer = createMediaPlayerForCurrentVoice()?.apply {
                isLooping = true
                restoreResumePosition(this)
            }
        }

        val player = mediaPlayer
        if (player != null) {
            if (!player.isPlaying) {
                player.start()
            }
            val duration = runCatching { player.duration.toLong() }.getOrDefault(-1L)
            updateMetadata(duration)
            val currentPos = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, currentPos)

            startForegroundNotification()
            hasPlaybackNotification = true
        } else {
            Log.e("MusicService", "MediaPlayer is null. Cannot start playback.")
        }
    }

    private fun pausePlayback() {
        val player = mediaPlayer ?: return

        runCatching { player.pause() }
        resumePositionMs = runCatching { player.currentPosition }.getOrDefault(resumePositionMs)
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, resumePositionMs.toLong())

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
        hasPlaybackNotification = true
        detachFromForeground()
    }

    private fun stopPlayback() {
        abandonAudioFocusCompat()

        resumePositionMs = 0
        currentPlayingAssetPath = null
        releaseMediaPlayer()

        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
        removeForegroundNotification()
        hasPlaybackNotification = false
        stopSelf()
    }

    private fun moveToNextVoiceSet() {
        refreshVoiceSets()

        if (availableVoiceSets.isEmpty()) {
            selectedVoiceSet = null
            saveSelectedVoiceSetToPrefs(null)
        } else {
            val currentIdx = availableVoiceSets.indexOf(selectedVoiceSet)
            val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % availableVoiceSets.size
            val next = availableVoiceSets[nextIdx]
            selectedVoiceSet = next
            saveSelectedVoiceSetToPrefs(next)
        }

        applyVoiceSetChange()
    }

    private fun selectVoiceSet(requestedSetName: String?) {
        refreshVoiceSets()

        val normalized = requestedSetName?.trim().orEmpty()

        if (normalized.isBlank() || (normalized == "기본")) {
            selectedVoiceSet = null
            saveSelectedVoiceSetToPrefs(null)
        } else if (!availableVoiceSets.contains(normalized)) {
            Log.w("MusicService", "Requested voice set not found: $normalized. Falling back to default.")
            selectedVoiceSet = null
            saveSelectedVoiceSetToPrefs(null)
        } else {
            selectedVoiceSet = normalized
            saveSelectedVoiceSetToPrefs(normalized)
        }

        applyVoiceSetChange()
    }

    private fun applyVoiceSetChange() {
        val wasPlaying = mediaPlayer?.isPlaying == true
        resumePositionMs = 0
        currentPlayingAssetPath = null
        releaseMediaPlayer()

        if (wasPlaying) {
            startPlayback()
            Log.d("MusicService", "Voice applied: ${getCurrentVoiceLabel()} (asset=$currentPlayingAssetPath)")
        } else {
            if (hasPlaybackNotification) {
                updateMetadata(0L)
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, 0L)
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, createNotification())
            }
            Log.d("MusicService", "Voice selected: ${getCurrentVoiceLabel()} (asset=$currentPlayingAssetPath)")
            stopSelf()
        }
    }

    private fun refreshVoiceSets() {
        availableVoiceSets = try {
            (assets.list(ASSET_SOUND_ROOT) ?: emptyArray())
                .asSequence()
                .filter { SET_DIR_REGEX.matches(it) }
                .sortedBy { it.removePrefix("set").toIntOrNull() ?: Int.MAX_VALUE }
                .toList()
        } catch (e: Exception) {
            Log.w("MusicService", "Failed to list assets/$ASSET_SOUND_ROOT: ${e.message}")
            emptyList()
        }

        if (selectedVoiceSet != null && !availableVoiceSets.contains(selectedVoiceSet)) {
            selectedVoiceSet = null
            saveSelectedVoiceSetToPrefs(null)
        }
    }

    private fun loadSelectedVoiceSetFromPrefs(): String? {
        val saved = prefs.getString(KEY_SELECTED_VOICE_SET, "") ?: ""
        return saved.ifBlank { null }
    }

    private fun saveSelectedVoiceSetToPrefs(setName: String?) {
        prefs.edit {
            putString(KEY_SELECTED_VOICE_SET, setName ?: "")
        }
    }

    private fun getCurrentVoiceLabel(): String {
        return selectedVoiceSet ?: "기본"
    }

    private fun createMediaPlayerForCurrentVoice(): MediaPlayer? {
        if (selectedVoiceSet == null) {
            currentPlayingAssetPath = null
            return try {
                MediaPlayer.create(this, R.raw.prayer_0815)
            } catch (e: Exception) {
                Log.e("MusicService", "Error creating default MediaPlayer: ${e.message}")
                null
            }
        }

        val setName = selectedVoiceSet ?: return null
        val folder = "$ASSET_SOUND_ROOT/$setName"

        val mp3List = try {
            (assets.list(folder) ?: emptyArray())
                .filter { it.endsWith(".mp3", ignoreCase = true) }
        } catch (e: Exception) {
            Log.e("MusicService", "Failed to list assets/$folder: ${e.message}")
            emptyList()
        }

        if (mp3List.isEmpty()) {
            Log.w("MusicService", "No mp3 found in assets/$folder. Falling back to default.")
            selectedVoiceSet = null
            saveSelectedVoiceSetToPrefs(null)
            currentPlayingAssetPath = null
            return MediaPlayer.create(this, R.raw.prayer_0815)
        }

        // 이미 선택된 트랙이 있고 해당 세트에 속해있으면 유지, 아니면 새로 랜덤 선택
        val assetPath = currentPlayingAssetPath?.takeIf { it.startsWith("$folder/") }
            ?: "$folder/${mp3List.random()}"
        currentPlayingAssetPath = assetPath

        return createMediaPlayerFromAssetPath(assetPath)
            ?: run {
                Log.w("MusicService", "Failed to create MediaPlayer from $assetPath. Falling back to default.")
                selectedVoiceSet = null
                saveSelectedVoiceSetToPrefs(null)
                currentPlayingAssetPath = null
                MediaPlayer.create(this, R.raw.prayer_0815)
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
            Log.w("MusicService", "openFd failed for $assetPath. Will copy to cache. (${e.message})")
        }

        // 2) 캐시로 복사 후 재생
        return try {
            val safeName = "voice_${assetPath.hashCode()}.mp3"
            val outFile = File(cacheDir, safeName)

            if (!outFile.exists() || outFile.length() == 0L) {
                assets.open(assetPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(outFile.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            Log.e("MusicService", "Failed to play asset via cache copy for $assetPath: ${e.message}")
            null
        }
    }

    /**
     * Android 13+ 미디어 컨트롤러의 squiggly wave animation(물결 재생 애니메이션) 및 시크바는
     * DURATION이 양수로 설정되어 있고 재생 상태가 STATE_PLAYING (speed=1f)일 때 정상 작동합니다.
     */
    private fun updateMetadata(durationMs: Long) {
        val voiceLabel = getCurrentVoiceLabel()
        val artist = if (voiceLabel == "기본") "예수" else "예수 ($voiceLabel)"

        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.notification_title))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (durationMs > 0) durationMs else -1L)

        albumArtBitmap?.let {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
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
            Log.w("MusicService", "Failed to restore playback position: ${it.message}")
            resumePositionMs = 0
        }
    }

    private fun releaseMediaPlayer() {
        val player = mediaPlayer ?: return
        runCatching { player.release() }
            .onFailure { Log.w("MusicService", "MediaPlayer release failed: ${it.message}") }
        mediaPlayer = null
    }

    private fun startForegroundNotification() {
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

        val pendingPlayPauseIntent = PendingIntent.getService(
            this,
            10,
            Intent(this, MusicService::class.java).setAction(playPauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pendingNextVoiceIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, MusicService::class.java).setAction(ACTION_NEXT_VOICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pendingStopIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, MusicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pendingActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val voiceLabel = getCurrentVoiceLabel()
        val contentText = if (voiceLabel == "기본") "예수" else "예수 ($voiceLabel)"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_cross_statusbar)
            .setLargeIcon(albumArtBitmap)
            .setContentIntent(pendingActivityIntent)
            .setDeleteIntent(pendingStopIntent)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 액션 0: 다음 음성
            .addAction(R.drawable.ic_play_arrow, "다음 음성", pendingNextVoiceIntent)
            // 액션 1: 재생 / 일시정지
            .addAction(playPauseIcon, getString(playPauseText), pendingPlayPauseIntent)
            // 액션 2: 정지
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop_action), pendingStopIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(pendingStopIntent)
            )

        return builder.build()
    }

    private fun requestAudioFocusCompat(): Boolean {
        val audioAttributes = createAudioAttributes()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(this)
            .build()
        audioFocusRequest = request

        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocusCompat() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 음성 기도 앱이므로 덕킹 시 볼륨을 줄이거나 일시정지
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 필요한 경우 자동 재개 고려 가능하나, 사용자 제어를 위해 명시적 재생 유도
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && mediaPlayer?.isPlaying != true) {
            releaseMediaPlayer()
            availableVoiceSets = emptyList()
            currentPlayingAssetPath = null
        }
    }

    override fun onDestroy() {
        abandonAudioFocusCompat()
        releaseMediaPlayer()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
