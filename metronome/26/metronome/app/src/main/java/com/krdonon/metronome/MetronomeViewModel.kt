package com.krdonon.metronome

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
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
            val s = binder.getService()
            metronomeService = s
            bound = true

            // 서비스 상태 변경 리스너 등록
            s.onStateChangedListener = { newState ->
                if (_state.value != newState) {
                    _state.value = newState
                }
            }

            if (pendingStateForService) {
                s.updateState(_state.value)
                pendingStateForService = false
            }
            syncStateFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            metronomeService?.onStateChangedListener = null
            bound = false
            metronomeService = null
        }
    }

    // 알림창의 "중지" 버튼 클릭 시 브로드캐스트 수신
    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MetronomeService.ACTION_METRONOME_STOPPED) {
                _state.value = _state.value.copy(
                    isPlaying = false,
                    currentBeat = 0,
                    subBeatIndex = 0
                )
            }
        }
    }
    private var receiverRegistered = false

    init {
        bindService()
        registerStoppedReceiver()
        startStateSync()
    }

    private fun registerStoppedReceiver() {
        val filter = IntentFilter(MetronomeService.ACTION_METRONOME_STOPPED)
        ContextCompat.registerReceiver(
            getApplication<Application>(),
            stoppedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
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
            val serviceState = service.getState()
            if (_state.value != serviceState) {
                _state.value = serviceState
            }
            if (_soundSetNames.value.isEmpty()) {
                val names = service.getSoundSetNames()
                if (names.isNotEmpty()) {
                    _soundSetNames.value = names
                }
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
        val clampedBpm = bpm.coerceIn(40, 440)
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
            stopStartedServiceIfIdle()
        }
    }

    override fun onCleared() {
        if (receiverRegistered) {
            try {
                getApplication<Application>().unregisterReceiver(stoppedReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }

        metronomeService?.onStateChangedListener = null

        if (bound) {
            getApplication<Application>().unbindService(serviceConnection)
            bound = false
        }

        if (!_state.value.isPlaying) {
            stopStartedServiceIfIdle()
        }

        metronomeService = null
        super.onCleared()
    }
}
