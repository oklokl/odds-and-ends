package com.krdonon.microphone.utils

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import com.krdonon.microphone.data.model.AppSettings
import com.krdonon.microphone.data.model.AudioQuality
import com.krdonon.microphone.data.model.MicrophonePosition
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.ByteBuffer

class AudioRecorder(
    private val context: Context,
    private val settings: AppSettings
) {
    private var mediaRecorder: MediaRecorder? = null
    private var isPaused = false
    private var startTime: Long = 0
    private var pauseTime: Long = 0
    private var totalPausedTime: Long = 0
    
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState
    
    private val _currentAmplitude = MutableStateFlow(0)
    val currentAmplitude: StateFlow<Int> = _currentAmplitude
    
    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime: StateFlow<Long> = _elapsedTime
    
    private var amplitudeUpdateJob: Job? = null
    private var timerJob: Job? = null
    
    sealed class RecordingState {
        object Idle : RecordingState()
        object Recording : RecordingState()
        object Paused : RecordingState()
        object Stopped : RecordingState()
        data class Error(val message: String) : RecordingState()
    }
    
    fun startRecording(outputFile: File): Boolean {
        return try {
            stopRecording() // 기존 녹음 정리
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                // 마이크 소스 설정 (상단/하단 마이크 선택)
                setAudioSource(getAudioSource())
                
                // 출력 포맷 설정
                when (settings.audioFormat) {
                    com.krdonon.microphone.data.model.AudioFormat.M4A -> {
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    }
                    com.krdonon.microphone.data.model.AudioFormat.MP3 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        } else {
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        }
                    }
                }
                
                // 채널 설정 (스테레오/모노)
                setAudioChannels(if (settings.stereoRecording) 2 else 1)
                
                // 음질 설정
                setAudioEncodingBitRate(settings.audioQuality.bitrate)
                setAudioSamplingRate(settings.audioQuality.sampleRate)
                
                setOutputFile(outputFile.absolutePath)
                
                prepare()
                start()
            }
            
            isPaused = false
            startTime = System.currentTimeMillis()
            totalPausedTime = 0
            _recordingState.value = RecordingState.Recording
            
            // 진폭 측정 시작
            startAmplitudeUpdates()
            startTimer()
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            _recordingState.value = RecordingState.Error(e.message ?: "녹음 시작 실패")
            false
        }
    }
    
    fun pauseRecording() {
        if (_recordingState.value is RecordingState.Recording) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    mediaRecorder?.pause()
                    isPaused = true
                    pauseTime = System.currentTimeMillis()
                    _recordingState.value = RecordingState.Paused
                    amplitudeUpdateJob?.cancel()
                    timerJob?.cancel()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    fun resumeRecording() {
        if (_recordingState.value is RecordingState.Paused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    mediaRecorder?.resume()
                    totalPausedTime += System.currentTimeMillis() - pauseTime
                    isPaused = false
                    _recordingState.value = RecordingState.Recording
                    startAmplitudeUpdates()
                    startTimer()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    fun stopRecording(): File? {
        var outputFile: File? = null
        try {
            if (_recordingState.value is RecordingState.Recording || 
                _recordingState.value is RecordingState.Paused) {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                outputFile = File(mediaRecorder.toString()) // 실제로는 파일 경로 저장 필요
            }
            _recordingState.value = RecordingState.Stopped
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
            amplitudeUpdateJob?.cancel()
            timerJob?.cancel()
            _currentAmplitude.value = 0
            _elapsedTime.value = 0
        }
        return outputFile
    }
    
    private fun getAudioSource(): Int {
        return when (settings.microphonePosition) {
            MicrophonePosition.TOP -> {
                // Android에서 상단 마이크를 명시적으로 선택하는 방법
                // MediaRecorder.AudioSource.CAMCORDER는 주로 후면 카메라 쪽 마이크 (상단)를 사용
                MediaRecorder.AudioSource.CAMCORDER
            }
            MicrophonePosition.BOTTOM -> {
                // 기본 마이크 (하단)
                MediaRecorder.AudioSource.MIC
            }
        }
    }
    
    private fun startAmplitudeUpdates() {
        amplitudeUpdateJob?.cancel()
        amplitudeUpdateJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && _recordingState.value is RecordingState.Recording) {
                try {
                    val amplitude = mediaRecorder?.maxAmplitude ?: 0
                    _currentAmplitude.value = amplitude
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(100) // 100ms마다 업데이트
            }
        }
    }
    
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && _recordingState.value is RecordingState.Recording) {
                val elapsed = System.currentTimeMillis() - startTime - totalPausedTime
                _elapsedTime.value = elapsed
                delay(100)
            }
        }
    }
    
    fun release() {
        stopRecording()
        amplitudeUpdateJob?.cancel()
        timerJob?.cancel()
    }
}
