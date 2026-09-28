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
import com.krdonon.microphone.data.repository.RecordingRepository
import com.krdonon.microphone.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream

class PlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentFileName: String = ""
    private var currentFilePath: String = ""
    private var isServiceInForeground = false
    private var progressJob: Job? = null

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var recordingRepository: RecordingRepository

    companion object {
        private const val TAG = "PlaybackService"

        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SEEK = "ACTION_SEEK"

        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_FILE_NAME = "EXTRA_FILE_NAME"
        const val EXTRA_RECORDING_ID = "EXTRA_RECORDING_ID"
        const val EXTRA_SEEK_POSITION = "EXTRA_SEEK_POSITION"

        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "playback_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        settingsRepository = SettingsRepository(applicationContext)
        recordingRepository = RecordingRepository(applicationContext)
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
            ACTION_SEEK -> {
                val seekPos = intent.getIntExtra(EXTRA_SEEK_POSITION, 0)
                Log.d(TAG, "ACTION_SEEK - position: $seekPos")
                seekTo(seekPos)
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

                val allRecordings = recordingRepository.recordingsFlow.first()
                val recording = allRecordings.firstOrNull { it.filePath == file.absolutePath }
                    ?: com.krdonon.microphone.data.model.RecordingFile(
                        id = file.nameWithoutExtension,
                        fileName = fileName,
                        filePath = file.absolutePath,
                        duration = 0L,
                        fileSize = file.length(),
                        dateCreated = file.lastModified(),
                        category = "미지정"
                    )

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
                                val totalDuration = mp.duration.toLong()
                                updateNotification(fileName, true)

                                // 전역 상태 업데이트
                                PlaybackStateManager.onStart(recording, totalDuration)
                                com.krdonon.microphone.ui.screens.PlaybackState.currentPlayingId.value = recording.id
                                com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = true

                                startProgressUpdates()
                            }
                            setOnCompletionListener {
                                Log.d(TAG, "Playback completed")
                                handlePlaybackCompletion()
                            }
                            setOnErrorListener { _, what, extra ->
                                Log.e(TAG, "MediaPlayer error - what: $what, extra: $extra")
                                Toast.makeText(this@PlaybackService, "재생 오류 발생", Toast.LENGTH_SHORT).show()
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

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive && mediaPlayer != null) {
                try {
                    val mp = mediaPlayer
                    if (mp != null && mp.isPlaying) {
                        val pos = mp.currentPosition.toLong()
                        PlaybackStateManager.updatePosition(pos)

                        // 구간 반복 (A-B Repeat) 로직
                        if (PlaybackStateManager.isRepeatActive.value) {
                            val repeatB = PlaybackStateManager.repeatB.value
                            val repeatA = PlaybackStateManager.repeatA.value ?: 0L
                            if (repeatB != null && pos >= repeatB) {
                                mp.seekTo(repeatA.toInt())
                                PlaybackStateManager.updatePosition(repeatA)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore transient errors
                }
                delay(100)
            }
        }
    }

    private fun seekTo(positionMs: Int) {
        mediaPlayer?.let { mp ->
            try {
                mp.seekTo(positionMs)
                PlaybackStateManager.updatePosition(positionMs.toLong())
            } catch (e: Exception) {
                Log.e(TAG, "Error in seekTo", e)
            }
        }
    }

    private fun handlePlaybackCompletion() {
        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            if (settings.autoPlayNext) {
                val currentRecordings = recordingRepository.recordingsFlow.first()
                val currentIndex = currentRecordings.indexOfFirst { it.filePath == currentFilePath }

                if (currentIndex != -1 && currentIndex < currentRecordings.size - 1) {
                    val nextRecording = currentRecordings[currentIndex + 1]
                    currentFilePath = nextRecording.filePath
                    currentFileName = nextRecording.fileName
                    startPlayback(File(currentFilePath), currentFileName)
                } else {
                    onPlaybackFinished()
                }
            } else {
                onPlaybackFinished()
            }
        }
    }

    private fun onPlaybackFinished() {
        // 구간 반복 활성화 상태라면 시작점으로 이동하여 계속 재생
        if (PlaybackStateManager.isRepeatActive.value) {
            val repeatA = PlaybackStateManager.repeatA.value ?: 0L
            mediaPlayer?.seekTo(repeatA.toInt())
            mediaPlayer?.start()
            PlaybackStateManager.updatePosition(repeatA)
            PlaybackStateManager.updateIsPlaying(true)
            return
        }

        // 재생이 완료되면 팝업을 닫지 않고 처음 위치(0초)로 이동하여 일시정지 상태로 대기
        try {
            mediaPlayer?.seekTo(0)
            PlaybackStateManager.updatePosition(0L)
            PlaybackStateManager.updateIsPlaying(false)
            com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = false
            updateNotification(currentFileName, isPlaying = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPlaybackFinished", e)
            stopPlayback()
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                updateNotification(currentFileName, isPlaying = false)
                PlaybackStateManager.updateIsPlaying(false)
                com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = false
                Log.d(TAG, "Playback paused")
            }
        }
    }

    private fun resumePlayback() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                updateNotification(currentFileName, isPlaying = true)
                PlaybackStateManager.updateIsPlaying(true)
                com.krdonon.microphone.ui.screens.PlaybackState.isPlaying.value = true
                startProgressUpdates()
                Log.d(TAG, "Playback resumed")
            }
        }
    }

    private fun stopPlayback() {
        Log.d(TAG, "stopPlayback called")

        progressJob?.cancel()
        progressJob = null

        PlaybackStateManager.reset()
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
