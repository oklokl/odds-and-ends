package com.krdondon.week

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 홈 화면 위젯.
 *
 * - 위젯을 탭하면 MainActivity로 진입
 * - 진입 시점에 위젯 RemoteViews를 한 번 더 갱신해서,
 *   런처/배터리 최적화 등의 이유로 TextClock 갱신이 멈춘 경우에도
 *   "수동 갱신"이 되도록 한다.
 */
class TimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 위젯이 추가되거나 복원될 때 호출됨
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // 시간/날짜/타임존이 바뀌는 경우에도 RemoteViews를 갱신해 둔다.
        // NOTE: Intent.ACTION_TIME_SET 같은 상수는 존재하지 않습니다.
        // 시간 설정 변경은 ACTION_TIME_CHANGED / ACTION_TIMEZONE_CHANGED 로 커버됩니다.
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_TICK -> {
                updateAllWidgets(context)
            }
        }
    }

    companion object {

        /**
         * 앱(또는 위젯 탭)에서 호출하는 "수동 갱신".
         */
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, TimeWidgetProvider::class.java)
            )
            for (id in widgetIds) {
                updateWidget(context, appWidgetManager, id)
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // 위젯 탭 -> MainActivity 진입 (동시에 MainActivity에서 수동 갱신 수행)
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(EXTRA_LAUNCHED_FROM_WIDGET, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                /* requestCode = */ 0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            // RemoteViews를 다시 적용하여 TextClock이 재바인딩되도록 한다.
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        const val EXTRA_LAUNCHED_FROM_WIDGET = "extra_launched_from_widget"
    }
}
