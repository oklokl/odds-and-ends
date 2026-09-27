package com.krdondon.read.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.krdondon.read.data.db.AppDatabase
import com.krdondon.read.data.model.TxtDocument
import com.krdondon.read.data.repository.DocumentRepository
import com.krdondon.read.service.PlaybackStatus
import com.krdondon.read.service.TtsEngineType
import com.krdondon.read.service.TtsPlaybackService
import com.krdondon.read.service.TtsStateHolder
import com.krdondon.read.service.TtsUiState
import com.krdondon.read.util.BatteryOptimizationHelper
import com.krdondon.read.util.TxtFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DocumentRepository
    val allDocuments: StateFlow<List<TxtDocument>>
    val ttsState: StateFlow<TtsUiState> = TtsStateHolder.uiState

    private val _activeDocument = MutableStateFlow<TxtDocument?>(null)
    val activeDocument: StateFlow<TxtDocument?> = _activeDocument.asStateFlow()

    private val _messageEvent = MutableStateFlow<String?>(null)
    val messageEvent: StateFlow<String?> = _messageEvent.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    init {
        val dao = AppDatabase.getInstance(application).txtDocumentDao()
        repository = DocumentRepository(dao)

        allDocuments = repository.allDocuments.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        viewModelScope.launch {
            repository.checkAndSeedInitialData()
            // Auto-restore reading positions after build update if available
            kotlinx.coroutines.delay(300L)
            val restoredCount = com.krdondon.read.util.ReadingProgressBackupManager.autoRestoreOnAppStart(
                context = application,
                docs = allDocuments.value,
                repository = repository,
                onSettingsRestored = { rate, pitch, engine, loop ->
                    applyTtsSettings(application, rate, pitch, engine)
                    TtsPlaybackService.setLoop(application, loop)
                }
            )
            if (restoredCount > 0) {
                _messageEvent.value = "이전 읽기 기록(${restoredCount}개 문서)이 자동으로 복구되었습니다."
            }
        }

        // Restore saved TTS Engine preference
        val prefs = application.getSharedPreferences("tts_preferences", Context.MODE_PRIVATE)
        val savedEngineName = prefs.getString("saved_engine", TtsEngineType.SYSTEM.name)
        val savedEngine = try {
            TtsEngineType.valueOf(savedEngineName ?: TtsEngineType.SYSTEM.name)
        } catch (_: Exception) {
            TtsEngineType.SYSTEM
        }
        TtsStateHolder.setEngineType(savedEngine)

        checkBatteryOptimization(application)
    }

    fun clearMessage() {
        _messageEvent.value = null
    }

    fun showMessage(msg: String) {
        _messageEvent.value = msg
    }

    fun checkBatteryOptimization(context: Context) {
        val ignored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        TtsStateHolder.setBatteryOptimizationIgnored(ignored)
    }

    fun requestBatteryOptimizationExemption(context: Context) {
        val requested = BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
        if (requested) {
            _messageEvent.value = "배터리 최적화 예외 설정 화면으로 이동했습니다."
        } else {
            _messageEvent.value = "기기 설정에서 배터리 제한 해제를 설정해주세요."
        }
        checkBatteryOptimization(context)
    }

    fun selectDocument(document: TxtDocument) {
        _activeDocument.value = document
    }

    fun selectDocumentById(id: Long) {
        viewModelScope.launch {
            val doc = repository.getDocumentOnce(id)
            _activeDocument.value = doc
        }
    }

    fun createDocument(title: String, content: String, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val trimmedTitle = title.trim().ifBlank { "제목 없는 문서" }
            val doc = TxtDocument(
                title = trimmedTitle,
                content = content
            )
            val newId = repository.insertDocument(doc)
            val savedDoc = repository.getDocumentOnce(newId)
            _activeDocument.value = savedDoc
            _messageEvent.value = "'$trimmedTitle' 게시물이 생성되었습니다."
            onCreated?.invoke(newId)
        }
    }

    fun updateDocument(id: Long, title: String, content: String) {
        viewModelScope.launch {
            val current = repository.getDocumentOnce(id) ?: return@launch
            val updated = current.copy(
                title = title.trim().ifBlank { current.title },
                content = content,
                updatedAt = System.currentTimeMillis()
            )
            repository.updateDocument(updated)
            _activeDocument.value = updated
            _messageEvent.value = "게시물이 수정되었습니다."
        }
    }

    fun deleteDocument(id: Long) {
        viewModelScope.launch {
            val currentDoc = _activeDocument.value
            if (currentDoc?.id == id) {
                _activeDocument.value = null
                // Stop playback if playing this doc
                if (ttsState.value.documentId == id) {
                    stopTts(getApplication())
                }
            }
            repository.deleteDocument(id)
            _messageEvent.value = "문서가 삭제되었습니다."
        }
    }

    fun importTxtFile(context: Context, uri: Uri, onImported: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val loaded = TxtFileHelper.readTextFromUri(context, uri)
                if (loaded != null && loaded.content.isNotBlank()) {
                    val newDoc = TxtDocument(
                        title = loaded.fileName,
                        content = loaded.content
                    )
                    val newId = repository.insertDocument(newDoc)
                    val inserted = repository.getDocumentOnce(newId)
                    _activeDocument.value = inserted
                    _messageEvent.value = "'${loaded.fileName}' 파일을 성공적으로 불러왔습니다. (${loaded.content.length}자)"
                    onImported?.invoke(newId)
                } else {
                    _messageEvent.value = "텍스트 파일을 읽을 수 없거나 내용이 비어 있습니다."
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _messageEvent.value = "파일을 불러오는 중 오류가 발생했습니다: ${e.localizedMessage}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun saveBookmark(docId: Long, charOffset: Int, sentenceIndex: Int) {
        viewModelScope.launch {
            val current = repository.getDocumentOnce(docId)
            val isAlreadyBookmarked = current?.bookmarkSentenceIndex == sentenceIndex && sentenceIndex >= 0

            if (isAlreadyBookmarked) {
                // 동일한 지점을 다시 누르면 책갈피 취소(해제/삭제)
                repository.updateBookmark(docId, -1, -1)
                val updated = repository.getDocumentOnce(docId)
                if (_activeDocument.value?.id == docId) {
                    _activeDocument.value = updated
                }
                _messageEvent.value = "책갈피가 해제되었습니다."
            } else {
                repository.updateBookmark(docId, charOffset, sentenceIndex)
                val updated = repository.getDocumentOnce(docId)
                if (_activeDocument.value?.id == docId) {
                    _activeDocument.value = updated
                }
                _messageEvent.value = "현재 읽는 위치(${sentenceIndex + 1}번째 문장)가 책갈피로 저장되었습니다 🔖"
            }
            saveAutoSnapshot()
        }
    }

    fun exportReadingBackup(context: Context) {
        viewModelScope.launch {
            val docs = allDocuments.value
            val (_, msg) = com.krdondon.read.util.ReadingProgressBackupManager.saveBackupToDownloads(context, docs, ttsState.value)
            _messageEvent.value = msg
        }
    }

    fun restoreReadingBackupFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val docs = allDocuments.value
            val (_, msg) = com.krdondon.read.util.ReadingProgressBackupManager.restoreFromUri(
                context = context,
                uri = uri,
                docs = docs,
                repository = repository,
                onSettingsRestored = { rate, pitch, engine, loop ->
                    applyTtsSettings(context, rate, pitch, engine)
                    TtsPlaybackService.setLoop(context, loop)
                }
            )
            _messageEvent.value = msg
            val activeId = _activeDocument.value?.id
            if (activeId != null) {
                _activeDocument.value = repository.getDocumentOnce(activeId)
            }
        }
    }

    fun restoreFromLatestBackup(context: Context) {
        viewModelScope.launch {
            val docs = allDocuments.value
            val (_, msg) = com.krdondon.read.util.ReadingProgressBackupManager.restoreFromLatestDownloadsOrSnapshot(
                context = context,
                docs = docs,
                repository = repository,
                onSettingsRestored = { rate, pitch, engine, loop ->
                    applyTtsSettings(context, rate, pitch, engine)
                    TtsPlaybackService.setLoop(context, loop)
                }
            )
            _messageEvent.value = msg
            val activeId = _activeDocument.value?.id
            if (activeId != null) {
                _activeDocument.value = repository.getDocumentOnce(activeId)
            }
        }
    }

    fun resetAllReadingProgress() {
        viewModelScope.launch {
            repository.resetAllReadingProgress()
            val activeId = _activeDocument.value?.id
            if (activeId != null) {
                _activeDocument.value = repository.getDocumentOnce(activeId)
            }
            _messageEvent.value = "모든 문서의 읽기 위치 및 책갈피가 초기화되었습니다."
            saveAutoSnapshot()
        }
    }

    fun saveAutoSnapshot() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val docs = allDocuments.value
                val json = com.krdondon.read.util.ReadingProgressBackupManager.createBackupJson(
                    docs = docs,
                    speechRate = ttsState.value.speechRate,
                    speechPitch = ttsState.value.speechPitch,
                    ttsEngine = ttsState.value.engineType,
                    isLoopEnabled = ttsState.value.isLoopEnabled
                )
                com.krdondon.read.util.ReadingProgressBackupManager.saveInternalSnapshot(getApplication(), json)
            }
        }
    }

    fun playTts(context: Context, doc: TxtDocument, startIndex: Int = 0) {
        TtsPlaybackService.startOrPlay(
            context = context,
            docId = doc.id,
            title = doc.title,
            content = doc.content,
            startIndex = startIndex
        )
    }

    fun pauseTts(context: Context) {
        TtsPlaybackService.pause(context)
    }

    fun resumeTts(context: Context) {
        if (ttsState.value.status == PlaybackStatus.PAUSED) {
            TtsPlaybackService.resume(context)
        } else {
            val doc = _activeDocument.value
            if (doc != null) {
                val startIdx = ttsState.value.currentSentenceIndex
                playTts(context, doc, startIdx)
            }
        }
    }

    fun stopTts(context: Context) {
        TtsPlaybackService.stop(context)
    }

    fun seekToSentence(context: Context, sentenceIndex: Int) {
        TtsPlaybackService.seekTo(context, sentenceIndex)
    }

    fun setSpeechRate(context: Context, rate: Float) {
        TtsPlaybackService.setRate(context, rate)
    }

    fun setSpeechPitch(context: Context, pitch: Float) {
        TtsPlaybackService.setPitch(context, pitch)
    }

    fun toggleKeepScreenOn() {
        val current = ttsState.value.keepScreenOn
        TtsStateHolder.setKeepScreenOn(!current)
        if (!current) {
            _messageEvent.value = "화면 켜짐 유지가 활성화되었습니다."
        } else {
            _messageEvent.value = "화면 켜짐 유지가 해제되었습니다."
        }
    }

    fun toggleLoopMode(context: Context) {
        val current = ttsState.value.isLoopEnabled
        val newLoop = !current
        TtsPlaybackService.setLoop(context, newLoop)
        if (newLoop) {
            _messageEvent.value = "처음부터 다시 읽기(무한 반복)가 켜졌습니다 🔁"
        } else {
            _messageEvent.value = "무한 반복 재생이 해제되었습니다."
        }
    }

    fun applyTtsSettings(context: Context, rate: Float, pitch: Float, engineType: TtsEngineType) {
        setSpeechRate(context, rate)
        setSpeechPitch(context, pitch)
        val prefs = context.getSharedPreferences("tts_preferences", Context.MODE_PRIVATE)
        prefs.edit().putString("saved_engine", engineType.name).apply()
        TtsPlaybackService.setEngine(context, engineType)
        _messageEvent.value = "TTS 설정이 적용되었습니다. (엔진: ${engineType.displayName})"
    }
}
