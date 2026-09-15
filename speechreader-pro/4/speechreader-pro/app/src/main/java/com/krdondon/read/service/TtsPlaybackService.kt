package com.krdondon.read.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.krdondon.read.MainActivity
import com.krdondon.read.data.db.AppDatabase
import com.krdondon.read.data.repository.DocumentRepository
import com.krdondon.read.util.SentenceChunk
import com.krdondon.read.util.SentenceParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class TtsPlaybackService : Service(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var initializedSystemDefaultEngine: String? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var repository: DocumentRepository

    private var currentDocId: Long = -1L
    private var docTitle: String = ""
    private var sentences: List<SentenceChunk> = emptyList()
    private var currentSentenceIndex: Int = 0
    private var isPlaying: Boolean = false
    private var isPaused: Boolean = false

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "tts_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_OR_PLAY = "com.krdondon.read.action.START_OR_PLAY"
        const val ACTION_PAUSE = "com.krdondon.read.action.PAUSE"
        const val ACTION_RESUME = "com.krdondon.read.action.RESUME"
        const val ACTION_STOP = "com.krdondon.read.action.STOP"
        const val ACTION_SEEK_TO = "com.krdondon.read.action.SEEK_TO"
        const val ACTION_SET_RATE = "com.krdondon.read.action.SET_RATE"
        const val ACTION_SET_PITCH = "com.krdondon.read.action.SET_PITCH"
        const val ACTION_SET_LOOP = "com.krdondon.read.action.SET_LOOP"
        const val ACTION_SET_ENGINE = "com.krdondon.read.action.SET_ENGINE"

        const val EXTRA_DOC_ID = "extra_doc_id"
        const val EXTRA_DOC_TITLE = "extra_doc_title"
        const val EXTRA_DOC_CONTENT = "extra_doc_content"
        const val EXTRA_START_INDEX = "extra_start_index"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_RATE = "extra_rate"
        const val EXTRA_PITCH = "extra_pitch"
        const val EXTRA_LOOP = "extra_loop"
        const val EXTRA_ENGINE = "extra_engine"

        fun startOrPlay(
            context: Context,
            docId: Long,
            title: String,
            content: String,
            startIndex: Int = 0
        ) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_START_OR_PLAY
                putExtra(EXTRA_DOC_ID, docId)
                putExtra(EXTRA_DOC_TITLE, title)
                putExtra(EXTRA_DOC_CONTENT, content)
                putExtra(EXTRA_START_INDEX, startIndex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun seekTo(context: Context, index: Int) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_SEEK_TO
                putExtra(EXTRA_INDEX, index)
            }
            context.startService(intent)
        }

        fun setRate(context: Context, rate: Float) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_SET_RATE
                putExtra(EXTRA_RATE, rate)
            }
            context.startService(intent)
        }

        fun setPitch(context: Context, pitch: Float) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_SET_PITCH
                putExtra(EXTRA_PITCH, pitch)
            }
            context.startService(intent)
        }

        fun setLoop(context: Context, enabled: Boolean) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_SET_LOOP
                putExtra(EXTRA_LOOP, enabled)
            }
            context.startService(intent)
        }

        fun setEngine(context: Context, engineType: TtsEngineType) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_SET_ENGINE
                putExtra(EXTRA_ENGINE, engineType.name)
            }
            context.startService(intent)
        }
    }

    private var isLoopEnabled: Boolean = true
    private var currentEngineType: TtsEngineType = TtsEngineType.SYSTEM

    override fun onCreate() {
        super.onCreate()
        val dao = AppDatabase.getInstance(this).txtDocumentDao()
        repository = DocumentRepository(dao)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TxtReader:TtsWakeLock")

        createNotificationChannel()
        currentEngineType = TtsStateHolder.uiState.value.engineType
        initializeTts(currentEngineType)
    }

    private fun initializeTts(engineType: TtsEngineType) {
        isTtsInitialized = false
        initializedSystemDefaultEngine = if (engineType == TtsEngineType.SYSTEM) {
            readSystemDefaultEnginePackage()
        } else {
            null
        }

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            // ignore
        }

        val pkg = engineType.packageName
        textToSpeech = try {
            // SYSTEM은 엔진 패키지를 지정하지 않습니다.
            // 이렇게 새 인스턴스를 만들면 Android의 "기본 TTS 엔진" 설정을 다시 읽습니다.
            if (pkg != null) TextToSpeech(this, this, pkg) else TextToSpeech(this, this)
        } catch (e: Exception) {
            // 명시 엔진 생성이 실패하면 시스템 기본 엔진으로 한 번 더 시도합니다.
            TextToSpeech(this, this)
        }
    }

    private fun readSystemDefaultEnginePackage(): String? {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
                ?.takeIf { it.isNotBlank() }
                ?: textToSpeech?.defaultEngine?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            try {
                textToSpeech?.defaultEngine?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * SYSTEM 모드에서 사용자가 Android 설정의 기본 TTS 엔진을 바꿨는지 확인합니다.
     *
     * TextToSpeech 인스턴스는 생성 시점의 기본 엔진을 계속 사용하므로, 앱이 살아 있는 동안
     * 삼성 TTS <-> Google TTS 기본 엔진을 바꿔도 기존 인스턴스는 자동 전환되지 않습니다.
     * 따라서 인스턴스를 만들 당시의 시스템 기본 엔진 패키지와 현재 설정을 비교합니다.
     */
    private fun hasSystemDefaultEngineChanged(): Boolean {
        if (currentEngineType != TtsEngineType.SYSTEM) return false

        val currentSystemDefault = readSystemDefaultEnginePackage() ?: return false
        val initializedDefault = initializedSystemDefaultEngine ?: return true
        return currentSystemDefault != initializedDefault
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                if (currentEngineType == TtsEngineType.SYSTEM) {
                    initializedSystemDefaultEngine = try {
                        tts.defaultEngine?.takeIf { it.isNotBlank() } ?: initializedSystemDefaultEngine
                    } catch (_: Exception) {
                        initializedSystemDefaultEngine
                    }
                }

                val result = tts.setLanguage(Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault())
                }
                tts.setSpeechRate(TtsStateHolder.uiState.value.speechRate)
                tts.setPitch(TtsStateHolder.uiState.value.speechPitch)

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        serviceScope.launch {
                            isPlaying = true
                            isPaused = false
                            TtsStateHolder.setPlaybackStatus(PlaybackStatus.PLAYING)
                            updateNotification()
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        serviceScope.launch {
                            if (isPlaying && !isPaused) {
                                val nextIndex = currentSentenceIndex + 1
                                if (nextIndex < sentences.size) {
                                    currentSentenceIndex = nextIndex
                                    persistProgress(currentSentenceIndex)
                                    speakCurrentSentence()
                                } else {
                                    // Finished all sentences: 처음으로 돌아가서 다시 읽기 (무한 루프)
                                    if (isLoopEnabled && sentences.isNotEmpty()) {
                                        currentSentenceIndex = 0
                                        persistProgress(0)
                                        speakCurrentSentence()
                                    } else {
                                        handlePlaybackComplete()
                                    }
                                }
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        serviceScope.launch {
                            if (isPlaying && !isPaused) {
                                val nextIndex = currentSentenceIndex + 1
                                if (nextIndex < sentences.size) {
                                    currentSentenceIndex = nextIndex
                                    speakCurrentSentence()
                                } else {
                                    if (isLoopEnabled && sentences.isNotEmpty()) {
                                        currentSentenceIndex = 0
                                        persistProgress(0)
                                        speakCurrentSentence()
                                    } else {
                                        handlePlaybackComplete()
                                    }
                                }
                            }
                        }
                    }
                })

                isTtsInitialized = true
                // If there's a pending speech action
                if (isPlaying && sentences.isNotEmpty()) {
                    speakCurrentSentence()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OR_PLAY -> {
                val docId = intent.getLongExtra(EXTRA_DOC_ID, -1L)
                val title = intent.getStringExtra(EXTRA_DOC_TITLE) ?: ""
                val content = intent.getStringExtra(EXTRA_DOC_CONTENT) ?: ""
                val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

                isLoopEnabled = TtsStateHolder.uiState.value.isLoopEnabled
                val desiredEngine = TtsStateHolder.uiState.value.engineType

                currentDocId = docId
                docTitle = title
                sentences = SentenceParser.parseSentences(content)
                currentSentenceIndex = startIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))

                TtsStateHolder.setDocumentInfo(docId, title, sentences.size)
                isPlaying = true
                isPaused = false

                acquireWakeLock()
                startForegroundServiceCompat()

                // SYSTEM은 재생을 새로 시작할 때 반드시 TTS 인스턴스를 다시 만듭니다.
                // 사용자가 앱 밖의 "글자 읽어주기 > 기본 엔진"에서 삼성/Google을 변경했을 수 있기 때문입니다.
                val shouldReinitialize =
                    currentEngineType != desiredEngine ||
                            !isTtsInitialized ||
                            desiredEngine == TtsEngineType.SYSTEM

                if (shouldReinitialize) {
                    currentEngineType = desiredEngine
                    initializeTts(desiredEngine)
                } else {
                    speakCurrentSentence()
                }
            }
            ACTION_PAUSE -> {
                pausePlayback()
            }
            ACTION_RESUME -> {
                resumePlayback()
            }
            ACTION_STOP -> {
                stopPlayback()
            }
            ACTION_SEEK_TO -> {
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                if (sentences.isNotEmpty()) {
                    currentSentenceIndex = index.coerceIn(0, sentences.size - 1)
                    persistProgress(currentSentenceIndex)
                    if (isPlaying && !isPaused) {
                        speakCurrentSentence()
                    } else {
                        val currText = sentences.getOrNull(currentSentenceIndex)?.text ?: ""
                        TtsStateHolder.setCurrentSentence(currentSentenceIndex, currText)
                        updateNotification()
                    }
                }
            }
            ACTION_SET_RATE -> {
                val rate = intent.getFloatExtra(EXTRA_RATE, 1.0f)
                TtsStateHolder.setSpeechRate(rate)
                textToSpeech?.setSpeechRate(rate)
            }
            ACTION_SET_PITCH -> {
                val pitch = intent.getFloatExtra(EXTRA_PITCH, 1.0f)
                TtsStateHolder.setSpeechPitch(pitch)
                textToSpeech?.setPitch(pitch)
            }
            ACTION_SET_LOOP -> {
                val loop = intent.getBooleanExtra(EXTRA_LOOP, true)
                isLoopEnabled = loop
                TtsStateHolder.setLoopEnabled(loop)
            }
            ACTION_SET_ENGINE -> {
                val engineName = intent.getStringExtra(EXTRA_ENGINE) ?: TtsEngineType.SYSTEM.name
                val newEngine = try {
                    TtsEngineType.valueOf(engineName)
                } catch (e: Exception) {
                    TtsEngineType.SYSTEM
                }
                TtsStateHolder.setEngineType(newEngine)

                // 같은 SYSTEM을 다시 적용한 경우에도 재생성합니다.
                // 앱 실행 중 휴대폰의 기본 TTS가 바뀌었을 수 있으므로 기존 인스턴스를 재사용하면 안 됩니다.
                val shouldReinitialize =
                    currentEngineType != newEngine ||
                            !isTtsInitialized ||
                            newEngine == TtsEngineType.SYSTEM

                if (shouldReinitialize) {
                    currentEngineType = newEngine
                    initializeTts(newEngine)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun speakCurrentSentence() {
        // SYSTEM 모드로 연속 재생 중에도 Android 기본 엔진 변경을 감지합니다.
        // 변경되었다면 새 기본 엔진으로 다시 초기화한 뒤 onInit()에서 같은 위치부터 이어 읽습니다.
        if (isTtsInitialized && hasSystemDefaultEngineChanged()) {
            initializeTts(TtsEngineType.SYSTEM)
            return
        }

        if (!isTtsInitialized || sentences.isEmpty()) return
        if (currentSentenceIndex !in sentences.indices) return

        val chunk = sentences[currentSentenceIndex]
        TtsStateHolder.setCurrentSentence(currentSentenceIndex, chunk.text)
        TtsStateHolder.setPlaybackStatus(PlaybackStatus.PLAYING)
        updateNotification()

        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sentence_$currentSentenceIndex")
        }
        textToSpeech?.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, params, "sentence_$currentSentenceIndex")
    }

    private fun pausePlayback() {
        textToSpeech?.stop()
        isPlaying = false
        isPaused = true
        releaseWakeLock()
        TtsStateHolder.setPlaybackStatus(PlaybackStatus.PAUSED)
        persistProgress(currentSentenceIndex)
        updateNotification()
    }

    private fun resumePlayback() {
        if (sentences.isEmpty()) return
        isPlaying = true
        isPaused = false
        acquireWakeLock()
        TtsStateHolder.setPlaybackStatus(PlaybackStatus.PLAYING)
        speakCurrentSentence()
    }

    private fun stopPlayback() {
        textToSpeech?.stop()
        isPlaying = false
        isPaused = false
        sentences = emptyList()
        releaseWakeLock()
        TtsStateHolder.setPlaybackStatus(PlaybackStatus.STOPPED)
        persistProgress(currentSentenceIndex)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun handlePlaybackComplete() {
        isPlaying = false
        isPaused = false
        releaseWakeLock()
        TtsStateHolder.setPlaybackStatus(PlaybackStatus.STOPPED)
        persistProgress(currentSentenceIndex)
        updateNotification()
    }

    private fun persistProgress(sentenceIndex: Int) {
        if (currentDocId != -1L) {
            serviceScope.launch {
                repository.updateLastReadSentence(currentDocId, sentenceIndex)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours max safe timeout
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS 음성 재생 알림",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "텍스트를 백그라운드에서 읽어줄 때 제어 버튼과 진행 상태를 표시합니다."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("doc_id", currentDocId)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val total = sentences.size
        val progressText = if (total > 0) {
            "문장 ${currentSentenceIndex + 1} / $total (${((currentSentenceIndex + 1) * 100 / total)}%)"
        } else {
            "음성 재생 준비 중"
        }

        val currentTextSnippet = sentences.getOrNull(currentSentenceIndex)?.text?.take(60) ?: ""

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (docTitle.isNotBlank()) docTitle else "K 읽기")
            .setContentText("$progressText : $currentTextSnippet")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Notification Action: Play / Pause
        if (isPlaying && !isPaused) {
            val pauseIntent = Intent(this, TtsPlaybackService::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePending = PendingIntent.getService(
                this, 1, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "멈춤", pausePending)
        } else {
            val resumeIntent = Intent(this, TtsPlaybackService::class.java).apply {
                action = ACTION_RESUME
            }
            val resumePending = PendingIntent.getService(
                this, 2, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "재생", resumePending)
        }

        // Notification Action: Stop
        val stopIntent = Intent(this, TtsPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 3, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "정지", stopPending)

        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundServiceCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        sentences = emptyList()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        serviceScope.cancel()
        TtsStateHolder.reset()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
