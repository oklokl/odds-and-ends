package com.krdonon.microphone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.krdonon.microphone.MainActivity
import kotlinx.coroutines.*
import java.io.File

class PlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_STOP = "ACTION_STOP"

        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_FILE_NAME = "EXTRA_FILE_NAME"

        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "playback_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME)
                if (filePath != null && fileName != null) {
                    startPlayback(File(filePath), fileName)
                }
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopPlayback()
        }

        return START_STICKY
    }

    private fun startPlayback(file: File, fileName: String) {
        try {
            stopPlayback() // 기존 재생 정리

            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()

                setOnCompletionListener {
                    stopPlayback()
                }
            }

            startForeground(NOTIFICATION_ID, createNotification(fileName, true))
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                updateNotification("일시정지됨", false)
            }
        }
    }

    private fun resumePlayback() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                updateNotification("재생 중", true)
            }
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "재생 알림",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "오디오 재생 상태를 표시합니다"
                setSound(null, null)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(fileName: String, isPlaying: Boolean): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseAction = if (isPlaying) {
            val pauseIntent = Intent(this, PlaybackService::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePendingIntent = PendingIntent.getService(
                this,
                1,
                pauseIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                "일시정지",
                pausePendingIntent
            ).build()
        } else {
            val resumeIntent = Intent(this, PlaybackService::class.java).apply {
                action = ACTION_RESUME
            }
            val resumePendingIntent = PendingIntent.getService(
                this,
                2,
                resumeIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "재개",
                resumePendingIntent
            ).build()
        }

        val stopIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_delete,
            "정지",
            stopPendingIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("재생 중")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(contentText: String, isPlaying: Boolean) {
        val notification = createNotification(contentText, isPlaying)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        serviceScope.cancel()
    }
}