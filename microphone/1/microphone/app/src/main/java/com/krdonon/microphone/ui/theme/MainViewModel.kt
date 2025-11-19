package com.krdonon.microphone.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krdonon.microphone.data.model.AppSettings
import com.krdonon.microphone.data.model.Category
import com.krdonon.microphone.data.model.RecordingFile
import com.krdonon.microphone.data.repository.CategoryRepository
import com.krdonon.microphone.data.repository.RecordingRepository
import com.krdonon.microphone.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(context: Context) : ViewModel() {
    
    private val settingsRepository = SettingsRepository(context)
    private val recordingRepository = RecordingRepository(context)
    private val categoryRepository = CategoryRepository(context)
    
    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    
    val recordings: StateFlow<List<RecordingFile>> = recordingRepository.recordingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    val categories: StateFlow<List<Category>> = categoryRepository.categoriesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    private val _currentRecording = MutableStateFlow<File?>(null)
    val currentRecording: StateFlow<File?> = _currentRecording.asStateFlow()
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    init {
        loadRecordings()
    }
    
    fun loadRecordings() {
        viewModelScope.launch {
            recordingRepository.loadRecordings()
        }
    }
    
    fun saveRecording(tempFile: File, fileName: String, category: String) {
        viewModelScope.launch {
            recordingRepository.saveRecording(tempFile, fileName, category)
            _currentRecording.value = null
        }
    }
    
    fun deleteRecording(recording: RecordingFile) {
        viewModelScope.launch {
            recordingRepository.deleteRecording(recording)
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
    
    fun generateFileName(): String {
        return recordingRepository.generateFileName()
    }
    
    fun setCurrentRecording(file: File?) {
        _currentRecording.value = file
    }
    
    fun setRecordingState(isRecording: Boolean) {
        _isRecording.value = isRecording
    }
    
    // Settings updates
    fun updateMicrophonePosition(position: com.krdonon.microphone.data.model.MicrophonePosition) {
        viewModelScope.launch {
            settingsRepository.updateMicrophonePosition(position)
        }
    }
    
    fun updateAudioQuality(quality: com.krdonon.microphone.data.model.AudioQuality) {
        viewModelScope.launch {
            settingsRepository.updateAudioQuality(quality)
        }
    }
    
    fun updateAudioFormat(format: com.krdonon.microphone.data.model.AudioFormat) {
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
    
    fun updateStorageLocation(location: com.krdonon.microphone.data.model.StorageLocation) {
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
