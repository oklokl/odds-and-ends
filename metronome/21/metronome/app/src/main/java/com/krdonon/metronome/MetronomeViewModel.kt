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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MetronomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MetronomeState())
    val state: StateFlow<MetronomeState> = _state.asStateFlow()

    private val _soundSetNames = MutableStateFlow<List<String>>(emptyList())
    val soundSetNames: StateFlow<List<String>> = _soundSetNames.asStateFlow()

    private var metronomeService: MetronomeService? = null
    private var bound = false

    @Volatile
    private var uiVisible = false

    /** 서비스 연결 전에 사용자가 상태를 바꾼 경우 연결 직후 전달하기 위한 플래그 */
    private var pendingStateForService = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MetronomeService.LocalBinder ?: return
            metronomeService = binder.getService()
            bound = true

            if (pendingStateForService) {
                metronomeService?.updateState(_state.value)
                pendingStateForService = false
            }
            syncStateFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            metronomeService = null
        }
    }

    init {
        // 단순히 앱을 열었다는 이유만으로 started service를 남기지 않습니다.
        // 화면과 연결할 때는 bind만 하고, 실제 재생 시에만 started service로 승격합니다.
        bindService()
        startStateSync()
    }

    private fun serviceIntent(): Intent =
        Intent(getApplication(), MetronomeService::class.java)

    private fun bindService() {
        getApplication<Application>().bindService(
            serviceIntent(),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun startServiceForPlayback() {
        // 사용자 조작으로 앱이 전면에 있는 시점에 호출됩니다.
        // Service 내부 startMetronome()이 즉시 foreground service로 승격합니다.
        getApplication<Application>().startService(serviceIntent())
    }

    private fun stopStartedServiceIfIdle() {
        getApplication<Application>().stopService(serviceIntent())
    }

    private fun startStateSync() {
        viewModelScope.launch {
            while (isActive) {
                if (uiVisible && bound && _state.value.isPlaying) {
                    syncStateFromService()
                }
                delay(50) // 재생 중 원형 비주얼라이저용 20 FPS 업데이트
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

    /** 화면이 보일 때만 고주기 UI 상태 동기화를 수행합니다. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible && bound) {
            syncStateFromService()
        }
    }

    fun togglePlayPause() {
        val newState = _state.value.copy(isPlaying = !_state.value.isPlaying)
        updateState(newState)
    }

    fun setBpm(bpm: Int) {
        val clampedBpm = bpm.coerceIn(40, 240)
        updateState(_state.value.copy(bpm = clampedBpm))
    }

    fun setBeatsPerMeasure(beats: Int) {
        val clampedBeats = beats.coerceIn(1, 16)
        updateState(
            _state.value.copy(
                beatsPerMeasure = clampedBeats,
                currentBeat = 0,
                subBeatIndex = 0
            )
        )
    }

    fun setBeatUnit(unit: Int) {
        val validUnits = listOf(1, 2, 4, 8, 16)
        val clampedUnit = validUnits.minByOrNull { kotlin.math.abs(it - unit) } ?: 4
        updateState(
            _state.value.copy(
                beatUnit = clampedUnit,
                currentBeat = 0,
                subBeatIndex = 0
            )
        )
    }

    fun nextSoundSet() {
        metronomeService?.nextSoundSet()
        syncStateFromService()
    }

    fun setSoundSetIndex(index: Int) {
        metronomeService?.setSoundSetIndex(index)
        syncStateFromService()
    }

    fun getCurrentSoundSet(): String = _state.value.soundSetName

    fun toggleVibrationMode() {
        updateState(_state.value.copy(isVibrationMode = !_state.value.isVibrationMode))
    }

    fun toggleKeepScreenOn() {
        updateState(_state.value.copy(keepScreenOn = !_state.value.keepScreenOn))
    }

    private fun updateState(newState: MetronomeState) {
        val previousState = _state.value

        if (!previousState.isPlaying && newState.isPlaying) {
            startServiceForPlayback()
        }

        _state.value = newState

        val service = metronomeService
        if (service != null) {
            service.updateState(newState)
        } else {
            pendingStateForService = true
        }

        if (previousState.isPlaying && !newState.isPlaying) {
            // 재생이 끝난 서비스가 백그라운드에 started 상태로 남지 않게 합니다.
            stopStartedServiceIfIdle()
        }
    }

    override fun onCleared() {
        if (bound) {
            getApplication<Application>().unbindService(serviceConnection)
            bound = false
        }

        // 재생하지 않는 상태라면 서비스가 남아 있을 이유가 없습니다.
        if (!_state.value.isPlaying) {
            stopStartedServiceIfIdle()
        }

        metronomeService = null
        super.onCleared()
    }
}
