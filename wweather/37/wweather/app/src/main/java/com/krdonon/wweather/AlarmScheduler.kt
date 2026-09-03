package com.krdonon.wweather

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object AlarmScheduler {

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PressureCheckReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 알림 스위치 상태에 따라 4시간 주기로 예약하거나, 해제합니다. */
    fun reschedule(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val alertEnabled = prefs.getBoolean("alert", false)
        val conditionEnabled = prefs.getBoolean("condition", false)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)

        if (alertEnabled || conditionEnabled) {
            val first = System.currentTimeMillis() + 60 * 1000L // 1분 후 첫 실행
            val interval = 4 * 60 * 60 * 1000L                    // 4시간
            am.setRepeating(AlarmManager.RTC_WAKEUP, first, interval, pi)
        } else {
            am.cancel(pi)
        }
    }

    /** 강제로 해제하고 싶을 때 */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }
}
