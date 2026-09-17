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
 * - 여러 위젯을 갱신할 때 RemoteViews/PendingIntent를 한 번만 생성해
 *   불필요한 임시 객체와 메모리 할당을 줄인다.
 */
class TimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 위젯이 추가되거나 복원될 때 호출됨
        updateWidgets(context, appWidgetManager, appWidgetIds)
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
            Intent.ACTION_TIME_TICK -> updateAllWidgets(context)
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
            updateWidgets(context, appWidgetManager, widgetIds)
        }

        /**
         * 동일한 레이아웃을 사용하는 모든 위젯을 한 번에 갱신한다.
         * RemoteViews와 PendingIntent를 ID마다 새로 만들지 않아 일시적인 메모리 할당을 줄인다.
         */
        private fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            if (appWidgetIds.isEmpty()) return

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

            val views = RemoteViews(context.packageName, R.layout.widget_layout).apply {
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }

            // 하나의 RemoteViews를 전체 위젯에 적용한다.
            // TextClock이 다시 바인딩되면서 수동 갱신 효과도 유지된다.
            appWidgetManager.updateAppWidget(appWidgetIds, views)
        }

        const val EXTRA_LAUNCHED_FROM_WIDGET = "extra_launched_from_widget"
    }
}
