package com.krdonon.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.krdonon.timer.alarmclock.AlarmScheduler
import com.krdonon.timer.alarmclock.AlarmStore

/**
 * 앱 업데이트(APK 교체) 직후 실행 중이던 타이머/보조 타이머/스톱워치/시계 알림바와
 * 예약된 요일 알람을 자동으로 복구한다.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext

        runCatching {
            ClockNotificationManager.restoreIfActive(appContext)
        }
        runCatching {
            ClockService.restoreIfActive(appContext)
        }
        runCatching {
            val store = AlarmStore(appContext)
            store.getAll()
                .filter { it.enabled }
                .forEach { item ->
                    AlarmScheduler.scheduleNext(appContext, item)
                }
        }
    }
}
