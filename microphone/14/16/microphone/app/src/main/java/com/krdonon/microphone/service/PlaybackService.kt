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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.krdonon.microphone.MainActivity
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream

class PlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentFileName: String = ""
    private var currentFilePath: String = ""
    private var isServiceInForeground = false

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

        // 즉시 Foreground 시작 (중요!)
        if (!isServiceInForeground) {
            try {
                startForeground(NOTIFICATION_ID, createNotification("준비 중", false, isLoading = true))
                isServiceInForeground = true
                Log.d(TAG, "Service moved to foreground")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground", e)
            }
        }

        when (intent?.action) {
            ACTION_PLAY -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME)

                Log.d(TAG, "ACTION_PLAY - filePath: $filePath, fileName: $fileName")

                if (filePath != null && fileName != null) {
                    currentFileName = fileName
                    currentFilePath = filePath

                    // 기존 재생 정리
                    mediaPlayer?.release()
                    mediaPlayer = null

                    // 새로운 재생 시작
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
        }

        return START_NOT_STICKY
    }

    private fun startPlayback(file: File, fileName: String) {
        Log.d(TAG, "startPlayback - file: ${file.absolutePath}, exists: ${file.exists()}, size: ${file.length()}")

        serviceScope.launch(Dispatchers.IO) {
            try {
                if (!file.exists() || file.length() == 0L) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PlaybackService, "파일을 찾을 수 없거나 비어있습니다", Toast.LENGTH_SHORT).show()
                        stopPlayback()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    try {
                        Log.d(TAG, "Creating MediaPlayer...")
                        mediaPlayer = MediaPlayer().apply {
                            val fis = FileInputStream(file)
                            setDataSource(fis.fd)
                            fis.close()

                            setOnPreparedListener { mp ->
                                Log.d(TAG, "MediaPlayer prepared, starting playback")
                                mp.start()
                                updateNotification(fileName, true)

                                // HomeScreen 상태 업데이트
                                com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = true
                            }
                            setOnCompletionListener {
                                Log.d(TAG, "Playback completed")
                                // HomeScreen 상태 초기화
                                com.krdonon.microphone.ui.screens.PlaybackState.currentPlayingId.value = null
                                com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = false
                                stopPlayback()
                            }
                            setOnErrorListener { _, what, extra ->
                                Log.e(TAG, "MediaPlayer error - what: $what, extra: $extra")
                                Toast.makeText(this@PlaybackService, "재생 오류 발생", Toast.LENGTH_SHORT).show()
                                // HomeScreen 상태 초기화
                                com.krdonon.microphone.ui.screens.PlaybackState.currentPlayingId.value = null
                                com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = false
                                stopPlayback()
                                true
                            }

                            Log.d(TAG, "Calling prepareAsync()...")
                            prepareAsync()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create MediaPlayer", e)
                        stopPlayback()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in startPlayback", e)
                withContext(Dispatchers.Main) {
                    stopSelf()
                }
            }
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                updateNotification(currentFileName, false)
                // HomeScreen 상태 업데이트
                com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = false
                Log.d(TAG, "Playback paused")
            }
        }
    }

    private fun resumePlayback() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                updateNotification(currentFileName, true)
                // HomeScreen 상태 업데이트
                com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = true
                Log.d(TAG, "Playback resumed")
            }
        }
    }

    private fun stopPlayback() {
        Log.d(TAG, "stopPlayback called")

        // HomeScreen의 재생 상태 초기화
        com.krdonon.microphone.ui.screens.PlaybackState.currentPlayingId.value = null
        com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = false

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

        isServiceInForeground = false
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

        if (!isLoading) {
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

            builder.addAction(playPauseAction)
            builder.addAction(stopAction)
        }

        return builder.build()
    }

    private fun updateNotification(fileName: String, isPlaying: Boolean) {
        if (isServiceInForeground) {
            val notification = createNotification(fileName, isPlaying)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy()")

        // 상태 초기화
        com.krdonon.microphone.ui.screens.PlaybackState.currentPlayingId.value = null
        com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = false

        mediaPlayer?.release()
        serviceScope.cancel()
    }
}