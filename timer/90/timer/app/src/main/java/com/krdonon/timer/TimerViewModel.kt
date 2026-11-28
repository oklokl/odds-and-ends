package com.krdonon.timer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TimerViewModel(app: Application) : AndroidViewModel(app) {

    data class ExtraTimer(
        val id: String = UUID.randomUUID().toString(),
        var label: String = "타이머",
        var remainingMs: Long = 0L,   // 남은 시간 (일시정지 시에도 유지)
        var running: Boolean = false, // 시작 버튼을 눌러야만 true
        var totalDurationMs: Long = 0L, // 알림 괄호 표기용: 최초 설정 시간(고정)
        var locked: Boolean = false,  // true면 알람 완료 후 삭제 대신 원래 시간으로 복원
    )

    data class CustomPreset(
        val id: String = UUID.randomUUID().toString(),
        val label: String = "",
        val durationMs: Long = 0L,
        val isClockTarget: Boolean = false,
        val targetHour24: Int = 0,
        val targetMinute: Int = 0,
        val isTomorrow: Boolean = false,
    )

    private val prefs = app.getSharedPreferences("timer_state_v2", 0)

    // 메인 타이머
    // - running=true 이면 실제 동작 중인 종료시각(mainEndAtMs)을 사용
    // - running=false 이면 사용자가 맞춰 둔 미시작 UI 초안(mainDraft*)을 사용
    private val _mainEndAtMs = MutableLiveData<Long?>(null)
    val mainEndAtMs: LiveData<Long?> = _mainEndAtMs
    private val _mainRunning = MutableLiveData(false)
    val mainRunning: LiveData<Boolean> = _mainRunning
    private val _mainDraftRemainingMs = MutableLiveData(0L)
    val mainDraftRemainingMs: LiveData<Long> = _mainDraftRemainingMs
    private val _mainDraftTargetAtMs = MutableLiveData<Long?>(null)
    val mainDraftTargetAtMs: LiveData<Long?> = _mainDraftTargetAtMs
    private val _mainLabel = MutableLiveData("")
    val mainLabel: LiveData<String> = _mainLabel
    private val _activePresetLabel = MutableLiveData("")
    val activePresetLabel: LiveData<String> = _activePresetLabel
    private val _mainLocked = MutableLiveData(false)
    val mainLocked: LiveData<Boolean> = _mainLocked
    private val _mainConfiguredDurationMs = MutableLiveData(0L)
    val mainConfiguredDurationMs: LiveData<Long> = _mainConfiguredDurationMs

    // 보조 타이머들
    private val _extras = MutableLiveData<List<ExtraTimer>>(emptyList())
    val extras: LiveData<List<ExtraTimer>> = _extras

    // 사용자 등록 사각 시간 버튼들
    private val _customPresets = MutableLiveData<List<CustomPreset>>(emptyList())
    val customPresets: LiveData<List<CustomPreset>> = _customPresets

    init {
        restore()
        validateState()  // 🔹 복원 후 상태 검증
    }

    // 🔹 상태 검증: 이미 지난 시간은 제거 (단, 잠긴 타이머는 원래 시간으로 복원)
    fun validateState() {
        val now = System.currentTimeMillis()

        // 메인 타이머 검증
        val mainEnd = _mainEndAtMs.value
        if (mainEnd != null && mainEnd < now) {
            _mainEndAtMs.value = null
            _mainRunning.value = false
            val configured = _mainConfiguredDurationMs.value ?: 0L
            if (_mainLocked.value == true && configured > 0L) {
                _mainDraftRemainingMs.value = configured
                _mainDraftTargetAtMs.value = null
            }
        }

        // 보조 타이머 검증
        // - unlocked & remainingMs <= 0 -> 완료된 것으로 보고 제거
        // - locked & remainingMs <= 0 -> 원래 설정 시간으로 복원 후 정지 상태 유지
        _extras.value = (_extras.value ?: emptyList()).mapNotNull { t ->
            if (t.remainingMs > 0L) {
                t
            } else if (t.locked && t.totalDurationMs > 0L) {
                t.copy(remainingMs = t.totalDurationMs, running = false)
            } else {
                null
            }
        }

        persist()
    }

    // 🔹 모든 상태 초기화
    fun clearAllState() {
        _mainEndAtMs.value = null
        _mainRunning.value = false
        _mainDraftRemainingMs.value = 0L
        _mainDraftTargetAtMs.value = null
        _mainConfiguredDurationMs.value = 0L
        _extras.value = emptyList()
        persist()
    }

    // --- 메인(복원 정보만 저장) ---
    fun setMain(endAtMs: Long?, running: Boolean) {
        _mainEndAtMs.value = endAtMs
        _mainRunning.value = running
        persist()
    }

    fun setMainDraft(remainingMs: Long, targetAtMs: Long?) {
        _mainDraftRemainingMs.value = remainingMs.coerceAtLeast(0L)
        _mainDraftTargetAtMs.value = targetAtMs?.takeIf { it > 0L }
        persist()
    }

    fun clearMainDraft() {
        _mainDraftRemainingMs.value = 0L
        _mainDraftTargetAtMs.value = null
        persist()
    }

    fun setMainLabel(label: String) {
        val normalized = label.trim().let {
            if (it == "메인 타이머" || it == "타이머") "" else it
        }
        _mainLabel.value = normalized
        persist()
    }

    fun setActivePresetLabel(label: String) {
        _activePresetLabel.value = label.trim()
        persist()
    }

    fun effectiveMainNotificationLabel(): String {
        val preset = _activePresetLabel.value.orEmpty().trim()
        if (preset.isNotEmpty()) return preset
        return _mainLabel.value.orEmpty().trim()
    }

    fun setMainLocked(locked: Boolean) {
        _mainLocked.value = locked
        persist()
    }

    fun setMainConfiguredDurationMs(durationMs: Long) {
        _mainConfiguredDurationMs.value = durationMs.coerceAtLeast(0L)
        persist()
    }

    // --- 사용자 등록 시간 버튼 CRUD ---
    fun addCustomPreset(
        label: String,
        durationMs: Long = 0L,
        isClockTarget: Boolean = false,
        targetHour24: Int = 0,
        targetMinute: Int = 0,
        isTomorrow: Boolean = false,
    ): CustomPreset {
        val preset = CustomPreset(
            label = label.trim(),
            durationMs = durationMs.coerceAtLeast(0L),
            isClockTarget = isClockTarget,
            targetHour24 = targetHour24.coerceIn(0, 23),
            targetMinute = targetMinute.coerceIn(0, 59),
            isTomorrow = isTomorrow
        )
        _customPresets.value = (_customPresets.value ?: emptyList()) + preset
        persist()
        return preset
    }

    fun removeCustomPresets(ids: Set<String>) {
        if (ids.isEmpty()) return
        _customPresets.value = (_customPresets.value ?: emptyList()).filterNot { it.id in ids }
        persist()
    }

    // --- 보조 타이머 CRUD ---
    fun addExtra(label: String, durationMs: Long): ExtraTimer {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val t = ExtraTimer(
            label = label.ifBlank { "타이머" },
            remainingMs = safeDuration,
            running = false, // ★ 추가 즉시 자동 시작 금지
            totalDurationMs = safeDuration,
            locked = false
        )
        _extras.value = _extras.value!!.plus(t)
        persist()
        return t
    }

    fun removeExtra(id: String) {
        _extras.value = _extras.value!!.filterNot { it.id == id }
        persist()
    }

    fun renameExtra(id: String, newLabel: String) {
        _extras.value = _extras.value!!.map { if (it.id == id) it.copy(label = newLabel) else it }
        persist()
    }

    fun setExtraLocked(id: String, locked: Boolean) {
        _extras.value = _extras.value!!.map { if (it.id == id) it.copy(locked = locked) else it }
        persist()
    }

    fun setRunning(id: String, running: Boolean) {
        _extras.value = _extras.value!!.map { if (it.id == id) it.copy(running = running) else it }
        persist()
    }

    fun setRemaining(id: String, remainingMs: Long) {
        _extras.value = _extras.value!!.map {
            if (it.id == id) it.copy(remainingMs = remainingMs.coerceAtLeast(0L)) else it
        }
        persist()
    }

    /** UI 틱 중에는 디스크 쓰기(JSON 직렬화) 없이 메모리 값만 갱신 */
    fun updateRemainingInMemory(id: String, remainingMs: Long) {
        val safe = remainingMs.coerceAtLeast(0L)
        _extras.value?.firstOrNull { it.id == id }?.remainingMs = safe
    }

    fun persistNow() {
        persist()
    }

    /**
     * ClockService(포그라운드 서비스)에서 지속저장된 보조타이머 상태를 기준으로
     * ViewModel의 보조타이머 목록을 동기화/보정한다.
     */
    fun upsertExtraFromService(
        id: String,
        label: String,
        remainingMs: Long,
        running: Boolean,
        locked: Boolean? = null
    ) {
        val current = _extras.value ?: emptyList()
        val idx = current.indexOfFirst { it.id == id }
        val existing = current.getOrNull(idx)
        val safeRemaining = remainingMs.coerceAtLeast(0L)
        val fixed = ExtraTimer(
            id = id,
            label = label.ifBlank { "타이머" },
            remainingMs = safeRemaining,
            running = running,
            totalDurationMs = existing?.totalDurationMs?.takeIf { it > 0L } ?: safeRemaining,
            locked = existing?.locked ?: locked ?: false
        )
        _extras.value = if (idx >= 0) {
            current.map { if (it.id == id) fixed else it }
        } else {
            current + fixed
        }
        persist()
    }

    // --- 저장/복원 ---
    private fun persist() {
        val root = JSONObject().apply {
            put("mainEndAt", _mainEndAtMs.value ?: JSONObject.NULL)
            put("mainRunning", _mainRunning.value == true)
            put("mainDraftRemainingMs", _mainDraftRemainingMs.value ?: 0L)
            put("mainDraftTargetAt", _mainDraftTargetAtMs.value ?: JSONObject.NULL)
            put("mainLabel", _mainLabel.value ?: "")
            put("activePresetLabel", _activePresetLabel.value ?: "")
            put("mainLocked", _mainLocked.value == true)
            put("mainConfiguredDurationMs", _mainConfiguredDurationMs.value ?: 0L)
            put("extras", JSONArray().apply {
                (_extras.value ?: emptyList()).forEach {
                    put(JSONObject().apply {
                        put("id", it.id)
                        put("label", it.label)
                        put("remainingMs", it.remainingMs)
                        put("running", it.running)
                        put("totalDurationMs", it.totalDurationMs)
                        put("locked", it.locked)
                    })
                }
            })
            put("customPresets", JSONArray().apply {
                (_customPresets.value ?: emptyList()).forEach {
                    put(JSONObject().apply {
                        put("id", it.id)
                        put("label", it.label)
                        put("durationMs", it.durationMs)
                        put("isClockTarget", it.isClockTarget)
                        put("targetHour24", it.targetHour24)
                        put("targetMinute", it.targetMinute)
                        put("isTomorrow", it.isTomorrow)
                    })
                }
            })
        }
        prefs.edit().putString("state", root.toString()).apply()
    }

    private fun restore() {
        val raw = prefs.getString("state", null) ?: return
        runCatching {
            val root = JSONObject(raw)
            _mainEndAtMs.value = root.optLong("mainEndAt", -1L).let { if (it <= 0) null else it }
            _mainRunning.value = root.optBoolean("mainRunning", false)
            _mainDraftRemainingMs.value = root.optLong("mainDraftRemainingMs", 0L).coerceAtLeast(0L)
            _mainDraftTargetAtMs.value = root.optLong("mainDraftTargetAt", -1L).let { if (it <= 0) null else it }
            _mainLabel.value = root.optString("mainLabel", "")
            _activePresetLabel.value = root.optString("activePresetLabel", "")
            _mainLocked.value = root.optBoolean("mainLocked", false)
            _mainConfiguredDurationMs.value = root.optLong("mainConfiguredDurationMs", 0L).coerceAtLeast(0L)

            val arr = root.optJSONArray("extras") ?: JSONArray()
            val list = mutableListOf<ExtraTimer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += ExtraTimer(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    remainingMs = o.optLong("remainingMs", 0L),
                    running = o.optBoolean("running", false),
                    totalDurationMs = o.optLong("totalDurationMs", o.optLong("remainingMs", 0L)).coerceAtLeast(0L),
                    locked = o.optBoolean("locked", false)
                )
            }
            _extras.value = list

            val presetArr = root.optJSONArray("customPresets") ?: JSONArray()
            val presets = mutableListOf<CustomPreset>()
            for (i in 0 until presetArr.length()) {
                val o = presetArr.getJSONObject(i)
                presets += CustomPreset(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    label = o.optString("label", ""),
                    durationMs = o.optLong("durationMs", 0L).coerceAtLeast(0L),
                    isClockTarget = o.optBoolean("isClockTarget", false),
                    targetHour24 = o.optInt("targetHour24", 0),
                    targetMinute = o.optInt("targetMinute", 0),
                    isTomorrow = o.optBoolean("isTomorrow", false)
                )
            }
            _customPresets.value = presets
        }
    }
}
