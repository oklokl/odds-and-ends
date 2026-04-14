package com.krdonon.metronome

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MetronomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MetronomeState())
    val state: StateFlow<MetronomeState> = _state.asStateFlow()

    private val _soundSetNames = MutableStateFlow<List<String>>(emptyList())
    val soundSetNames: StateFlow<List<String>> = _soundSetNames.asStateFlow()

    private var metronomeService: MetronomeService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MetronomeService.LocalBinder
            metronomeService = binder.getService()
            bound = true
            syncStateFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            metronomeService = null
        }
    }

    init {
        bindService()
        startStateSync()
    }

    private fun bindService() {
        val intent = Intent(getApplication(), MetronomeService::class.java)
        getApplication<Application>().startService(intent)
        getApplication<Application>().bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun startStateSync() {
        viewModelScope.launch {
            while (true) {
                if (bound && _state.value.isPlaying) {
                    syncStateFromService()
                }
                delay(50) // 20 FPS 업데이트
            }
        }
    }

    private fun syncStateFromService() {
        metronomeService?.let { service ->
            _state.value = service.getState()
            if (_soundSetNames.value.isEmpty()) {
                _soundSetNames.value = service.getSoundSetNames()
            }
        }
    }

    fun togglePlayPause() {
        val newState = _state.value.copy(isPlaying = !_state.value.isPlaying)
        updateState(newState)
    }

    fun setBpm(bpm: Int) {
        val clampedBpm = bpm.coerceIn(40, 240)
        val newState = _state.value.copy(bpm = clampedBpm)
        updateState(newState)
    }

    fun setBeatsPerMeasure(beats: Int) {
        val clampedBeats = beats.coerceIn(1, 16)
        val newState = _state.value.copy(
            beatsPerMeasure = clampedBeats,
            currentBeat = 0,
            subBeatIndex = 0          // ★ 추가
        )
        updateState(newState)
    }

    fun setBeatUnit(unit: Int) {
        val validUnits = listOf(1, 2, 4, 8, 16)
        val clampedUnit = validUnits.minByOrNull { kotlin.math.abs(it - unit) } ?: 4
        val newState = _state.value.copy(
            beatUnit = clampedUnit,
            currentBeat = 0,
            subBeatIndex = 0          // ★ 추가
        )
        updateState(newState)
    }


    fun nextSoundSet() {
        metronomeService?.nextSoundSet()
        // ✅ 정지 상태에서도 UI 텍스트가 즉시 바뀌도록 동기화
        syncStateFromService()
    }

    fun setSoundSetIndex(index: Int) {
        metronomeService?.setSoundSetIndex(index)
        syncStateFromService()
    }

    fun getCurrentSoundSet(): String = _state.value.soundSetName

    fun toggleVibrationMode() {
        val current = _state.value
        val newState = current.copy(isVibrationMode = !current.isVibrationMode)

        // 화면 상태 업데이트
        _state.value = newState

        // 서비스에도 새 상태 전달하는 부분이 이미 있다면 여기서 호출
        metronomeService?.updateState(newState)
    }

    fun toggleKeepScreenOn() {
        val current = _state.value
        val newState = current.copy(keepScreenOn = !current.keepScreenOn)
        updateState(newState)
    }


    private fun updateState(newState: MetronomeState) {
        _state.value = newState
        metronomeService?.updateState(newState)
    }

    override fun onCleared() {
        if (bound) {
            getApplication<Application>().unbindService(serviceConnection)
            bound = false
        }
        super.onCleared()
    }

}
