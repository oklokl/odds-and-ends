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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.krdonon.microphone.MainActivity
import kotlinx.coroutines.*
import java.io.File

class PlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentFileName: String = ""

    companion object {
        private const val TAG = "PlaybackService"

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
        Log.d(TAG, "Service onCreate()")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand - action: ${intent?.action}")

        // 서비스 시작 시 즉시 Foreground 상태로 전환 (5초 타임아웃 방지)
        when (intent?.action) {
            ACTION_PLAY -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME)

                Log.d(TAG, "ACTION_PLAY - filePath: $filePath, fileName: $fileName")

                if (filePath != null && fileName != null) {
                    currentFileName = fileName
                    // 먼저 Foreground 시작 (필수!)
                    try {
                        startForeground(NOTIFICATION_ID, createNotification(fileName, false, isLoading = true))
                        Log.d(TAG, "Started foreground service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start foreground service", e)
                    }
                    // 그 다음 재생 준비
                    startPlayback(File(filePath), fileName)
                } else {
                    Log.e(TAG, "filePath or fileName is null!")
                    stopSelf()
                }
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "ACTION_PAUSE")
                pausePlayback()
            }
            ACTION_RESUME -> {
                Log.d(TAG, "ACTION_RESUME")
                resumePlayback()
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP")
                stopPlayback()
            }
            else -> {
                Log.w(TAG, "Unknown action or null action")
                // 액션 없이 서비스가 시작된 경우에도 Foreground 상태 유지
                startForeground(NOTIFICATION_ID, createNotification("준비 중", false, isLoading = true))
            }
        }

        return START_STICKY
    }

    private fun startPlayback(file: File, fileName: String) {
        Log.d(TAG, "startPlayback - file: ${file.absolutePath}, exists: ${file.exists()}")

        serviceScope.launch {
            try {
                if (!file.exists()) {
                    Log.e(TAG, "File does not exist: ${file.absolutePath}")
                    stopPlayback()
                    return@launch
                }

                stopPlayback() // 기존 재생 정리

                Log.d(TAG, "Creating MediaPlayer...")
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnPreparedListener {
                        Log.d(TAG, "MediaPlayer prepared, starting playback")
                        // 준비 완료 후 재생 시작
                        start()
                        updateNotification(fileName, true)
                    }
                    setOnCompletionListener {
                        Log.d(TAG, "Playback completed")
                        stopPlayback()
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error - what: $what, extra: $extra")
                        // 에러 발생 시
                        stopPlayback()
                        true
                    }
                    // 비동기 준비
                    Log.d(TAG, "Calling prepareAsync()...")
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in startPlayback", e)
                stopSelf()
            }
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                updateNotification(currentFileName, false)
                Log.d(TAG, "Playback paused")
            }
        }
    }

    private fun resumePlayback() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                updateNotification(currentFileName, true)
                Log.d(TAG, "Playback resumed")
            }
        }
    }

    private fun stopPlayback() {
        Log.d(TAG, "stopPlayback called")
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
                Log.d(TAG, "MediaPlayer released")
            } catch (e: Exception) {
                Log.e(TAG, "Exception while stopping playback", e)
            }
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
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createNotification(fileName: String, isPlaying: Boolean, isLoading: Boolean = false): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = when {
            isLoading -> "준비 중..."
            isPlaying -> fileName
            else -> "$fileName (일시정지)"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("재생")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)

        // 로딩 중이 아닐 때만 버튼 추가
        if (!isLoading) {
            // 재생/일시정지 버튼
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

            // 정지 버튼
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

            builder.addAction(playPauseAction)
            builder.addAction(stopAction)
        }

        return builder.build()
    }

    private fun updateNotification(fileName: String, isPlaying: Boolean) {
        val notification = createNotification(fileName, isPlaying)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy()")
        mediaPlayer?.release()
        serviceScope.cancel()
    }
}