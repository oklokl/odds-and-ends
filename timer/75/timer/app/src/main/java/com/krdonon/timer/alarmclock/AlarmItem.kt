package com.krdonon.timer.alarmclock

import org.json.JSONArray
import org.json.JSONObject

data class AlarmItem(
    val id: Long,
    val hour24: Int,
    val minute: Int,
    val days: BooleanArray, // 0..6 => Sun..Sat
    val enabled: Boolean,
    val label: String,
    val groupId: Long,
    val soundEnabled: Boolean,
    val vibrateEnabled: Boolean,
    val snoozeMinutes: Int, // 5 or 10
    val snoozeCount: Int    // 3 or 5
) {
    init {
        require(days.size == 7) { "days must have size 7" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour24", hour24)
        put("minute", minute)
        put("enabled", enabled)
        put("label", label)
        put("groupId", groupId)
        put("soundEnabled", soundEnabled)
        put("vibrateEnabled", vibrateEnabled)
        put("snoozeMinutes", snoozeMinutes)
        put("snoozeCount", snoozeCount)
        put("days", JSONArray().apply { for (b in days) put(b) })
    }

    companion object {
        fun fromJson(obj: JSONObject): AlarmItem {
            val arr = obj.optJSONArray("days") ?: JSONArray()
            val days = BooleanArray(7) { idx -> arr.optBoolean(idx, false) }
            return AlarmItem(
                id = obj.getLong("id"),
                hour24 = obj.optInt("hour24", 7),
                minute = obj.optInt("minute", 0),
                days = days,
                enabled = obj.optBoolean("enabled", true),
                label = obj.optString("label", ""),
                groupId = obj.optLong("groupId", 0L),
                soundEnabled = obj.optBoolean("soundEnabled", true),
                vibrateEnabled = obj.optBoolean("vibrateEnabled", true),
                snoozeMinutes = obj.optInt("snoozeMinutes", 5),
                snoozeCount = obj.optInt("snoozeCount", 3),
            )
        }
    }
}
