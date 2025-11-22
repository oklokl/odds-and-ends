package com.krdonon.microphone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.krdonon.microphone.MainActivity
import com.krdonon.microphone.data.model.AppSettings
import com.krdonon.microphone.data.repository.RecordingRepository
import com.krdonon.microphone.data.repository.SettingsRepository
import com.krdonon.microphone.utils.AudioRecorder
import com.krdonon.microphone.service.RecordingStateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

class RecordingService : Service() {

    private var audioRecorder: AudioRecorder? = null
    private var currentOutputFile: File? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var recordingRepository: RecordingRepository

    companion object {
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_PAUSE_RECORDING = "ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "ACTION_RESUME_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val EXTRA_OUTPUT_FILE = "EXTRA_OUTPUT_FILE"

        const val EXTRA_STOP_FROM_NOTIFICATION = "EXTRA_STOP_FROM_NOTIFICATION"
        const val EXTRA_OPEN_RECORDING_SCREEN = "EXTRA_OPEN_RECORDING_SCREEN"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "recording_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("RecordingService", "onCreate()")
        settingsRepository = SettingsRepository(applicationContext)
        recordingRepository = RecordingRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("RecordingService", "onStartCommand - action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val outputFilePath = intent.getStringExtra(EXTRA_OUTPUT_FILE)
                if (outputFilePath != null) {
                    startForeground(NOTIFICATION_ID, createNotification("녹음 준비 중", false))
                    startRecording(File(outputFilePath))
                }
            }
            ACTION_PAUSE_RECORDING -> pauseRecording()
            ACTION_RESUME_RECORDING -> resumeRecording()
            ACTION_STOP_RECORDING -> {
                val fromNotification =
                    intent.getBooleanExtra(EXTRA_STOP_FROM_NOTIFICATION, false)
                stopRecording(fromNotification)
            }
        }

        return START_STICKY
    }


    private fun startRecording(outputFile: File) {
        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            currentOutputFile = outputFile

            audioRecorder = AudioRecorder(applicationContext, settings)
            audioRecorder?.startRecording(outputFile)

            // 상태 업데이트
            RecordingStateManager.onStart()

            updateNotification("녹음 중", true)

            // (진폭 모니터링 부분은 그대로)
        }
    }


    private fun pauseRecording() {
        audioRecorder?.pauseRecording()
        Thread.sleep(100)
        RecordingStateManager.onPause()
        updateNotification("일시정지됨", false)
    }

    private fun resumeRecording() {
        audioRecorder?.resumeRecording()
        RecordingStateManager.onResume()
        updateNotification("녹음 중", true)
    }

    private fun stopRecording(fromNotification: Boolean = false) {
        Log.d("RecordingService", "stopRecording called")
        audioRecorder?.stopRecording()
        audioRecorder?.release()
        audioRecorder = null

        val tempFile = currentOutputFile
        currentOutputFile = null

        // 상태 업데이트
        RecordingStateManager.onStop(fromNotification)

        if (fromNotification && tempFile != null) {
            // 알림에서 정지: 자동 저장 후에 서비스 종료
            val job = serviceScope.launch(Dispatchers.IO) {
                try {
                    val defaultName = recordingRepository.generateFileName()
                    recordingRepository.saveRecording(tempFile, defaultName, "미지정")
                    Log.d("RecordingService", "Auto save completed")
                } catch (e: Exception) {
                    Log.e("RecordingService", "Auto save failed", e)
                }
            }

            // 저장이 끝난 뒤에만 서비스 종료
            job.invokeOnCompletion {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            // 일반 정지(화면에서 정지 버튼) → 기존 동작 유지
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "녹음 알림",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "녹음 진행 상태를 표시합니다"
                setSound(null, null)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d("RecordingService", "Notification channel created")
        }
    }

    private fun createNotification(contentText: String, isRecording: Boolean): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_RECORDING_SCREEN, true)
            // 기존 task 위에 올라오도록 플래그 설정
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 재생/일시정지 버튼
        val playPauseAction = if (isRecording) {
            val pauseIntent = Intent(this, RecordingService::class.java).apply {
                action = ACTION_PAUSE_RECORDING
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
            val resumeIntent = Intent(this, RecordingService::class.java).apply {
                action = ACTION_RESUME_RECORDING
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
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
            putExtra(EXTRA_STOP_FROM_NOTIFICATION, true)
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
            .setContentTitle("상단 마이크")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(contentText: String, isRecording: Boolean) {
        val notification = createNotification(contentText, isRecording)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("RecordingService", "onDestroy()")
        audioRecorder?.release()
        serviceScope.cancel()
    }
}