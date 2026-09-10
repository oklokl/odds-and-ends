package com.krdonon.timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 2번째(가로형) 스톱워치 화면의 "시계" 모드 전용 알림 관리자.
 *
 * 설계 원칙:
 * - ClockService의 Foreground leader 경쟁에 참여하지 않는다.
 * - 타이머/스톱워치 알림과 완전히 다른 Notification ID / Channel / Group을 사용한다.
 * - 현재 시각은 RemoteViews의 TextClock이 SystemUI에서 직접 갱신한다.
 *   따라서 1초마다 notify()를 반복하지 않아 알림 카드 충돌/재배치를 유발하지 않는다.
 */
object ClockNotificationManager {

    private const val PREFS_NAME = "wall_clock_display_prefs"
    private const val KEY_ACTIVE = "wall_clock_active"

    private const val CHANNEL_ID = "wall_clock_display_channel"
    private const val NOTIFICATION_ID = 1004
    private const val NOTIFICATION_GROUP = "wall_clock_notification_group"
    private const val REQUEST_CODE_OPEN_STOPWATCH = 2004

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        val appContext = context.applicationContext
        setActive(appContext, true)
        ensureChannel(appContext)

        if (!canPostNotifications(appContext)) return

        runCatching {
            NotificationManagerCompat.from(appContext).notify(
                NOTIFICATION_ID,
                buildNotification(appContext)
            )
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        setActive(appContext, false)
        runCatching {
            NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        }
    }

    fun isActive(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)

    /**
     * 앱/Fragment가 다시 만들어진 뒤 시계 모드가 살아 있다면 알림을 한 번 복원한다.
     * 시간 흐름 자체는 TextClock이 담당하므로 이후 반복 갱신은 하지 않는다.
     */
    fun restoreIfActive(context: Context) {
        if (isActive(context)) {
            start(context)
        }
    }

    private fun setActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .apply()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "시계 표시",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "현재 시간을 표시하는 시계 알림"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(context: Context): Notification {
        val compact = RemoteViews(
            context.packageName,
            R.layout.notification_clock_compact
        )

        val expanded = RemoteViews(
            context.packageName,
            R.layout.notification_clock_expanded
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("시계")
            .setSubText("시계")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(NOTIFICATION_GROUP)
            .setContentIntent(openStopwatchPendingIntent(context))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .build()
    }

    private fun openStopwatchPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_STOPWATCH
            putExtra("open_tab_action", MainActivity.ACTION_OPEN_STOPWATCH)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_STOPWATCH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
