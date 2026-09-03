package com.krdonon.timer.alarmclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.krdonon.timer.alarm.SnoozeReceiver

/**
 * 요일 알람(weekday alarm) 스누즈(다시 울림) 예약/취소.
 *
 * 요구사항:
 * - 사용자가 '밀어서 해제'하면 1분 후에 다시 울려야 함
 * - Android 12+에서 exact alarm이 허용되지 않은 경우, setAlarmClock(...)로 폴백하여
 *   1분 재울림이 지연되는 문제를 최소화
 */
object SnoozeScheduler {
    private const val TAG = "SnoozeScheduler"
    private const val REQUEST_CODE_BASE = 880_000

    /** 항상 1분 후 다시 울림 */
    fun scheduleInOneMinute(context: Context, alarmId: Long) {
        if (alarmId <= 0L) return

        val triggerAtMillis = System.currentTimeMillis() + 60_000L
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, alarmId)

        Log.d(
            TAG,
            "scheduleInOneMinute alarmId=$alarmId triggerAt=$triggerAtMillis exactAllowed=${canExact(am)}"
        )

        // 일부 기기/OS에서 1분 스누즈가 setExactAndAllowWhileIdle로는 지연되거나,
        // Receiver→ForegroundService 시작이 제한되는 사례가 있어(S+), 스누즈는 AlarmClock 경로를 우선 사용.
        // (AlarmClock은 사용자에게 '알람'으로 인지되고, 시스템 예외 처리도 더 강함)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val showIntent = Intent(context, com.krdonon.timer.MainActivity::class.java)
            val showPi = PendingIntent.getActivity(
                context,
                0,
                showIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showPi), pi)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancel(context: Context, alarmId: Long) {
        if (alarmId <= 0L) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, alarmId)
        Log.d(TAG, "cancel alarmId=$alarmId")
        am.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(context: Context, alarmId: Long): PendingIntent {
        val i = Intent(context, SnoozeReceiver::class.java).apply {
            action = SnoozeReceiver.ACTION_SNOOZE
            putExtra(SnoozeReceiver.EXTRA_ALARM_ID, alarmId)
        }
        // alarmId가 커져도 충돌 가능성을 낮춘 requestCode 생성
        val stable = (alarmId xor (alarmId ushr 32)).toInt() and 0x7fffffff
        val requestCode = REQUEST_CODE_BASE + (stable % 100_000)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canExact(am: AlarmManager): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** (옵션) 정확 알람 허용 요청 화면 */
    fun requestExactAlarmPermissionIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }
}
