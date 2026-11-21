package com.krdondon.week

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import java.util.Calendar
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 홈 화면 위젯.
 *
 * - 위젯을 탭하면 MainActivity로 진입하며 수동 동기화를 수행한다.
 * - 위젯 크기(가로·세로)에 따라 시간/날짜/요일 텍스트 크기와 여백을 자동으로 조절한다.
 * - 일요일인 경우 요일('일')만 빨간색으로 표시하고, 월~토는 검정색을 유지한다.
 */
class TimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId), newOptions)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelMidnightAlarm(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // 시간/날짜/타임존/로케일 변경, 앱 업데이트, 자정 알람 시 위젯을 갱신한다.
        // (Intent.ACTION_TIME_CHANGED는 매니페스트의 android.intent.action.TIME_SET과 동일)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_MIDNIGHT_UPDATE,
            -> updateAllWidgets(context)
        }
    }

    companion object {
        const val EXTRA_LAUNCHED_FROM_WIDGET = "extra_launched_from_widget"
        private const val ACTION_MIDNIGHT_UPDATE = "com.krdondon.week.ACTION_MIDNIGHT_UPDATE"
        private const val REQUEST_CODE_LAUNCH = 0
        private const val REQUEST_CODE_MIDNIGHT = 1

        private const val DEFAULT_WIDGET_WIDTH_DP = 130f
        private const val DEFAULT_WIDGET_HEIGHT_DP = 56f
        private const val SUNDAY_RED_COLOR = 0xFFD32F2F.toInt()

        internal data class WidgetMetrics(
            val ampmSizeDp: Float,
            val timeSizeDp: Float,
            val dateSizeDp: Float,
            val daySizeDp: Float,
            val hangulBottomOffsetDp: Float,
            val horizontalPaddingDp: Float,
            val verticalPaddingDp: Float,
            val dividerGapDp: Float,
        )

        /**
         * 앱(또는 브로드캐스트)에서 호출하는 전체 위젯 갱신.
         */
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, TimeWidgetProvider::class.java),
            )
            updateWidgets(context, appWidgetManager, widgetIds)
        }

        private fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            overrideOptions: Bundle? = null,
        ) {
            if (appWidgetIds.isEmpty()) return

            val launchPendingIntent = createLaunchPendingIntent(context)
            val sunday = isSunday()
            val selectedBackground = WidgetBackground.getSelected(context)

            for (appWidgetId in appWidgetIds) {
                val options = overrideOptions ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
                val views = createResponsiveRemoteViews(
                    context = context,
                    launchPendingIntent = launchPendingIntent,
                    options = options,
                    isSunday = sunday,
                    background = selectedBackground,
                )
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }

            scheduleMidnightAlarm(context)
        }

        private fun createResponsiveRemoteViews(
            context: Context,
            launchPendingIntent: PendingIntent,
            options: Bundle?,
            isSunday: Boolean,
            background: WidgetBackground,
        ): RemoteViews {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && options != null) {
                val sizes = BundleCompat.getParcelableArrayList(
                    options,
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    SizeF::class.java,
                )
                if (!sizes.isNullOrEmpty()) {
                    val sizeMapping = sizes.associateWith { size ->
                        buildRemoteViews(
                            context = context,
                            launchPendingIntent = launchPendingIntent,
                            widthDp = size.width,
                            heightDp = size.height,
                            isSunday = isSunday,
                            background = background,
                        )
                    }
                    return RemoteViews(sizeMapping)
                }
            }

            val (widthDp, heightDp) = resolveFallbackSizeDp(context, options)
            return buildRemoteViews(
                context = context,
                launchPendingIntent = launchPendingIntent,
                widthDp = widthDp,
                heightDp = heightDp,
                isSunday = isSunday,
                background = background,
            )
        }

        private fun buildRemoteViews(
            context: Context,
            launchPendingIntent: PendingIntent,
            widthDp: Float,
            heightDp: Float,
            isSunday: Boolean,
            background: WidgetBackground,
        ): RemoteViews {
            val density = context.resources.displayMetrics.density
            val isKoreanLocale = Locale.getDefault().language == "ko"
            val metrics = calculateWidgetMetrics(widthDp, heightDp, isKoreanLocale)
            val dayColor = if (isSunday) SUNDAY_RED_COLOR else Color.BLACK

            val hPadPx = (metrics.horizontalPaddingDp * density).roundToInt()
            val vPadPx = (metrics.verticalPaddingDp * density).roundToInt()
            val gapPx = (metrics.dividerGapDp * density).roundToInt()
            val shadowSidePx = (2f * density).roundToInt()
            val baseBottomPx = (2f * density).roundToInt()
            val hangulBottomPx =
                ((2f + metrics.hangulBottomOffsetDp) * density).roundToInt()

            return RemoteViews(context.packageName, R.layout.widget_layout).apply {
                setOnClickPendingIntent(R.id.widget_root, launchPendingIntent)
                setImageViewResource(R.id.widget_background, background.drawableRes)

                setViewPadding(R.id.widget_content, hPadPx, vPadPx, hPadPx, vPadPx)
                setViewPadding(R.id.widget_time_row, 0, 0, 0, gapPx)
                setViewPadding(R.id.widget_date_row, 0, gapPx, 0, 0)

                setViewPadding(R.id.widget_ampm, shadowSidePx, 0, 0, hangulBottomPx)
                setViewPadding(R.id.widget_time, 0, 0, shadowSidePx, baseBottomPx)
                setViewPadding(R.id.widget_date, shadowSidePx, 0, 0, baseBottomPx)
                setViewPadding(R.id.widget_day, 0, 0, shadowSidePx, hangulBottomPx)

                setTextViewTextSize(R.id.widget_ampm, TypedValue.COMPLEX_UNIT_DIP, metrics.ampmSizeDp)
                setTextViewTextSize(R.id.widget_time, TypedValue.COMPLEX_UNIT_DIP, metrics.timeSizeDp)
                setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_DIP, metrics.dateSizeDp)
                setTextViewTextSize(R.id.widget_day, TypedValue.COMPLEX_UNIT_DIP, metrics.daySizeDp)

                setTextColor(R.id.widget_date, Color.BLACK)
                setTextColor(R.id.widget_day, dayColor)
            }
        }

        private fun resolveFallbackSizeDp(
            context: Context,
            options: Bundle?,
        ): Pair<Float, Float> {
            if (options == null) return DEFAULT_WIDGET_WIDTH_DP to DEFAULT_WIDGET_HEIGHT_DP

            val isLandscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)

            val width = (if (isLandscape && maxW > 0) maxW else minW)
                .takeIf { it > 0 }?.toFloat() ?: DEFAULT_WIDGET_WIDTH_DP
            val height = (if (!isLandscape && maxH > 0) maxH else minH)
                .takeIf { it > 0 }?.toFloat() ?: DEFAULT_WIDGET_HEIGHT_DP

            return width to height
        }

        /**
         * 위젯 크기(dp)에 맞춰 시간/날짜/요일 글자 크기와 여백을 균형 있게 계산한다.
         *
         * - 숫자 영역('1:05', '11.22')은 동일한 크기(1:1)로 맞춘다.
         * - 한국어 로케일에서는 한글('오전', '일')의 글리프 높이가 라틴 숫자보다 크므로
         *   오전/오후와 요일 크기를 모두 0.90배로 동일하게 보정해 전체 밸런스를 맞춘다.
         */
        internal fun calculateWidgetMetrics(
            widthDp: Float,
            heightDp: Float,
            isKoreanLocale: Boolean = true,
        ): WidgetMetrics {
            val safeWidth = widthDp.coerceAtLeast(80f)
            val safeHeight = heightDp.coerceAtLeast(36f)

            val horizontalPaddingDp = (safeWidth * 0.06f).coerceIn(6f, 20f)
            val verticalPaddingDp = (safeHeight * 0.06f).coerceIn(4f, 18f)
            val dividerGapDp = (safeHeight * 0.035f).coerceIn(2f, 12f)

            val innerWidth = (safeWidth - 2f * horizontalPaddingDp).coerceAtLeast(40f)
            val innerHeight =
                (safeHeight - 2f * verticalPaddingDp - 2f * dividerGapDp - 2f).coerceAtLeast(18f)

            val maxByWidth = innerWidth / 5.7f
            val maxByHeight = innerHeight / 2.5f

            val timeSizeDp = min(maxByWidth, maxByHeight).coerceIn(11f, 52f)
            val dateSizeDp = timeSizeDp
            val hangulScale = if (isKoreanLocale) 0.90f else 1.0f
            val ampmSizeDp = (timeSizeDp * hangulScale).coerceAtLeast(10f)
            val daySizeDp = (dateSizeDp * hangulScale).coerceAtLeast(10f)
            val hangulBottomOffsetDp = if (isKoreanLocale) timeSizeDp * 0.02f else 0f

            return WidgetMetrics(
                ampmSizeDp = ampmSizeDp,
                timeSizeDp = timeSizeDp,
                dateSizeDp = dateSizeDp,
                daySizeDp = daySizeDp,
                hangulBottomOffsetDp = hangulBottomOffsetDp,
                horizontalPaddingDp = horizontalPaddingDp,
                verticalPaddingDp = verticalPaddingDp,
                dividerGapDp = dividerGapDp,
            )
        }

        internal fun isSunday(calendar: Calendar = Calendar.getInstance()): Boolean {
            return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        }

        internal fun nextMidnightDelayAnchorMillis(now: Calendar = Calendar.getInstance()): Long {
            val midnight = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 5)
                set(Calendar.MILLISECOND, 0)
            }
            return midnight.timeInMillis
        }

        private fun createLaunchPendingIntent(context: Context): PendingIntent {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(EXTRA_LAUNCHED_FROM_WIDGET, true)
            }

            return PendingIntent.getActivity(
                context,
                REQUEST_CODE_LAUNCH,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun createMidnightPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TimeWidgetProvider::class.java).apply {
                action = ACTION_MIDNIGHT_UPDATE
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_MIDNIGHT,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun scheduleMidnightAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val pendingIntent = createMidnightPendingIntent(context)
            val triggerAtMillis = nextMidnightDelayAnchorMillis()

            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC,
                triggerAtMillis,
                pendingIntent,
            )
        }

        private fun cancelMidnightAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(createMidnightPendingIntent(context))
        }
    }
}
