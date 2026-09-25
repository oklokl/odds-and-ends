package com.krdonon.microphone.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.krdonon.microphone.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    private object PreferencesKeys {
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val STEREO_RECORDING = booleanPreferencesKey("stereo_recording")
        val BLOCK_CALLS = booleanPreferencesKey("block_calls")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        val STORAGE_LOCATION = stringPreferencesKey("storage_location")
        val MICROPHONE_POSITION = stringPreferencesKey("microphone_position")
        val AUDIO_FORMAT = stringPreferencesKey("audio_format")
        val USE_BLUETOOTH_MIC = booleanPreferencesKey("use_bluetooth_mic")
    }
    
    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            audioQuality = AudioQuality.valueOf(
                preferences[PreferencesKeys.AUDIO_QUALITY] ?: AudioQuality.MEDIUM.name
            ),
            stereoRecording = preferences[PreferencesKeys.STEREO_RECORDING] ?: false,
            blockCallsWhileRecording = preferences[PreferencesKeys.BLOCK_CALLS] ?: false,
            autoPlayNext = preferences[PreferencesKeys.AUTO_PLAY_NEXT] ?: false,
            storageLocation = StorageLocation.valueOf(
                preferences[PreferencesKeys.STORAGE_LOCATION] ?: StorageLocation.INTERNAL.name
            ),
            microphonePosition = MicrophonePosition.valueOf(
                preferences[PreferencesKeys.MICROPHONE_POSITION] ?: MicrophonePosition.BOTTOM.name
            ),
            audioFormat = AudioFormat.valueOf(
                preferences[PreferencesKeys.AUDIO_FORMAT] ?: AudioFormat.M4A.name
            ),
            useBluetoothMic = preferences[PreferencesKeys.USE_BLUETOOTH_MIC] ?: false
        )
    }
    
    suspend fun updateAudioQuality(quality: AudioQuality) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUDIO_QUALITY] = quality.name
        }
    }
    
    suspend fun updateStereoRecording(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.STEREO_RECORDING] = enabled
        }
    }
    
    suspend fun updateBlockCalls(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BLOCK_CALLS] = enabled
        }
    }
    
    suspend fun updateAutoPlayNext(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_PLAY_NEXT] = enabled
        }
    }
    
    suspend fun updateStorageLocation(location: StorageLocation) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.STORAGE_LOCATION] = location.name
        }
    }
    
    suspend fun updateMicrophonePosition(position: MicrophonePosition) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MICROPHONE_POSITION] = position.name
        }
    }
    
    suspend fun updateAudioFormat(format: AudioFormat) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUDIO_FORMAT] = format.name
        }
    }
    
    suspend fun updateUseBluetoothMic(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_BLUETOOTH_MIC] = enabled
        }
    }
}
