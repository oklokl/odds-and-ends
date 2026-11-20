package com.krdonon.timer.alarmclock

import org.json.JSONObject

data class GroupItem(
    val id: Long,
    val name: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
    }

    companion object {
        fun fromJson(o: JSONObject): GroupItem =
            GroupItem(
                id = o.optLong("id", 0L),
                name = o.optString("name", "")
            )
    }
}
