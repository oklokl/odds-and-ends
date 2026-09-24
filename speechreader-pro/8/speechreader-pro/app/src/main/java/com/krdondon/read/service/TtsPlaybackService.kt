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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.krdondon.read.MainActivity
import com.krdondon.read.data.db.AppDatabase
import com.krdondon.read.data.repository.DocumentRepository
import com.krdondon.read.util.SentenceChunk
import com.krdondon.read.util.SentenceParser
import com.krdondon.read.util.TtsLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class TtsPlaybackService : Service(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var pendingPlayOnInitialized = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var repository: DocumentRepository

    private var currentDocId: Long = -1L
    private var docTitle: String = ""
    private var sentences: List<SentenceChunk> = emptyList()
    private var currentSentenceIndex: Int = 0
    private var isPlaying: Boolean = false
    private var isPaused: Boolean = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var isLoopEnabled: Boolean = true
    private var currentEngineType: TtsEngineType = TtsEngineType.SYSTEM
    private var hasAttemptedFallback: Boolean = false

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

    override fun onCreate() {
        super.onCreate()
        TtsLogManager.log("SERVICE_LIFECYCLE", "event='SERVICE_ON_CREATE'")
        val dao = AppDatabase.getInstance(this).txtDocumentDao()
        repository = DocumentRepository(dao)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TxtReader:TtsWakeLock")

        createNotificationChannel()
        currentEngineType = TtsStateHolder.uiState.value.engineType
        initializeTts(currentEngineType)
    }

    private fun resolveTargetEnginePackage(engineType: TtsEngineType): String? {
        return when (engineType) {
            TtsEngineType.GOOGLE -> {
                "com.google.android.tts"
            }
            TtsEngineType.SYSTEM -> {
                val isSamsungDevice = Build.MANUFACTURER.contains("samsung", ignoreCase = true)
                val hasSamsungTts = TtsLogManager.isPackageInstalled(this, "com.samsung.SMT")
                TtsLogManager.log("ENGINE_RESOLVE", "isSamsungDevice=$isSamsungDevice, hasSamsungTts=$hasSamsungTts")
                if (hasSamsungTts) {
                    "com.samsung.SMT"
                } else {
                    null
                }
            }
        }
    }

    /**
     * TTS instance initialization with bidirectional auto-fallback
     */
    private fun initializeTts(engineType: TtsEngineType, isFallback: Boolean = false) {
        if (!isFallback) {
            hasAttemptedFallback = false
        }
        isTtsInitialized = false
        val pkg = resolveTargetEnginePackage(engineType)
        TtsLogManager.log("ENGINE_INIT", "event='REQUEST_INIT', targetEngine='${engineType.name}', resolvedPackage='${pkg ?: "DEFAULT_SYSTEM"}', isFallback=$isFallback")

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            TtsLogManager.logError("ENGINE_INIT", "event='SHUTDOWN_EXCEPTION', targetEngine='${engineType.name}'", e)
        }

        textToSpeech = try {
            if (pkg != null) {
                TtsLogManager.log("ENGINE_INIT", "event='CREATE_TTS_EXPLICIT_PACKAGE', pkg='$pkg'")
                TextToSpeech(this, this, pkg)
            } else {
                TtsLogManager.log("ENGINE_INIT", "event='CREATE_TTS_DEFAULT_SYSTEM_ENGINE'")
                TextToSpeech(this, this)
            }
        } catch (e: Exception) {
            TtsLogManager.logError("ENGINE_INIT", "event='CREATE_TTS_FAILED_FALLBACK_TO_DEFAULT'", e)
            try {
                TextToSpeech(this, this)
            } catch (fallbackEx: Exception) {
                TtsLogManager.logError("ENGINE_INIT", "event='CREATE_TTS_DEFAULT_ALSO_FAILED'", fallbackEx)
                null
            }
        }
    }

    override fun onInit(status: Int) {
        TtsLogManager.log("ENGINE_INIT", "event='ON_INIT_CALLBACK', statusCode=$status, result='${if (status == TextToSpeech.SUCCESS) "SUCCESS(0)" else "ERROR($status)"}'")

        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                val activeEngine = try { tts.defaultEngine ?: "UNKNOWN" } catch (_: Exception) { "UNKNOWN" }
                TtsLogManager.log("ENGINE_INIT", "event='ACTIVE_ENGINE_RESOLVED', activePackage='$activeEngine'")

                // Enumerate installed TTS engines on device
                try {
                    val availableEngines = tts.engines
                    val engineListStr = availableEngines.joinToString { "${it.name}(${it.label})" }
                    TtsLogManager.log("ENGINE_INIT", "event='INSTALLED_ENGINES_DISCOVERED', count=${availableEngines.size}, list=[$engineListStr]")
                } catch (e: Exception) {
                    TtsLogManager.logError("ENGINE_INIT", "event='QUERY_INSTALLED_ENGINES_FAILED'", e)
                }

                // Locale configuration (Locale.KOREA is prioritized for Samsung TTS compatibility)
                var langResult = tts.setLanguage(Locale.KOREA)
                TtsLogManager.log("LOCALE_SETUP", "locale='Locale.KOREA', resultCode=$langResult")

                if (langResult < 0) {
                    langResult = tts.setLanguage(Locale.KOREAN)
                    TtsLogManager.log("LOCALE_SETUP", "locale='Locale.KOREAN', resultCode=$langResult")
                }

                if (langResult < 0) {
                    langResult = tts.setLanguage(Locale.getDefault())
                    TtsLogManager.log("LOCALE_SETUP", "locale='Locale.getDefault(${Locale.getDefault()})', resultCode=$langResult")
                }

                if (langResult < 0) {
                    TtsLogManager.logError("LOCALE_SETUP", "status='UNSUPPORTED_OR_MISSING_DATA', resultCode=$langResult, engine='${currentEngineType.name}'")
                    // Fallback to Google TTS if System TTS lacks Korean voice pack
                    if (currentEngineType == TtsEngineType.SYSTEM && !hasAttemptedFallback) {
                        TtsLogManager.log("FALLBACK_TRIGGER", "reason='SYSTEM_TTS_MISSING_KOREAN', action='SWITCH_TO_GOOGLE_TTS'")
                        hasAttemptedFallback = true
                        currentEngineType = TtsEngineType.GOOGLE
                        TtsStateHolder.setEngineType(TtsEngineType.GOOGLE)
                        initializeTts(TtsEngineType.GOOGLE, isFallback = true)
                        return
                    }
                } else {
                    TtsLogManager.log("LOCALE_SETUP", "status='SUCCESS', activeLocale='Korean', resultCode=$langResult")
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
                                    if (isLoopEnabled && sentences.isNotEmpty()) {
                                        TtsLogManager.log("PLAYBACK_FLOW", "event='LOOP_RESTARTED', reason='ALL_SENTENCES_COMPLETED', nextIndex=0")
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

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        TtsLogManager.logError("UTTERANCE_EVENT", "event='ON_ERROR', utteranceId='$utteranceId'")
                        handleUtteranceError()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        TtsLogManager.logError("UTTERANCE_EVENT", "event='ON_ERROR', utteranceId='$utteranceId', errorCode=$errorCode")
                        handleUtteranceError()
                    }

                    private fun handleUtteranceError() {
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
                TtsLogManager.log("ENGINE_INIT", "event='READY', isTtsInitialized=true, engine='${currentEngineType.name}'")

                // If playback was requested while initialization was in progress, execute immediately
                if (pendingPlayOnInitialized || (isPlaying && sentences.isNotEmpty())) {
                    TtsLogManager.log("PLAYBACK_FLOW", "event='EXECUTE_PENDING_PLAYBACK', sentenceIndex=$currentSentenceIndex")
                    pendingPlayOnInitialized = false
                    speakCurrentSentence()
                }
            }
        } else {
            isTtsInitialized = false
            TtsLogManager.logError("ENGINE_INIT", "event='INIT_FAILED', statusCode=$status, engine='${currentEngineType.name}'")

            // Bidirectional auto-fallback between System and Google TTS
            if (!hasAttemptedFallback) {
                hasAttemptedFallback = true
                if (currentEngineType == TtsEngineType.SYSTEM) {
                    TtsLogManager.log("FALLBACK_TRIGGER", "reason='SYSTEM_INIT_FAILED', action='SWITCH_TO_GOOGLE_TTS'")
                    currentEngineType = TtsEngineType.GOOGLE
                    TtsStateHolder.setEngineType(TtsEngineType.GOOGLE)
                    initializeTts(TtsEngineType.GOOGLE, isFallback = true)
                } else {
                    TtsLogManager.log("FALLBACK_TRIGGER", "reason='GOOGLE_INIT_FAILED', action='SWITCH_TO_SYSTEM_DEFAULT'")
                    currentEngineType = TtsEngineType.SYSTEM
                    TtsStateHolder.setEngineType(TtsEngineType.SYSTEM)
                    initializeTts(TtsEngineType.SYSTEM, isFallback = true)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TtsLogManager.log("INTENT_DISPATCH", "action='${intent?.action ?: "NULL"}'")

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

                TtsLogManager.log(
                    "USER_ACTION",
                    "event='START_OR_PLAY_TRIGGERED', docId=$docId, title='$title', startIndex=$startIndex, totalSentences=${sentences.size}, targetEngine='${desiredEngine.name}', isTtsReady=$isTtsInitialized"
                )

                // Initialize only if engine changed or not yet initialized
                if (currentEngineType != desiredEngine || !isTtsInitialized) {
                    TtsLogManager.log("PLAYBACK_FLOW", "action='INIT_REQUIRED', desiredEngine='${desiredEngine.name}', currentEngine='${currentEngineType.name}', isTtsInitialized=$isTtsInitialized")
                    currentEngineType = desiredEngine
                    pendingPlayOnInitialized = true
                    initializeTts(desiredEngine)
                } else {
                    TtsLogManager.log("PLAYBACK_FLOW", "action='IMMEDIATE_PLAY', sentenceIndex=$currentSentenceIndex, total=${sentences.size}")
                    speakCurrentSentence()
                }
            }
            ACTION_PAUSE -> {
                TtsLogManager.log("USER_ACTION", "event='PAUSE_TRIGGERED', sentenceIndex=$currentSentenceIndex")
                pausePlayback()
            }
            ACTION_RESUME -> {
                TtsLogManager.log("USER_ACTION", "event='RESUME_TRIGGERED', sentenceIndex=$currentSentenceIndex")
                resumePlayback()
            }
            ACTION_STOP -> {
                TtsLogManager.log("USER_ACTION", "event='STOP_TRIGGERED', sentenceIndex=$currentSentenceIndex")
                stopPlayback()
            }
            ACTION_SEEK_TO -> {
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                if (sentences.isNotEmpty()) {
                    currentSentenceIndex = index.coerceIn(0, sentences.size - 1)
                    persistProgress(currentSentenceIndex)
                    TtsLogManager.log("USER_ACTION", "event='SEEK_TRIGGERED', targetIndex=$currentSentenceIndex, total=${sentences.size}, isPlaying=$isPlaying")
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
                TtsLogManager.log("SETTINGS_UPDATE", "param='SPEECH_RATE', value=$rate")
                TtsStateHolder.setSpeechRate(rate)
                textToSpeech?.setSpeechRate(rate)
            }
            ACTION_SET_PITCH -> {
                val pitch = intent.getFloatExtra(EXTRA_PITCH, 1.0f)
                TtsLogManager.log("SETTINGS_UPDATE", "param='SPEECH_PITCH', value=$pitch")
                TtsStateHolder.setSpeechPitch(pitch)
                textToSpeech?.setPitch(pitch)
            }
            ACTION_SET_LOOP -> {
                val loop = intent.getBooleanExtra(EXTRA_LOOP, true)
                TtsLogManager.log("SETTINGS_UPDATE", "param='LOOP_MODE', value=$loop")
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
                TtsLogManager.log("SETTINGS_UPDATE", "param='ENGINE_SWITCH_REQUEST', targetEngine='${newEngine.name}'")
                TtsStateHolder.setEngineType(newEngine)

                if (currentEngineType != newEngine || !isTtsInitialized) {
                    currentEngineType = newEngine
                    pendingPlayOnInitialized = isPlaying
                    initializeTts(newEngine)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun speakCurrentSentence() {
        if (!isTtsInitialized) {
            TtsLogManager.log("SPEECH_DISPATCH", "status='PENDING_INITIALIZATION', sentenceIndex=$currentSentenceIndex, flag='pendingPlayOnInitialized=true'")
            pendingPlayOnInitialized = true
            return
        }

        if (sentences.isEmpty() || currentSentenceIndex !in sentences.indices) {
            TtsLogManager.logError("SPEECH_DISPATCH", "status='INVALID_SENTENCE_INDEX', sentenceIndex=$currentSentenceIndex, totalSentences=${sentences.size}")
            return
        }

        val chunk = sentences[currentSentenceIndex]
        TtsStateHolder.setCurrentSentence(currentSentenceIndex, chunk.text)
        TtsStateHolder.setPlaybackStatus(PlaybackStatus.PLAYING)
        updateNotification()

        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sentence_$currentSentenceIndex")
        }

        val speakResult = textToSpeech?.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, params, "sentence_$currentSentenceIndex")
        val isSpeakOk = speakResult == TextToSpeech.SUCCESS
        TtsLogManager.log("SPEECH_DISPATCH", "sentenceIndex=$currentSentenceIndex/${sentences.size}, speakResultCode=$speakResult(${if (isSpeakOk) "SUCCESS" else "ERROR"}), textLen=${chunk.text.length}, snippet='${chunk.text.take(30)}'")

        if (!isSpeakOk) {
            TtsLogManager.logError("SPEECH_DISPATCH", "status='SPEAK_REJECTED', resultCode=$speakResult, engine='${currentEngineType.name}'")
            if (currentEngineType == TtsEngineType.SYSTEM && !hasAttemptedFallback) {
                TtsLogManager.log("FALLBACK_TRIGGER", "reason='SYSTEM_TTS_SPEAK_FAILED', action='SWITCH_TO_GOOGLE_TTS'")
                hasAttemptedFallback = true
                currentEngineType = TtsEngineType.GOOGLE
                TtsStateHolder.setEngineType(TtsEngineType.GOOGLE)
                pendingPlayOnInitialized = true
                initializeTts(TtsEngineType.GOOGLE, isFallback = true)
            } else {
                pendingPlayOnInitialized = true
                initializeTts(currentEngineType)
            }
        }
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
        TtsLogManager.log("TTS_PLAY", "재생 완료 처리")
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
            wakeLock?.acquire(2 * 60 * 60 * 1000L)
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
        TtsLogManager.log("SERVICE", "TtsPlaybackService onDestroy 호출")
        releaseWakeLock()
        sentences = emptyList()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        serviceScope.cancel()
        TtsStateHolder.reset()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
