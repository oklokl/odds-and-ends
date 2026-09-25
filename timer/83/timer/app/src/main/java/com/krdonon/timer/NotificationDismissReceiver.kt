package com.krdonon.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.krdonon.timer.alarm.AlarmService
import com.krdonon.timer.alarm.WeekdayAlarmService

/**
 * 사용자가 시스템 알림창에서 알림을 스와이프하거나 '모두 지우기'로 닫았을 때
 * 동작 중인 작업(타이머 카운트다운, 스톱워치 측정, 알람 울림)이 있다면
 * 잠시 후 자동으로 알림바를 복원해 주는 수신자.
 *
 * 완전히 종료된 상태라면 복원하지 않고 정상 해제 상태를 유지한다.
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISMISS_TIMER = "com.krdonon.timer.action.DISMISS_TIMER"
        const val ACTION_DISMISS_STOPWATCH = "com.krdonon.timer.action.DISMISS_STOPWATCH"
        const val ACTION_DISMISS_STOPWATCH2 = "com.krdonon.timer.action.DISMISS_STOPWATCH2"
        const val ACTION_DISMISS_EXTRA = "com.krdonon.timer.action.DISMISS_EXTRA"
        const val ACTION_DISMISS_CLOCK = "com.krdonon.timer.action.DISMISS_CLOCK"
        const val ACTION_DISMISS_ALARM_TIMER = "com.krdonon.timer.action.DISMISS_ALARM_TIMER"
        const val ACTION_DISMISS_ALARM_WEEKDAY = "com.krdonon.timer.action.DISMISS_ALARM_WEEKDAY"

        const val EXTRA_ID = "extra_id"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            ACTION_DISMISS_TIMER -> {
                ClockService.handleNotificationDismissed(ClockService.DISMISS_TYPE_TIMER)
            }
            ACTION_DISMISS_STOPWATCH -> {
                ClockService.handleNotificationDismissed(ClockService.DISMISS_TYPE_STOPWATCH)
            }
            ACTION_DISMISS_STOPWATCH2 -> {
                ClockService.handleNotificationDismissed(ClockService.DISMISS_TYPE_STOPWATCH2)
            }
            ACTION_DISMISS_EXTRA -> {
                val id = intent.getStringExtra(EXTRA_ID)
                ClockService.handleNotificationDismissed(ClockService.DISMISS_TYPE_EXTRA, id)
            }
            ACTION_DISMISS_CLOCK -> {
                ClockNotificationManager.handleNotificationDismissed(context)
            }
            ACTION_DISMISS_ALARM_TIMER -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "타이머 종료"
                AlarmService.handleNotificationDismissed(context, label)
            }
            ACTION_DISMISS_ALARM_WEEKDAY -> {
                val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "요일 알람"
                WeekdayAlarmService.handleNotificationDismissed(context, alarmId, label)
            }
        }
    }
}
