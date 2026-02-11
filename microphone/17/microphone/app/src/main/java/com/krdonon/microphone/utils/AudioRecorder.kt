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
import android.util.Log
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
            // 혹시 이전에 녹음 중이던 게 있으면 정리
            stopRecording()

            // 분할 파일용 파트 인덱스 (1부터 시작)
            var partIndex = 1

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                // 마이크 소스 설정 (상단/하단 마이크)
                setAudioSource(getAudioSource())

                // 출력 포맷 / 코덱 설정
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
                            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                        }
                    }
                }

                // 🔐 분할 기준: 3.7GB
                try {
                    setMaxFileSize(MAX_PART_FILE_SIZE_BYTES)
                } catch (e: Exception) {
                    Log.w("AudioRecorder", "setMaxFileSize failed: ${e.message}")
                }

                // 📂 첫 번째 파트는 호출자가 넘겨준 파일
                setOutputFile(outputFile.absolutePath)

                // ⚙️ 용량이 꽉 차면 다음 파일로 자동 분할
                setOnInfoListener { mr, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        Log.w("AudioRecorder", "Max file size reached, creating next part")

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            try {
                                partIndex += 1
                                val nextFile = createNextPartFile(outputFile, partIndex)
                                mr.setNextOutputFile(nextFile)
                                Log.w(
                                    "AudioRecorder",
                                    "Switched to next part file: ${nextFile.absolutePath}"
                                )
                            } catch (e: Exception) {
                                Log.e("AudioRecorder", "Failed to switch to next part file", e)
                                try {
                                    mr.stop()
                                } catch (e2: Exception) {
                                    Log.e("AudioRecorder", "Error stopping after failure", e2)
                                }
                                _recordingState.value = RecordingState.Error(
                                    "파일이 너무 커져서 녹음이 중지되었습니다."
                                )
                            }
                        } else {
                            // 구형 기기: 분할 불가 → 녹음 중지
                            Log.w(
                                "AudioRecorder",
                                "setNextOutputFile not supported on this device, stopping"
                            )
                            try {
                                mr.stop()
                            } catch (e: Exception) {
                                Log.e("AudioRecorder", "Error stopping on max size", e)
                            }
                            _recordingState.value = RecordingState.Error(
                                "이 기기에서는 매우 긴 녹음을 나누어 저장할 수 없어 녹음이 중지되었습니다."
                            )
                        }
                    }
                }

                // 채널/음질 설정
                setAudioChannels(if (settings.stereoRecording) 2 else 1)
                setAudioEncodingBitRate(settings.audioQuality.bitrate)
                setAudioSamplingRate(settings.audioQuality.sampleRate)

                prepare()
                start()
            }

            isPaused = false
            startTime = System.currentTimeMillis()
            totalPausedTime = 0
            _recordingState.value = RecordingState.Recording

            // 진폭 / 타이머 시작
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
                    // 파일 시스템에 데이터를 flush하도록 강제
                    Thread.sleep(100) // 짧은 딜레이 추가
                    isPaused = true
                    pauseTime = System.currentTimeMillis()
                    _recordingState.value = RecordingState.Paused
                    amplitudeUpdateJob?.cancel()
                    timerJob?.cancel()
                    Log.d("AudioRecorder", "Recording paused successfully")
                } catch (e: Exception) {
                    Log.e("AudioRecorder", "Failed to pause recording", e)
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
                    Log.d("AudioRecorder", "Recording resumed successfully")
                } catch (e: Exception) {
                    Log.e("AudioRecorder", "Failed to resume recording", e)
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
                    try {
                        stop()
                        Log.d("AudioRecorder", "Recording stopped successfully")
                    } catch (e: Exception) {
                        Log.e("AudioRecorder", "Stop failed", e)
                    }
                    release()
                }
            }
            _recordingState.value = RecordingState.Stopped
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error in stopRecording", e)
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

    /**
     * 분할 녹음용 다음 캐시 파일 생성
     *
     * baseFile: 첫 번째 파트 파일 (예: temp_recording_1700.m4a)
     * index   : 2, 3, 4 ...
     *
     * 결과 예:
     *   index=2 -> temp_recording_1700_2.m4a
     *   index=3 -> temp_recording_1700_3.m4a
     */
    private fun createNextPartFile(baseFile: File, index: Int): File {
        val parent = baseFile.parentFile
        val nameWithoutExt = baseFile.nameWithoutExtension   // temp_recording_1700
        val ext = baseFile.extension                         // m4a
        val newName = "${nameWithoutExt}_${index}.$ext"
        return File(parent, newName)
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

    companion object {
        private const val MAX_PART_FILE_SIZE_BYTES = 3_700_000_000L // 3.7GB
    }

}