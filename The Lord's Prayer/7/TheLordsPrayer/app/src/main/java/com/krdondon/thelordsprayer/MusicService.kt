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
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.util.Log

class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager

    companion object {
        const val CHANNEL_ID = "MusicServiceChannel"
        const val ACTION_PLAY = "com.krdondon.thelordsprayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.krdondon.thelordsprayer.ACTION_PAUSE"
        const val ACTION_STOP = "com.krdondon.thelordsprayer.ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
        // MediaButtonReceiver.handleIntent(mediaSession, intent) 대신 직접 처리
        when (intent?.action) {
            ACTION_PLAY -> startPlayback()
            ACTION_PAUSE -> pausePlayback()
            ACTION_STOP -> stopPlayback()
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
                try {
                    mediaPlayer = MediaPlayer.create(this, R.raw.prayer_0815)?.apply {
                        isLooping = true
                    }
                } catch (e: Exception) {
                    Log.e("MusicService", "Error creating MediaPlayer: ${e.message}")
                    mediaPlayer = null
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
        val pendingPlayPauseIntent = PendingIntent.getService(this, 0, playPauseIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, MusicService::class.java).setAction(ACTION_STOP)
        val pendingStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
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
            // 다른 앱이 오디오 포커스를 영구적으로 가져갔을 때
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 서비스를 완전히 중지하는 대신 일시 정지만 수행합니다.
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                }
            }
            // 다른 앱이 짧게 오디오 포커스를 가져갔을 때
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                }
            }
            // 다른 앱이 짧게 오디오 포커스를 가져가지만 소리가 겹쳐도 될 때
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
            // 오디오 포커스를 다시 얻었을 때 (이전 상태에 따라 재생을 재개할 수 있음)
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 현재는 아무 동작도 하지 않지만, 필요에 따라 로직을 추가할 수 있습니다.
                // 예: mediaPlayer?.start()
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