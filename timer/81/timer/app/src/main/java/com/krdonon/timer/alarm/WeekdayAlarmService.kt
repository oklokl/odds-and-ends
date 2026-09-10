package com.krdonon.timer.alarm

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.krdonon.timer.R

/**
 * 요일(반복) 알람 전용 서비스.
 *
 * - 기존 타이머(AlarmService)와 완전히 분리하여, 동시 울림(멀티) 시 서로 덮어쓰지 않도록 한다.
 * - 풀스크린 화면은 [AlarmAgainActivity]를 사용한다.
 */
class WeekdayAlarmService : Service() {

    companion object {
        private const val CHANNEL_ID = "WEEKDAY_ALARM_CHANNEL_V1"
        private const val NOTIFICATION_ID = 987655

        const val ACTION_START = "com.krdonon.timer.alarm.ACTION_START_WEEKDAY"
        const val ACTION_STOP = "com.krdonon.timer.alarm.ACTION_STOP_WEEKDAY"

        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_SOUND_ENABLED = "extra_sound_enabled"
        const val EXTRA_VIBRATE_ENABLED = "extra_vibrate_enabled"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
        const val EXTRA_SNOOZE_COUNT = "extra_snooze_count"

        fun start(
            context: Context,
            alarmId: Long,
            label: String,
            soundEnabled: Boolean,
            vibrateEnabled: Boolean,
            snoozeMinutes: Int,
            snoozeCount: Int,
        ) {
            val i = Intent(context, WeekdayAlarmService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_SOUND_ENABLED, soundEnabled)
                putExtra(EXTRA_VIBRATE_ENABLED, vibrateEnabled)
                putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
                putExtra(EXTRA_SNOOZE_COUNT, snoozeCount)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (_: Throwable) {
                forceStop(context)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, WeekdayAlarmService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(i) }
        }

        fun forceStop(context: Context) {
            runCatching { context.stopService(Intent(context, WeekdayAlarmService::class.java)) }
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val stopHandler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAlarm(
                    alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L),
                    label = intent.getStringExtra(EXTRA_LABEL) ?: "요일 알람",
                    soundEnabled = intent.getBooleanExtra(EXTRA_SOUND_ENABLED, true),
                    vibrateEnabled = intent.getBooleanExtra(EXTRA_VIBRATE_ENABLED, true),
                    snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 5),
                    snoozeCount = intent.getIntExtra(EXTRA_SNOOZE_COUNT, 3),
                )
            }
            ACTION_STOP -> stopAlarm()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAlarm(
        alarmId: Long,
        label: String,
        soundEnabled: Boolean,
        vibrateEnabled: Boolean,
        snoozeMinutes: Int,
        snoozeCount: Int,
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createOrUpdateChannel(nm)

        val fsIntent = Intent(this, AlarmAgainActivity::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_SOUND_ENABLED, soundEnabled)
            putExtra(EXTRA_VIBRATE_ENABLED, vibrateEnabled)
            putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            putExtra(EXTRA_SNOOZE_COUNT, snoozeCount)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val fsPi = PendingIntent.getActivity(
            this,
            0,
            fsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = km.isKeyguardLocked

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏰ 요일 알람")
            .setContentText(label)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)

        if (isLocked) {
            builder.setFullScreenIntent(fsPi, true)
        } else {
            builder.setContentIntent(fsPi)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, builder.build())
            }
        } catch (_: Throwable) {
            // 포그라운드 시작 실패 시 종료
            stopSelf()
            return
        }

        stopHandler.removeCallbacksAndMessages(null)
        stopEffects()

        if (vibrateEnabled) startVibration()
        if (soundEnabled) startSound()

        // 안전장치: 설정된 다시 울림(5/10분)을 '한 번 울리는 최대 지속시간'으로 사용
        // (사용자가 해제하지 않아도 일정 시간 후 자동 종료)
        val durationMinutes = snoozeMinutes.coerceIn(1, 60)
        stopHandler.postDelayed({ stopAlarm() }, durationMinutes * 60_000L)
    }

    private fun stopAlarm() {
        stopHandler.removeCallbacksAndMessages(null)
        stopEffects()

        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        stopEffects()
        super.onDestroy()
    }

    private fun stopEffects() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null

        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        runCatching {
            val customUri = AlarmSoundPrefs.getReadableCustomUriOrNull(this)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                isLooping = true

                if (customUri != null) {
                    setDataSource(this@WeekdayAlarmService, customUri)
                } else {
                    val afd = resources.openRawResourceFd(R.raw.alarm_sound)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }

                prepare()
                start()
            }
        }.onFailure {
            // 사용자 파일이 삭제/권한 해제된 경우: 기본으로 복귀
            AlarmSoundPrefs.clearCustom(this)
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 500, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun createOrUpdateChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "요일 알람",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "요일(반복) 알람"
                setSound(null, null) // 사운드는 서비스에서 직접 재생
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }
}
