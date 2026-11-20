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

    // 보조 타이머들
    private val _extras = MutableLiveData<List<ExtraTimer>>(emptyList())
    val extras: LiveData<List<ExtraTimer>> = _extras

    init {
        restore()
        validateState()  // 🔹 복원 후 상태 검증
    }

    // 🔹 상태 검증: 이미 지난 시간은 제거
    fun validateState() {
        val now = System.currentTimeMillis()

        // 메인 타이머 검증
        val mainEnd = _mainEndAtMs.value
        if (mainEnd != null && mainEnd < now) {
            // 이미 지난 시간이면 초기화
            _mainEndAtMs.value = null
            _mainRunning.value = false
        }

        // 보조 타이머 검증
        // ✅ remainingMs <= 0 인 항목은 완료된 것으로 보고 목록에서 제거한다.
        // (사용자가 추가만 해놓고 시작하지 않은 타이머는 remainingMs > 0 이므로 유지됨)
        _extras.value = (_extras.value ?: emptyList()).filter { it.remainingMs > 0L }

        persist()
    }

    // 🔹 모든 상태 초기화
    fun clearAllState() {
        _mainEndAtMs.value = null
        _mainRunning.value = false
        _mainDraftRemainingMs.value = 0L
        _mainDraftTargetAtMs.value = null
        _extras.value = emptyList()
        prefs.edit().clear().apply()
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

    // --- 보조 타이머 CRUD ---
    fun addExtra(label: String, durationMs: Long): ExtraTimer {
        val safeDuration = durationMs.coerceAtLeast(0L)
        val t = ExtraTimer(
            label = label.ifBlank { "타이머" },
            remainingMs = safeDuration,
            running = false, // ★ 추가 즉시 자동 시작 금지
            totalDurationMs = safeDuration
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



    /**
     * ClockService(포그라운드 서비스)에서 지속저장된 보조타이머 상태를 기준으로
     * ViewModel의 보조타이머 목록을 동기화/보정한다.
     *
     * - 서비스에 존재하면 running=true 로 간주
     * - remainingMs/label은 서비스 값을 우선한다
     */
    fun upsertExtraFromService(id: String, label: String, remainingMs: Long, running: Boolean) {
        val current = _extras.value ?: emptyList()
        val idx = current.indexOfFirst { it.id == id }
        val existing = current.getOrNull(idx)
        val safeRemaining = remainingMs.coerceAtLeast(0L)
        val fixed = ExtraTimer(
            id = id,
            label = label.ifBlank { "타이머" },
            remainingMs = safeRemaining,
            running = running,
            totalDurationMs = existing?.totalDurationMs?.takeIf { it > 0L } ?: safeRemaining
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
            put("extras", JSONArray().apply {
                _extras.value!!.forEach {
                    put(JSONObject().apply {
                        put("id", it.id)
                        put("label", it.label)
                        put("remainingMs", it.remainingMs)
                        put("running", it.running)
                        put("totalDurationMs", it.totalDurationMs)
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
                )
            }
            _extras.value = list
        }
    }
}