package com.krdonon.timer.alarmclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object AlarmScheduler {
    const val ACTION_ALARM_CLOCK = "com.krdonon.timer.ALARM_CLOCK"
    const val EXTRA_ALARM_ID = "extra_alarm_id"

    /** Convenience overload: schedule by id (reads from store). */
    fun scheduleNext(context: Context, alarmId: Long) {
        val item = AlarmStore(context).getById(alarmId) ?: return
        scheduleNext(context, item)
    }

    fun scheduleNext(context: Context, item: AlarmItem) {
        if (!item.enabled) {
            cancel(context, item.id)
            return
        }

        val next = computeNextDateTime(item) ?: run {
            cancel(context, item.id)
            return
        }

        val triggerMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntentUpdate(context, item.id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
        }
    }

    fun cancel(context: Context, alarmId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntentNoCreate(context, alarmId) ?: return
        am.cancel(pi)
    }

    private fun pendingIntentUpdate(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, com.krdonon.timer.alarm.AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_CLOCK
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, alarmId.toInt(), intent, flags)
    }

    private fun pendingIntentNoCreate(context: Context, alarmId: Long): PendingIntent? {
        val intent = Intent(context, com.krdonon.timer.alarm.AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_CLOCK
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, alarmId.toInt(), intent, flags)
    }

    private fun idxToDayOfWeek(idx: Int): DayOfWeek = when (idx) {
        0 -> DayOfWeek.SUNDAY
        1 -> DayOfWeek.MONDAY
        2 -> DayOfWeek.TUESDAY
        3 -> DayOfWeek.WEDNESDAY
        4 -> DayOfWeek.THURSDAY
        5 -> DayOfWeek.FRIDAY
        else -> DayOfWeek.SATURDAY
    }

    private fun computeNextDateTime(item: AlarmItem): LocalDateTime? {
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        val time = LocalTime.of(item.hour24, item.minute)

        val enabledIdx = (0..6).filter { item.days[it] }
        if (enabledIdx.isEmpty()) return null

        val candidates = enabledIdx.map { idx ->
            val dow = idxToDayOfWeek(idx)
            var date = today
            while (date.dayOfWeek != dow) date = date.plusDays(1)

            var dt = date.atTime(time)
            if (!dt.isAfter(now)) dt = dt.plusWeeks(1)
            dt
        }
        return candidates.minOrNull()
    }
}
