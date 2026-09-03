package com.krdonon.timer.alarmclock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

class AlarmStore(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun getAll(): List<AlarmItem> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                AlarmItem.fromJson(o)
            }.sortedBy { it.id }
        }.getOrElse { emptyList() }
    }

    fun getById(id: Long): AlarmItem? = getAll().firstOrNull { it.id == id }

    fun upsert(item: AlarmItem) {
        val current = getAll().toMutableList()
        val idx = current.indexOfFirst { it.id == item.id }
        if (idx >= 0) current[idx] = item else current.add(item)
        saveList(current)
    }

    fun delete(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val filtered = getAll().filterNot { ids.contains(it.id) }
        saveList(filtered)
    }

    fun nextId(): Long {
        val maxId = getAll().maxOfOrNull { it.id } ?: 0L
        return max(1L, maxId + 1L)
    }

    private fun saveList(list: List<AlarmItem>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_ALARMS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "alarm_clock_prefs"
        private const val KEY_ALARMS = "alarms_json"
    }
}