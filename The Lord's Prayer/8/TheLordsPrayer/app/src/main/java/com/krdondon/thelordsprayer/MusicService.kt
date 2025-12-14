package com.krdondon.thelordsprayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.io.File

class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** assets/sounds 아래에 있는 set 폴더 목록 (예: set0, set1 ...) */
    private var availableVoiceSets: List<String> = emptyList()

    /**
     * 현재 선택된 음성 세트 이름.
     * - null: 기본 음성(res/raw/prayer_0815.mp3)
     * - "set0", "set1"...: assets/sounds/{setX} 내부 mp3 중 랜덤 1개 재생
     */
    private var selectedVoiceSet: String? = null

    /** 디버깅용: 마지막으로 선택된 실제 asset 경로 */
    private var lastAssetPath: String? = null

    companion object {
        const val CHANNEL_ID = "MusicServiceChannel"

        const val ACTION_PLAY = "com.krdondon.thelordsprayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.krdondon.thelordsprayer.ACTION_PAUSE"
        const val ACTION_STOP = "com.krdondon.thelordsprayer.ACTION_STOP"

        // 새 기능: 음성 변경(기본 -> set0 -> set1 -> ... -> 기본 순환)
        const val ACTION_NEXT_VOICE = "com.krdondon.thelordsprayer.ACTION_NEXT_VOICE"

        private const val PREFS_NAME = "voice_prefs"
        private const val KEY_SELECTED_VOICE_SET = "selected_voice_set"

        private const val ASSET_SOUND_ROOT = "sounds"
        private val SET_DIR_REGEX = Regex("""^set\d+$""")
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 음성 세트 초기화 (서비스 재시작 시에도 자동 복구)
        refreshVoiceSets()
        selectedVoiceSet = loadSelectedVoiceSetFromPrefs()

        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PAUSE or
                                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                                PlaybackStateCompat.ACTION_STOP
                    )
                    .build()
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
            })
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "주님의 기도")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "예수")
                    .build()
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MusicService", "onStartCommand() called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_PLAY -> startPlayback()
            ACTION_PAUSE -> pausePlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_NEXT_VOICE -> moveToNextVoiceSet()
            else -> MediaButtonReceiver.handleIntent(mediaSession, intent)
        }

        return START_NOT_STICKY
    }

    private fun startPlayback() {
        @Suppress("DEPRECATION")
        val result = audioManager.requestAudioFocus(
            this,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (mediaPlayer == null) {
                mediaPlayer = createMediaPlayerForCurrentVoice()?.apply {
                    isLooping = true
                }
            }

            if (mediaPlayer != null) {
                mediaPlayer?.start()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                startForeground(1, createNotification())
            } else {
                Log.e("MusicService", "MediaPlayer is null. Cannot start playback.")
            }
        } else {
            Log.d("MusicService", "Audio focus request failed with result: $result")
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification())
        stopForeground(false)
    }

    private fun stopPlayback() {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(this)

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(true)
        stopSelf()
    }

    private fun moveToNextVoiceSet() {
        // assets/sounds 아래 set 폴더가 빌드에 포함되어 있으면 자동 인식됩니다.
        refreshVoiceSets()

        // 개선: "음성 변경" 버튼은 set0 -> set1 -> set2 ... 처럼 "세트"만 순환합니다.
        // (기본 음성은 세트가 없을 때만 자동 사용)
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

        // 재생/일시정지 상태에 따라 동작
        val wasPlaying = mediaPlayer?.isPlaying == true
        val hadPlayer = mediaPlayer != null

        // 중요: 음성 변경 직후 곧바로 새 음성이 적용되도록 플레이어를 확실히 교체
        mediaPlayer?.let {
            try {
                it.stop()
            } catch (_: Exception) {
                // ignore
            }
            it.release()
        }
        mediaPlayer = null

        if (wasPlaying) {
            // 즉시 새 음성으로 재시작
            startPlayback()
            // lastAssetPath는 startPlayback() 내부에서 새 음성이 선택된 뒤 갱신됨
            Log.d("MusicService", "Voice applied: ${getCurrentVoiceLabel()} (asset=$lastAssetPath)")
        } else {
            // 일시정지 상태였다면, 알림만 갱신(다음 재생 시 새 음성 적용)
            if (hadPlayer) {
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(1, createNotification())
            }
            Log.d("MusicService", "Voice selected: ${getCurrentVoiceLabel()} (asset=$lastAssetPath)")
            // 음성 변경만 누르고 재생 중이 아니라면 서비스 유지가 필요 없으므로 종료
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

        // 저장된 세트가 사라졌다면 기본 음성으로 복구
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
        prefs.edit().putString(KEY_SELECTED_VOICE_SET, setName ?: "").apply()
    }

    private fun getCurrentVoiceLabel(): String {
        return selectedVoiceSet ?: "기본"
    }

    private fun createMediaPlayerForCurrentVoice(): MediaPlayer? {
        // 1) 기본 음성
        if (selectedVoiceSet == null) {
            return try {
                lastAssetPath = null
                MediaPlayer.create(this, R.raw.prayer_0815)
            } catch (e: Exception) {
                Log.e("MusicService", "Error creating default MediaPlayer: ${e.message}")
                null
            }
        }

        // 2) assets/sounds/setX 내부 mp3 중 랜덤 선택
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
            return MediaPlayer.create(this, R.raw.prayer_0815)
        }

        val picked = mp3List.random()
        val assetPath = "$folder/$picked"
        lastAssetPath = assetPath

        return createMediaPlayerFromAssetPath(assetPath)
            ?: run {
                Log.w("MusicService", "Failed to create MediaPlayer from $assetPath. Falling back to default.")
                selectedVoiceSet = null
                saveSelectedVoiceSetToPrefs(null)
                MediaPlayer.create(this, R.raw.prayer_0815)
            }
    }

    /**
     * assets 안의 mp3를 재생하기 위한 MediaPlayer 생성.
     * - openFd()가 실패(압축된 asset 등)하면, cacheDir로 복사 후 파일 경로로 재생합니다.
     */
    private fun createMediaPlayerFromAssetPath(assetPath: String): MediaPlayer? {
        // 1) 우선 openFd 방식 (가장 빠름)
        try {
            assets.openFd(assetPath).use { afd ->
                return MediaPlayer().apply {
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
                setDataSource(outFile.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            Log.e("MusicService", "Failed to play asset via cache copy for $assetPath: ${e.message}")
            null
        }
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, mediaPlayer?.currentPosition?.toLong() ?: 0, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }

        val isPlaying = mediaPlayer?.isPlaying ?: false
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseText = if (isPlaying) R.string.notification_pause_action else R.string.notification_play_action

        val playPauseIntent = Intent(this, MusicService::class.java).setAction(playPauseAction)
        val pendingPlayPauseIntent = PendingIntent.getService(
            this,
            0,
            playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MusicService::class.java).setAction(ACTION_STOP)
        val pendingStopIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = "${getString(R.string.notification_text)} (${getCurrentVoiceLabel()})"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_play_arrow)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(playPauseIcon, getString(playPauseText), pendingPlayPauseIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop_action), pendingStopIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )

        return builder.build()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 서비스를 완전히 중지하는 대신 일시 정지만 수행합니다.
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // no-op
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 필요 시 로직 추가 가능 (예: mediaPlayer?.start())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(this)
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession.release()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
