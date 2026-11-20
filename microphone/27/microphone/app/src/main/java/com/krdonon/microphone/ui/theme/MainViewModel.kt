package com.krdonon.microphone.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krdonon.microphone.data.model.AppSettings
import com.krdonon.microphone.data.model.Category
import com.krdonon.microphone.data.model.RecordingFile
import com.krdonon.microphone.data.model.MicrophonePosition
import com.krdonon.microphone.data.model.AudioQuality
import com.krdonon.microphone.data.model.AudioFormat
import com.krdonon.microphone.data.model.StorageLocation
import com.krdonon.microphone.data.repository.CategoryRepository
import com.krdonon.microphone.data.repository.RecordingRepository
import com.krdonon.microphone.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(context: Context) : ViewModel() {

    private val settingsRepository = SettingsRepository(context)
    private val recordingRepository = RecordingRepository(context)
    private val categoryRepository = CategoryRepository(context)
    private val sharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, sharingStarted, AppSettings())

    val recordings: StateFlow<List<RecordingFile>> = recordingRepository.recordingsFlow
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val trashRecordings: StateFlow<List<RecordingFile>> = recordingRepository.trashFlow
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val categories: StateFlow<List<Category>> = categoryRepository.categoriesFlow
        .stateIn(viewModelScope, sharingStarted, emptyList())

    private val _currentRecording = MutableStateFlow<File?>(null)
    val currentRecording: StateFlow<File?> = _currentRecording.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // ▼ 현재 선택된 카테고리 필터
    private val _selectedCategoryFilter = MutableStateFlow<String?>(null)
    val selectedCategoryFilter: StateFlow<String?> = _selectedCategoryFilter.asStateFlow()

    fun setSelectedCategoryFilter(categoryName: String?) {
        _selectedCategoryFilter.value = categoryName
    }

    init {
        loadRecordings()
    }

    fun loadRecordings() {
        viewModelScope.launch {
            recordingRepository.loadRecordings()
        }
    }

    // MP3 변환 진행률 (0.0f ~ 1.0f, null이면 변환 중 아님)
    private val _conversionProgress = MutableStateFlow<Float?>(null)
    val conversionProgress: StateFlow<Float?> = _conversionProgress.asStateFlow()

    fun saveRecording(
        tempFile: File,
        fileName: String,
        category: String,
        onComplete: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            val format = settings.value.audioFormat
            val bitrateKbps = settings.value.audioQuality.bitrate / 1000
            if (format == AudioFormat.MP3) {
                _conversionProgress.value = 0f
            }
            recordingRepository.saveRecording(
                tempFile = tempFile,
                fileName = fileName,
                category = category,
                targetFormat = format,
                targetBitrateKbps = bitrateKbps,
                onProgress = { p ->
                    _conversionProgress.value = p
                }
            )
            _conversionProgress.value = null
            _currentRecording.value = null
            onComplete?.invoke()
        }
    }

    fun deleteRecording(recording: RecordingFile, moveToTrash: Boolean = false) {
        viewModelScope.launch {
            recordingRepository.deleteRecording(recording, moveToTrash)
        }
    }

    // ★ 복원 함수 – 이름을 HomeScreen 과 똑같이 restoreRecording 으로 통일
    fun restoreRecording(recording: RecordingFile) {
        viewModelScope.launch {
            // 예전: recordingRepository.restoreRecordingFromTrash(recording)
            recordingRepository.restoreFromTrash(recording)
        }
    }

    // 녹음 파일을 공개 Music 폴더로 내보내기
    fun exportRecordingToMusic(recording: RecordingFile, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val success = recordingRepository.exportRecordingToMusic(recording)
            onResult?.invoke(success)
        }
    }

    // 녹음 파일을 공개 Download 폴더로 내보내기
    fun exportRecordingToDownloads(recording: RecordingFile, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val success = recordingRepository.exportRecordingToDownloads(recording)
            onResult?.invoke(success)
        }
    }




    fun emptyTrash() {
        viewModelScope.launch {
            recordingRepository.emptyTrash()
        }
    }

    fun renameRecording(recording: RecordingFile, newName: String) {
        viewModelScope.launch {
            recordingRepository.renameRecording(recording, newName)
        }
    }

    fun addCategory(name: String) {
        viewModelScope.launch {
            categoryRepository.addCategory(name)
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            categoryRepository.deleteCategory(categoryId)
        }
    }

    // 카테고리 이름 변경 + 녹음 파일들에 들어 있는 이름도 같이 변경
    fun renameCategory(categoryId: String, newName: String) {
        viewModelScope.launch {
            val oldCategoryName = categories.value
                .firstOrNull { it.id == categoryId }
                ?.name

            if (oldCategoryName == null || oldCategoryName == newName) {
                return@launch
            }

            categoryRepository.renameCategory(categoryId, newName)
            recordingRepository.updateCategoryName(oldCategoryName, newName)

            if (selectedCategoryFilter.value == oldCategoryName) {
                _selectedCategoryFilter.value = newName
            }
        }
    }

    fun moveRecordingToCategory(recordingId: String, categoryName: String) {
        viewModelScope.launch {
            recordingRepository.updateRecordingCategory(recordingId, categoryName)
        }
    }

    fun generateFileName(): String {
        return recordingRepository.generateFileName()
    }

    fun setCurrentRecording(file: File?) {
        _currentRecording.value = file
    }

    fun setRecordingState(isRecording: Boolean) {
        _isRecording.value = isRecording
    }

    // ───── Settings 업데이트 함수들 ─────

    fun updateMicrophonePosition(position: MicrophonePosition) {
        viewModelScope.launch {
            settingsRepository.updateMicrophonePosition(position)
        }
    }

    fun updateAudioQuality(quality: AudioQuality) {
        viewModelScope.launch {
            settingsRepository.updateAudioQuality(quality)
        }
    }

    fun updateAudioFormat(format: AudioFormat) {
        viewModelScope.launch {
            settingsRepository.updateAudioFormat(format)
        }
    }

    fun updateStereoRecording(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateStereoRecording(enabled)
        }
    }

    fun updateBlockCalls(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateBlockCalls(enabled)
        }
    }

    fun updateAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoPlayNext(enabled)
        }
    }

    fun updateStorageLocation(location: StorageLocation) {
        viewModelScope.launch {
            settingsRepository.updateStorageLocation(location)
        }
    }

    fun updateUseBluetoothMic(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateUseBluetoothMic(enabled)
        }
    }
}
