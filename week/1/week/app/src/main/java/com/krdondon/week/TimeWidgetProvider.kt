package com.krdondon.week

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 위젯 인스턴스마다 날짜/요일 갱신
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    // 날짜/시간/타임존이 바뀔 때마다 호출
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, TimeWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

                for (appWidgetId in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            val calendar = Calendar.getInstance()

            // 날짜 + 요일 (오늘 기준)
            val dateFormat = SimpleDateFormat("MM.dd", Locale.KOREAN)
            val dayFormat = SimpleDateFormat("E", Locale.KOREAN)
            val dateString =
                "${dateFormat.format(calendar.time)} ${dayFormat.format(calendar.time)}"

            views.setTextViewText(R.id.widget_date, dateString)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
