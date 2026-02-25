package com.krdonon.timer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class TimerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return

            // ⏰ 타이머 알림 채널
            val timerChannel = NotificationChannel(
                "timer_channel",
                "타이머",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "타이머가 실행 중일 때 표시되는 알림"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            // ⏱ 스톱워치 알림 채널
            val stopwatchChannel = NotificationChannel(
                "stopwatch_channel",
                "스톱워치",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "스톱워치가 실행 중일 때 표시되는 알림"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            // ✅ 여러 개를 동시에 등록 가능
            nm.createNotificationChannels(listOf(timerChannel, stopwatchChannel))
        }
    }
}
