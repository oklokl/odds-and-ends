package com.krdonon.timer.alarmclock

import android.content.Context

/**
 * 요일 알람(weekday alarm) 스누즈/해제 횟수 상태 저장소.
 *
 * - alarmId별로 해제(dismiss) 횟수를 저장한다.
 * - 정규 알람이 울릴 때(AlarmReceiver) reset(alarmId) 해주면 1회부터 다시 시작한다.
 */
class SnoozeStateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getDismissCount(alarmId: Long): Int {
        if (alarmId <= 0) return 0
        return prefs.getInt(keyDismissCount(alarmId), 0)
    }

    /**
     * 해제 횟수를 +1 하고, 증가된 값을 반환한다.
     */
    fun incrementDismissCount(alarmId: Long): Int {
        if (alarmId <= 0) return 0
        val newValue = (getDismissCount(alarmId) + 1).coerceAtLeast(0)
        prefs.edit().putInt(keyDismissCount(alarmId), newValue).apply()
        return newValue
    }

    /**
     * 정규 알람이 울릴 때 호출: 해제 횟수 0으로 초기화.
     */
    fun reset(alarmId: Long) {
        if (alarmId <= 0) return
        prefs.edit().putInt(keyDismissCount(alarmId), 0).apply()
    }

    /**
     * 완전 종료 시 상태 삭제.
     */
    fun clear(alarmId: Long) {
        if (alarmId <= 0) return
        prefs.edit().remove(keyDismissCount(alarmId)).apply()
    }

    private fun keyDismissCount(alarmId: Long) = "dismiss_count_$alarmId"

    companion object {
        private const val PREF_NAME = "weekday_snooze_state"
    }
}
