package com.krdonon.wweather

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit

object AlarmScheduler {

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PressureCheckReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 백그라운드 실행 금지 정책:
     * 과거 버전에 등록되어 있을 수 있는 반복 알람을 모두 취소하고 알림 플래그를 해제합니다.
     */
    fun reschedule(context: Context) {
        cancel(context)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit {
            putBoolean("alert", false)
            putBoolean("condition", false)
        }
    }

    /** 예약된 백그라운드 알람을 즉시 해제합니다. */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, PressureCheckReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
        am.cancel(pendingIntent(context))
    }
}
