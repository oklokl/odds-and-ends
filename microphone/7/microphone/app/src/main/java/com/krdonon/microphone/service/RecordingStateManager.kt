package com.krdonon.microphone.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RecordingStateManager {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // 🔊 현재 진폭 (0 ~ 32767)
    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    // ✨ 알림바에서 정지했는지 여부
    private val _stoppedFromNotification = MutableStateFlow(false)
    val stoppedFromNotification: StateFlow<Boolean> =
        _stoppedFromNotification.asStateFlow()

    fun onStart() {
        _isRecording.value = true
        _isPaused.value = false
        _stoppedFromNotification.value = false
        _amplitude.value = 0
    }

    fun onPause() {
        _isPaused.value = true
    }

    fun onResume() {
        _isPaused.value = false
    }

    // ✨ fromNotification 여부까지 같이 받도록 변경
    fun onStop(fromNotification: Boolean) {
        _isRecording.value = false
        _isPaused.value = false
        _stoppedFromNotification.value = fromNotification
        _amplitude.value = 0          // 정지 시 0으로 리셋
    }

    // UI 에서 진폭 갱신할 수 있게 노출
    fun updateAmplitude(value: Int) {
        _amplitude.value = value
    }

    // ✨ UI에서 처리 후 다시 false 로 돌려놓기
    fun consumeStoppedFromNotification() {
        _stoppedFromNotification.value = false
    }
}

