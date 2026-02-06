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
    )

    private val prefs = app.getSharedPreferences("timer_state_v2", 0)

    // 메인 타이머(메인은 UI에서 CountDownTimer로 돌고, 여기서는 복원용만 들고갑니다)
    private val _mainEndAtMs = MutableLiveData<Long?>(null)
    val mainEndAtMs: LiveData<Long?> = _mainEndAtMs
    private val _mainRunning = MutableLiveData(false)
    val mainRunning: LiveData<Boolean> = _mainRunning

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
        _extras.value = _extras.value?.filter { extra ->
            // 남은 시간이 0 이하거나 실행 중이 아닌 것만 유지
            !extra.running || extra.remainingMs > 0
        } ?: emptyList()

        persist()
    }

    // 🔹 모든 상태 초기화
    fun clearAllState() {
        _mainEndAtMs.value = null
        _mainRunning.value = false
        _extras.value = emptyList()
        prefs.edit().clear().apply()
    }

    // --- 메인(복원 정보만 저장) ---
    fun setMain(endAtMs: Long?, running: Boolean) {
        _mainEndAtMs.value = endAtMs
        _mainRunning.value = running
        persist()
    }

    // --- 보조 타이머 CRUD ---
    fun addExtra(label: String, durationMs: Long): ExtraTimer {
        val t = ExtraTimer(
            label = label.ifBlank { "타이머" },
            remainingMs = durationMs.coerceAtLeast(0L),
            running = false // ★ 추가 즉시 자동 시작 금지
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

    // --- 저장/복원 ---
    private fun persist() {
        val root = JSONObject().apply {
            put("mainEndAt", _mainEndAtMs.value ?: JSONObject.NULL)
            put("mainRunning", _mainRunning.value == true)
            put("extras", JSONArray().apply {
                _extras.value!!.forEach {
                    put(JSONObject().apply {
                        put("id", it.id)
                        put("label", it.label)
                        put("remainingMs", it.remainingMs)
                        put("running", it.running)
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

            val arr = root.optJSONArray("extras") ?: JSONArray()
            val list = mutableListOf<ExtraTimer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += ExtraTimer(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    remainingMs = o.optLong("remainingMs", 0L),
                    running = o.optBoolean("running", false),
                )
            }
            _extras.value = list
        }
    }
}