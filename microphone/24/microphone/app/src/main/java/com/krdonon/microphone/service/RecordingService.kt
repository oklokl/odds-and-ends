package com.krdonon.microphone.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.krdonon.microphone.MainActivity
import com.krdonon.microphone.data.repository.RecordingRepository
import com.krdonon.microphone.data.repository.SettingsRepository
import com.krdonon.microphone.utils.AudioRecorder
import com.krdonon.microphone.utils.CacheCleaner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

class RecordingService : Service() {

    private var audioRecorder: AudioRecorder? = null
    private var currentOutputFile: File? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    // 🔊 진폭 / 경과시간 수신용 Job
    private var amplitudeJob: Job? = null
    private var elapsedTimeJob: Job? = null

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var telephonyManager: TelephonyManager
    private var phoneStateListener: PhoneStateListener? = null


    // ✨ 알림에 쓸 마지막 경과 시간(밀리초)
    private var lastElapsedMillis: Long = 0L

    // ✨ 알림을 마지막으로 갱신한 시점(초 단위)
    private var lastNotificationSeconds: Long = -1L




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

        // ⏱ 알림 갱신 간격(초) – 7, 10, 40 중 편한 값으로 바꾸셔도 됩니다. 1초로 바꿈
        private const val NOTIFICATION_UPDATE_INTERVAL_SEC = 1L
    }

    override fun onCreate() {
        super.onCreate()

        settingsRepository = SettingsRepository(applicationContext)
        recordingRepository = RecordingRepository(applicationContext)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager


        // 🔹 캐시 정리를 서비스 전용 IO 스레드에서 실행
        serviceScope.launch {
            CacheCleaner.cleanRecordingCache(applicationContext)
        }

        createNotificationChannel()
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("RecordingService", "onStartCommand - action: ${intent?.action}")

        // ✅ 녹음 세션이 없는데(서비스만 잘못 시작됨) PAUSE/RESUME가 들어오면
        // 알림을 만들지 말고 바로 종료한다.
        if ((intent?.action == ACTION_PAUSE_RECORDING || intent?.action == ACTION_RESUME_RECORDING)
            && (audioRecorder == null || !RecordingStateManager.isRecording.value)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val outputFilePath = intent.getStringExtra(EXTRA_OUTPUT_FILE)
                if (outputFilePath != null) {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification("녹음 준비 중", false)
                    )
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

        // ✅ 실제 녹음(또는 녹음 시작 요청)이 있을 때만 sticky
        return if (intent?.action == ACTION_START_RECORDING || audioRecorder != null || RecordingStateManager.isRecording.value) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private fun startRecording(outputFile: File) {
        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val hasPhonePermissions = ContextCompat.checkSelfPermission(
                this@RecordingService, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                this@RecordingService, Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED

            if (settings.blockCallsWhileRecording && hasPhonePermissions) {
                withContext(Dispatchers.Main) {
                    registerPhoneStateListener()
                }
            } else if (settings.blockCallsWhileRecording && !hasPhonePermissions) {
                Log.w("RecordingService", "Block calls feature is enabled but permissions are not granted.")
            }
            currentOutputFile = outputFile

            audioRecorder = AudioRecorder(applicationContext, settings)
            audioRecorder?.startRecording(outputFile)

            // ... 기존 amplitudeJob / elapsedTimeJob 설정 코드 ...


            // 상태 초기화
            RecordingStateManager.onStart()
            lastElapsedMillis = 0L
            lastNotificationSeconds = -1L

            // 처음 알림 한 번 바로 표시
            updateNotification(
                buildNotificationText(0L, isRecording = true),
                isRecording = true
            )

            // 🔊 진폭 전달
            amplitudeJob?.cancel()
            amplitudeJob = launch {
                audioRecorder?.currentAmplitude?.collect { amp ->
                    RecordingStateManager.updateAmplitude(amp)
                }
            }

            // ⏱ 경과 시간 전달 + 알림은 10초에 한 번만 갱신
            elapsedTimeJob?.cancel()
            elapsedTimeJob = launch {
                audioRecorder?.elapsedTime?.collect { millis ->
                    lastElapsedMillis = millis
                    RecordingStateManager.updateElapsedTime(millis)

                    val totalSeconds = millis / 1000
                    val needUpdate =
                        lastNotificationSeconds < 0 ||
                                (totalSeconds - lastNotificationSeconds) >= NOTIFICATION_UPDATE_INTERVAL_SEC

                    if (needUpdate) {
                        lastNotificationSeconds = totalSeconds
                        updateNotification(
                            buildNotificationText(millis, isRecording = true),
                            isRecording = true
                        )
                    }
                }
            }
        }
    }

    private fun pauseRecording() {
        val recorder = audioRecorder
        if (recorder == null || !RecordingStateManager.isRecording.value) {
            // 녹음 세션이 없는데 pause가 들어온 경우(통화 이벤트 등) → 알림 생성 방지
            stopSelf()
            return
        }

        recorder.pauseRecording()
        RecordingStateManager.onPause()

        // 일시정지 시에도 마지막 시간 기준으로 표시
        updateNotification(
            buildNotificationText(lastElapsedMillis, isRecording = false),
            isRecording = false
        )
    }

    private fun resumeRecording() {
        val recorder = audioRecorder
        if (recorder == null || !RecordingStateManager.isRecording.value) {
            // 녹음 세션이 없는데 resume가 들어온 경우 → 알림 생성 방지
            stopSelf()
            return
        }

        recorder.resumeRecording()
        RecordingStateManager.onResume()

        // 재개 시에도 마지막 시간 기준으로 표시
        updateNotification(
            buildNotificationText(lastElapsedMillis, isRecording = true),
            isRecording = true
        )
    }

    private fun stopRecording(fromNotification: Boolean = false) {
        Log.d("RecordingService", "stopRecording called")
        serviceScope.launch(Dispatchers.Main) {
            unregisterPhoneStateListener()
        }

        amplitudeJob?.cancel()
        elapsedTimeJob?.cancel()

        // release() also cancels AudioRecorder's owned coroutine scope.
        audioRecorder?.release()
        audioRecorder = null

        val tempFile = currentOutputFile
        currentOutputFile = null

        // 상태 업데이트
        RecordingStateManager.onStop(fromNotification)

        if (fromNotification && tempFile != null) {
            // 알림에서 정지: 자동 저장 후 서비스 종료
            val job = serviceScope.launch(Dispatchers.IO) {
                try {
                    val defaultName = recordingRepository.generateFileName()
                    recordingRepository.saveRecording(tempFile, defaultName, "미지정")
                    Log.d("RecordingService", "Auto save completed")
                } catch (e: Exception) {
                    Log.e("RecordingService", "Auto save failed", e)
                }
            }

            job.invokeOnCompletion {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            // 화면에서 정지
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("NewApi")
    private fun registerPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    try {
                        val telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        val endCallMethod = telephony.javaClass.getMethod("endCall")
                        endCallMethod.invoke(telephony)
                    } catch (e: Exception) {
                        Log.e("RecordingService", "Failed to end call", e)
                    }
                }
            }
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun unregisterPhoneStateListener() {
        phoneStateListener?.let {
            telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            phoneStateListener = null
        }
    }

    // ---------- 알림 관련 ----------

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

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d("RecordingService", "Notification channel created")
        }
    }

    private fun createNotification(
        contentText: String,
        isRecording: Boolean
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_RECORDING_SCREEN, true)
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
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // "00:00:00" 형식 문자열 생성 (최대 99:59:59)
    private fun formatElapsedForNotification(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = (totalSeconds / 3600).coerceAtMost(99)
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    // 알림에 표시할 전체 문구
    private fun buildNotificationText(millis: Long, isRecording: Boolean): String {
        val timeText = formatElapsedForNotification(millis)
        return if (isRecording) {
            "$timeText  녹음 중…"
        } else {
            "$timeText  일시정지됨"
        }
    }

    // -----------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("RecordingService", "onDestroy()")
        serviceScope.launch(Dispatchers.Main) {
            unregisterPhoneStateListener()
        }
        amplitudeJob?.cancel()
        elapsedTimeJob?.cancel()
        audioRecorder?.release()
        serviceScope.cancel()
    }
}
