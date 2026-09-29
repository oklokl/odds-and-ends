package com.krdondon.week.notification

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.krdondon.week.MainActivity
import com.krdondon.week.R
import java.util.Calendar

object StatusNotificationManager {

    private const val OLD_CHANNEL_ID = "channel_status_bar_info"
    private const val CHANNEL_ID_AM_PM = "channel_status_am_pm_v2"
    private const val CHANNEL_ID_DAY = "channel_status_day_v2"

    private const val GROUP_KEY_AM_PM = "group_status_am_pm"
    private const val GROUP_KEY_DAY = "group_status_day"

    private const val NOTIFICATION_ID_AM_PM = 1001
    private const val NOTIFICATION_ID_DAY_OF_WEEK = 1002
    private const val ALARM_REQUEST_CODE = 2001

    fun updateNotifications(context: Context) {
        val amPmEnabled = StatusNotificationPrefs.isAmPmEnabled(context)
        val dayOfWeekEnabled = StatusNotificationPrefs.isDayOfWeekEnabled(context)

        createNotificationChannels(context)

        val notificationManager = NotificationManagerCompat.from(context)

        // 권한 확인 (Android 13+)
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED)

        val calendar = Calendar.getInstance()

        // 1. 오전/오후 알림 처리
        if (amPmEnabled && hasPermission) {
            val isAm = calendar.get(Calendar.AM_PM) == Calendar.AM
            val smallIcon = if (isAm) R.drawable.ic_stat_am else R.drawable.ic_stat_pm
            val title = if (isAm) "오전" else "오후"

            val notification = buildNotification(
                context = context,
                channelId = CHANNEL_ID_AM_PM,
                groupKey = GROUP_KEY_AM_PM,
                smallIconRes = smallIcon,
                title = title
            )
            try {
                notificationManager.notify(NOTIFICATION_ID_AM_PM, notification)
            } catch (_: SecurityException) {
            }
        } else {
            notificationManager.cancel(NOTIFICATION_ID_AM_PM)
        }

        // 2. 요일 알림 처리
        if (dayOfWeekEnabled && hasPermission) {
            val (smallIcon, title) = getDayResourceAndTitle(calendar.get(Calendar.DAY_OF_WEEK))

            val notification = buildNotification(
                context = context,
                channelId = CHANNEL_ID_DAY,
                groupKey = GROUP_KEY_DAY,
                smallIconRes = smallIcon,
                title = title
            )
            try {
                notificationManager.notify(NOTIFICATION_ID_DAY_OF_WEEK, notification)
            } catch (_: SecurityException) {
            }
        } else {
            notificationManager.cancel(NOTIFICATION_ID_DAY_OF_WEEK)
        }

        // 3. 스케줄러(AlarmManager & WorkManager) 관리
        if (amPmEnabled || dayOfWeekEnabled) {
            scheduleNextAlarm(context)
            StatusNotificationWorker.enqueue(context)
        } else {
            cancelAlarm(context)
            StatusNotificationWorker.cancel(context)
        }
    }

    private fun buildNotification(
        context: Context,
        channelId: String,
        groupKey: String,
        smallIconRes: Int,
        title: String
    ) = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(smallIconRes)
        .setContentTitle(title)
        .setContentText(context.getString(R.string.notification_guide_text))
        .setGroup(groupKey)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setAutoCancel(false)
        .setSound(null)
        .setVibrate(null)
        .setContentIntent(createContentIntent(context))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()

    private fun createContentIntent(context: Context): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // 이전 무음 채널 삭제
        try {
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        } catch (_: Exception) {
        }

        // 오전/오후 채널: 소리/진동 없이 상태바 아이콘은 정상 노출되도록 IMPORTANCE_DEFAULT 설정
        val channelAmPm = NotificationChannel(
            CHANNEL_ID_AM_PM,
            "오전·오후 상태바 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "상태바에 오전/오후 아이콘을 표시하는 알림입니다."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        // 요일 채널: 별도 채널로 분리하여 시스템에 의한 자동 묶임 방지
        val channelDay = NotificationChannel(
            CHANNEL_ID_DAY,
            "요일 상태바 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "상태바에 요일 아이콘을 표시하는 알림입니다."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        manager.createNotificationChannel(channelAmPm)
        manager.createNotificationChannel(channelDay)
    }

    private fun getDayResourceAndTitle(dayOfWeek: Int): Pair<Int, String> {
        return when (dayOfWeek) {
            Calendar.MONDAY -> R.drawable.ic_stat_mon to "월요일"
            Calendar.TUESDAY -> R.drawable.ic_stat_tue to "화요일"
            Calendar.WEDNESDAY -> R.drawable.ic_stat_wed to "수요일"
            Calendar.THURSDAY -> R.drawable.ic_stat_thu to "목요일"
            Calendar.FRIDAY -> R.drawable.ic_stat_fri to "금요일"
            Calendar.SATURDAY -> R.drawable.ic_stat_sat to "토요일"
            Calendar.SUNDAY -> R.drawable.ic_stat_sun to "일요일"
            else -> R.drawable.ic_stat_mon to "월요일"
        }
    }

    private fun scheduleNextAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = createAlarmPendingIntent(context)
        val triggerAtMillis = getNextTransitionTimeMillis()

        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } catch (_: SecurityException) {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = createAlarmPendingIntent(context)
        alarmManager.cancel(pendingIntent)
    }

    private fun createAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, StatusNotificationReceiver::class.java).apply {
            action = StatusNotificationReceiver.ACTION_UPDATE_NOTIFICATIONS
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getNextTransitionTimeMillis(): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        if (currentHour < 12) {
            // 오늘 낮 12:00
            next.set(Calendar.HOUR_OF_DAY, 12)
            next.set(Calendar.MINUTE, 0)
        } else {
            // 내일 자정 00:00
            next.add(Calendar.DAY_OF_YEAR, 1)
            next.set(Calendar.HOUR_OF_DAY, 0)
            next.set(Calendar.MINUTE, 0)
        }
        // 전환 시점 500ms 이후 갱신 보장
        return next.timeInMillis + 500
    }
}
