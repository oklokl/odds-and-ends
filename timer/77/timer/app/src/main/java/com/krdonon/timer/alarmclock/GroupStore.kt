package com.krdonon.timer.alarmclock

import android.content.Context
import org.json.JSONArray
import kotlin.math.max

class GroupStore(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun getAll(): List<GroupItem> {
        val raw = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                GroupItem.fromJson(o)
            }.sortedBy { it.id }
        }.getOrElse { emptyList() }
    }

    fun getById(id: Long): GroupItem? = getAll().firstOrNull { it.id == id }

    fun nextId(): Long {
        val maxId = getAll().maxOfOrNull { it.id } ?: 0L
        return max(1L, maxId + 1L)
    }

    fun upsert(item: GroupItem) {
        val current = getAll().toMutableList()
        val idx = current.indexOfFirst { it.id == item.id }
        if (idx >= 0) current[idx] = item else current.add(item)
        saveList(current)
    }

    fun delete(id: Long) {
        val filtered = getAll().filterNot { it.id == id }
        saveList(filtered)
    }

    private fun saveList(list: List<GroupItem>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "alarm_clock_prefs"
        private const val KEY_GROUPS = "groups_json"
    }
}
