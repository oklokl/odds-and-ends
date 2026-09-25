package com.krdonon.timer.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.krdonon.timer.alarmclock.AlarmStore

/**
 * 요일 알람 스누즈(다시 울림)용 Receiver.
 *
 * - AlarmManager가 깨우면 WeekdayAlarmService를 다시 시작한다.
 * - 정규 요일 스케줄(AlarmScheduler.scheduleNext)은 여기서 건드리지 않는다.
 */
class SnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        Log.d(TAG, "onReceive action=${intent.action} alarmId=$alarmId extras=${intent.extras}")

        if (alarmId <= 0L) {
            Log.w(TAG, "Invalid alarmId=$alarmId. Stop.")
            return
        }

        // 짧은 WakeLock (최대 30초)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timer:snooze_wakelock")
            .apply { setReferenceCounted(false) }

        try {
            runCatching { wl.acquire(30_000) }

            val item = AlarmStore(context).getById(alarmId)
            if (item == null) {
                Log.w(TAG, "AlarmStore.getById($alarmId) returned null. Stop.")
                return
            }

            WeekdayAlarmService.start(
                context = context,
                alarmId = item.id,
                label = if (item.label.isBlank()) "요일 알람" else item.label,
                soundEnabled = item.soundEnabled,
                vibrateEnabled = item.vibrateEnabled,
                snoozeMinutes = item.snoozeMinutes,
                snoozeCount = item.snoozeCount,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "onReceive failed", t)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    companion object {
        private const val TAG = "SnoozeReceiver"
        const val ACTION_SNOOZE = "com.krdonon.timer.alarm.ACTION_SNOOZE_WEEKDAY"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
    }
}
