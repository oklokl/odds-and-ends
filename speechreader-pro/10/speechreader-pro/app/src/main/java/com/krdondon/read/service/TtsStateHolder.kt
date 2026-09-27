package com.krdondon.read.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackStatus {
    IDLE, PLAYING, PAUSED, STOPPED
}

enum class TtsEngineType(val packageName: String?, val displayName: String, val description: String) {
    SYSTEM(null, "시스템", "시스템 기본 엔진 (삼성 TTS 등)"),
    GOOGLE("com.google.android.tts", "구글", "구글 음성 서비스 (Google TTS)")
}

data class TtsUiState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val documentId: Long? = null,
    val title: String = "",
    val currentSentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val currentSentenceText: String = "",
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val keepScreenOn: Boolean = true,
    val isBatteryOptimizationIgnored: Boolean = false,
    val isLoopEnabled: Boolean = true,
    val engineType: TtsEngineType = TtsEngineType.SYSTEM
)

object TtsStateHolder {
    private val _uiState = MutableStateFlow(TtsUiState())
    val uiState: StateFlow<TtsUiState> = _uiState.asStateFlow()

    fun updateState(transform: (TtsUiState) -> TtsUiState) {
        _uiState.value = transform(_uiState.value)
    }

    fun setPlaybackStatus(status: PlaybackStatus) {
        _uiState.value = _uiState.value.copy(status = status)
    }

    fun setDocumentInfo(docId: Long, title: String, totalSentences: Int) {
        _uiState.value = _uiState.value.copy(
            documentId = docId,
            title = title,
            totalSentences = totalSentences
        )
    }

    fun setCurrentSentence(index: Int, text: String) {
        _uiState.value = _uiState.value.copy(
            currentSentenceIndex = index,
            currentSentenceText = text
        )
    }

    fun setSpeechRate(rate: Float) {
        _uiState.value = _uiState.value.copy(speechRate = rate)
    }

    fun setSpeechPitch(pitch: Float) {
        _uiState.value = _uiState.value.copy(speechPitch = pitch)
    }

    fun setKeepScreenOn(keep: Boolean) {
        _uiState.value = _uiState.value.copy(keepScreenOn = keep)
    }

    fun setBatteryOptimizationIgnored(ignored: Boolean) {
        _uiState.value = _uiState.value.copy(isBatteryOptimizationIgnored = ignored)
    }

    fun setLoopEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isLoopEnabled = enabled)
    }

    fun setEngineType(engineType: TtsEngineType) {
        _uiState.value = _uiState.value.copy(engineType = engineType)
    }

    fun reset() {
        _uiState.value = TtsUiState(
            keepScreenOn = _uiState.value.keepScreenOn,
            isBatteryOptimizationIgnored = _uiState.value.isBatteryOptimizationIgnored,
            isLoopEnabled = _uiState.value.isLoopEnabled,
            engineType = _uiState.value.engineType
        )
    }
}
