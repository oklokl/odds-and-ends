package com.krdonon.timer.alarm

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmService : Service() {

    companion object {
        private const val CHANNEL_ID = "ALARM_CHANNEL_V5"
        private const val HISTORY_CHANNEL_ID = "ALARM_HISTORY_V1"
        private const val NOTIFICATION_ID = 987654

        const val ACTION_START = "com.krdonon.timer.alarm.ACTION_START"
        const val ACTION_STOP  = "com.krdonon.timer.alarm.ACTION_STOP"
        const val EXTRA_LABEL  = "extra_label"

        const val PREFS = "alarm_prefs"
        const val KEY_RINGING = "ringing"
        const val KEY_STARTED_FROM_KEYGUARD = "started_from_keyguard"

        const val KEY_ALERT_MODE = "key_alert_mode"
        const val MODE_VIBRATE = "VIBRATE"
        const val MODE_SOUND   = "SOUND"

        fun start(context: Context, label: String) {
            val i = Intent(context, AlarmService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else context.startService(i)
            } catch (_: Throwable) { forceStop(context) }
        }

        fun stop(context: Context) {
            val i = Intent(context, AlarmService::class.java).apply { action = ACTION_STOP }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (isServiceRunning(context, AlarmService::class.java)) context.startService(i)
                    else forceStop(context)
                } else context.startService(i)
            } catch (_: Throwable) { forceStop(context) }
        }

        private fun isServiceRunning(ctx: Context, cls: Class<*>): Boolean {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == cls.name }
        }

        fun forceStop(context: Context) {
            runCatching { context.stopService(Intent(context, AlarmService::class.java)) }
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
            }
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_RINGING, false).apply()
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val stopHandler = Handler(Looper.getMainLooper())

    // 볼륨/포커스
    private var savedAlarmVolume: Int? = null
    private var audioFocusGranted = false
    private var focusRequest: AudioFocusRequest? = null
    private val fadeHandler = Handler(Looper.getMainLooper())
    private var fadeStep = 0

    private val FADE_IN_MS = 2000
    private val FADE_STEPS = 20
    private val FADE_INTERVAL = FADE_IN_MS / FADE_STEPS

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAlarm(intent.getStringExtra(EXTRA_LABEL) ?: "Timer Alarm")
            ACTION_STOP  -> stopAlarm()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------- 알람 시작/정지 --------------------
    private fun startAlarm(label: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createOrUpdateAlarmChannel(nm)
        createOrUpdateHistoryChannel(nm)

        val fsIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(EXTRA_LABEL, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val fsPi = PendingIntent.getActivity(
            this, 0, fsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = km.isKeyguardLocked

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏰ 타이머 종료")
            .setContentText(label)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)

        if (isLocked) {
            // 잠금 상태에서만 풀스크린으로 깨워 띄움 (지문 UI 겹침 방지)
            builder.setFullScreenIntent(fsPi, true)
        } else {
            // 평소엔 헤드업 + 탭 시 진입
            builder.setContentIntent(fsPi)
        }

        // 🔹 Android 14+ (API 34+) 대응: 포그라운드 서비스 타입 명시
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
                startForeground(
                    NOTIFICATION_ID,
                    builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29
                startForeground(
                    NOTIFICATION_ID,
                    builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, builder.build())
            }
        } catch (e: Exception) {
            // 포그라운드 시작 실패 시 폴백
            try {
                startForeground(NOTIFICATION_ID, builder.build())
            } catch (e2: Exception) {
                // 최후의 수단: 서비스 종료
                stopSelf()
                return
            }
        }

        val startedFromKeyguard = isLocked
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RINGING, true)
            .putBoolean(KEY_STARTED_FROM_KEYGUARD, startedFromKeyguard)
            .apply()

        val mode = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALERT_MODE, MODE_SOUND) ?: MODE_SOUND

        stopHandler.removeCallbacksAndMessages(null)
        stopSound()
        startVibration()
        if (mode == MODE_SOUND) startSound()

        // 자동 종료(5분)
        stopHandler.postDelayed({ stopAlarm() }, 5 * 60 * 1000L)

        postHistoryNotification(label)
    }

    private fun stopAlarm() {
        stopHandler.removeCallbacksAndMessages(null)
        stopSound()
        stopVibration()

        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RINGING, false)
            .apply()

        // 🔹 Android 14+ (API 34+) 대응: ServiceCompat 사용
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // 폴백
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    @Suppress("DEPRECATION")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
        }

        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        stopSound()
        stopVibration()
        super.onDestroy()
    }

    // -------------------- 소리 --------------------
    private fun startSound() {
        // 배경은 '살짝 줄이기'만 요청
        audioFocusGranted = requestAudioFocusDuck()

        // 알람 스트림만 최대 (배경 MUSIC은 건드리지 않음)
        elevateAlarmStreamVolume()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        try {
            val afd = resources.openRawResourceFd(R.raw.alarm_sound)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                isLooping = true
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                setVolume(0f, 0f) // 페이드 인 시작
                start()
            }
            startFadeIn()
        } catch (e: Exception) {
            // 사운드 파일 로드 실패 시에도 진동은 계속
            e.printStackTrace()
        }
    }

    private fun stopSound() {
        fadeHandler.removeCallbacksAndMessages(null)
        runCatching { mediaPlayer?.apply { if (isPlaying) stop() } }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null

        if (audioFocusGranted) {
            abandonAudioFocus()
            audioFocusGranted = false
        }
        restoreAlarmStreamVolume()
    }

    // -------------------- 페이드 인 --------------------
    private fun startFadeIn() {
        fadeStep = 0
        fadeHandler.post(object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                val t = (++fadeStep).coerceAtMost(FADE_STEPS)
                val vol = t.toFloat() / FADE_STEPS
                mp.setVolume(vol, vol)
                if (t < FADE_STEPS) fadeHandler.postDelayed(this, FADE_INTERVAL.toLong())
            }
        })
    }

    // -------------------- 진동 --------------------
    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
    }

    private fun startVibration() {
        vibrator = getVibrator()
        vibrator?.let { vib ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0))
            } else @Suppress("DEPRECATION") {
                vib.vibrate(longArrayOf(0, 500, 300), 0)
            }
        }
    }

    private fun stopVibration() {
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    // -------------------- 볼륨/포커스 유틸 --------------------
    private fun elevateAlarmStreamVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_ALARM
        if (savedAlarmVolume == null) savedAlarmVolume = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        am.setStreamVolume(stream, max, 0)
    }

    private fun restoreAlarmStreamVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAlarmVolume?.let {
            runCatching {
                am.setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
            }
        }
        savedAlarmVolume = null
    }

    private fun requestAudioFocusDuck(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { /* no-op */ }
                .build()
            am.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else @Suppress("DEPRECATION") {
            am.abandonAudioFocus(null)
        }
    }

    // -------------------- 채널 & 기록 --------------------
    private fun createOrUpdateAlarmChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Timer Alarm", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "타이머 알람 채널"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)   // 소리는 MediaPlayer로
                enableVibration(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun createOrUpdateHistoryChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                HISTORY_CHANNEL_ID, "Alarm History", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "알람 울림 기록"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun postHistoryNotification(label: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val text = "$time • \"$label\" 알림이 울렸습니다"

        val n = NotificationCompat.Builder(this, HISTORY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("알림 기록")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        nm.notify(abs((System.currentTimeMillis() and 0xFFFFFFF).toInt()), n)
    }
}