package com.krdonon.microphone.service

import com.krdonon.microphone.data.model.RecordingFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackStateManager {

    // 현재 재생 중인 파일 정보
    private val _currentRecording = MutableStateFlow<RecordingFile?>(null)
    val currentRecording: StateFlow<RecordingFile?> = _currentRecording.asStateFlow()

    // 재생 여부
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // 현재 재생 위치 (밀리초)
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    // 전체 길이 (밀리초)
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    // 구간 반복 (A-B Repeat) 상태
    private val _repeatA = MutableStateFlow<Long?>(null)
    val repeatA: StateFlow<Long?> = _repeatA.asStateFlow()

    private val _repeatB = MutableStateFlow<Long?>(null)
    val repeatB: StateFlow<Long?> = _repeatB.asStateFlow()

    private val _isRepeatActive = MutableStateFlow(false)
    val isRepeatActive: StateFlow<Boolean> = _isRepeatActive.asStateFlow()

    fun onStart(recording: RecordingFile, totalDuration: Long = 0L) {
        _currentRecording.value = recording
        _isPlaying.value = true
        _currentPosition.value = 0L
        _duration.value = totalDuration
        _repeatA.value = null
        _repeatB.value = null
        _isRepeatActive.value = false
    }

    fun updatePosition(position: Long) {
        _currentPosition.value = position
    }

    fun updateDuration(duration: Long) {
        _duration.value = duration
    }

    fun updateIsPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun setRepeatA(position: Long) {
        _repeatA.value = position
        // 만약 기존 B가 A보다 앞서있으면 B 초기화
        val b = _repeatB.value
        if (b != null && b <= position) {
            _repeatB.value = null
            _isRepeatActive.value = false
        }
    }

    fun setRepeatB(position: Long) {
        val a = _repeatA.value ?: 0L
        if (position > a) {
            _repeatB.value = position
            _isRepeatActive.value = true
        }
    }

    fun clearRepeat() {
        _repeatA.value = null
        _repeatB.value = null
        _isRepeatActive.value = false
    }

    fun reset() {
        _currentRecording.value = null
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _repeatA.value = null
        _repeatB.value = null
        _isRepeatActive.value = false
    }
}
