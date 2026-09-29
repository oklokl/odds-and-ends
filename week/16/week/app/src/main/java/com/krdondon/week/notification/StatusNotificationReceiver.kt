package com.krdondon.week.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StatusNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_UPDATE_NOTIFICATIONS,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                StatusNotificationManager.updateNotifications(context)
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_NOTIFICATIONS = "com.krdondon.week.ACTION_UPDATE_STATUS_NOTIFICATIONS"
    }
}
