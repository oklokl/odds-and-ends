package com.krdonon.timer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.Build

class TimerApp : Application() {
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        createNotificationChannels()
        AppLog.startNewSession(this)
        MemoryDiagnostics.logPreviousMemoryLimiterExit(this)
        AppLog.onAppMovedToForeground(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                val wasInBackground = startedActivityCount == 0
                startedActivityCount += 1
                if (wasInBackground) {
                    AppLog.onAppMovedToForeground(this@TimerApp)
                }
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                if (activity.isChangingConfigurations) return
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) {
                    AppLog.onAppMovedToBackground(this@TimerApp)
                }
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        AppLog.i(this, "TimerApp", "application started")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // UI가 숨겨졌거나 실행 중 메모리 압박이 커졌을 때 재생성 가능한 상태를 해제한다.
        // 메인 타이머/알람의 실제 동작 상태는 SharedPreferences/Service에 있으므로 건드리지 않는다.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            AppLog.releaseTransientMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLog.releaseTransientMemory()
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e(this, "Uncaught", "uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
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
