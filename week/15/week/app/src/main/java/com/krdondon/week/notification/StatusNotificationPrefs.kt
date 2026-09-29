package com.krdondon.week.notification

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object StatusNotificationPrefs {
    private const val PREFS_NAME = "week_status_notification_prefs"
    private const val KEY_AM_PM_ENABLED = "key_am_pm_enabled"
    private const val KEY_DAY_OF_WEEK_ENABLED = "key_day_of_week_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAmPmEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AM_PM_ENABLED, false)
    }

    fun setAmPmEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_AM_PM_ENABLED, enabled) }
    }

    fun isDayOfWeekEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DAY_OF_WEEK_ENABLED, false)
    }

    fun setDayOfWeekEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_DAY_OF_WEEK_ENABLED, enabled) }
    }
}
