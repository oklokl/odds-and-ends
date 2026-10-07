package com.example.myapplication2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale

class FloatingTimerService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var windowManagerParams: WindowManager.LayoutParams

    private val timerHandler = Handler(Looper.getMainLooper())
    private val alarmHandler = Handler(Looper.getMainLooper())
    private val stopAlarmRunnable = Runnable { stopAlarm() }

    private var initialSeconds = 10
    private var remainingSeconds = 10
    private var isRunning = false
    private var isAlarmRinging = false
    private var isOverlayVisible = false

    private var tvTimer: TextView? = null
    private var btnStartPause: TextView? = null
    private var btnDismissAlarm: TextView? = null

    private var mediaPlayer: MediaPlayer? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESET = "ACTION_RESET"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_STOP_ALARM = "ACTION_STOP_ALARM"
        const val ACTION_SHOW_OVERLAY = "ACTION_SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "ACTION_HIDE_OVERLAY"

        const val BROADCAST_TICK = "com.example.myapplication2.TIMER_TICK"
        const val EXTRA_REMAINING = "extra_remaining"
        const val EXTRA_RUNNING = "extra_running"
        const val EXTRA_ALARM = "extra_alarm"
    }

    private val updateTimerRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                if (remainingSeconds > 0) {
                    remainingSeconds--
                    updateTimerText()
                    updateNotification()
                    sendBroadcastTick()
                    if (remainingSeconds == 0) {
                        isRunning = false
                        btnStartPause?.text = "시작"
                        triggerAlarm()
                    } else {
                        timerHandler.postDelayed(this, 1000)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START -> {
                    val duration = it.getIntExtra("EXTRA_DURATION", -1)
                    if (duration != -1) {
                        initialSeconds = duration
                        remainingSeconds = duration
                    }
                    if (!isAlarmRinging && remainingSeconds > 0) {
                        isRunning = true
                        btnStartPause?.text = "중지"
                        timerHandler.removeCallbacks(updateTimerRunnable)
                        timerHandler.post(updateTimerRunnable)
                    }
                    updateTimerText()
                    sendBroadcastTick()
                }
                ACTION_PAUSE -> {
                    isRunning = false
                    timerHandler.removeCallbacks(updateTimerRunnable)
                    btnStartPause?.text = "시작"
                    updateNotification()
                    sendBroadcastTick()
                }
                ACTION_RESET -> {
                    stopAlarm()
                    isAlarmRinging = false
                    isRunning = false
                    timerHandler.removeCallbacks(updateTimerRunnable)
                    val duration = it.getIntExtra("EXTRA_DURATION", -1)
                    if (duration != -1) {
                        initialSeconds = duration
                    }
                    remainingSeconds = initialSeconds
                    btnStartPause?.text = "시작"
                    btnStartPause?.visibility = View.VISIBLE
                    btnDismissAlarm?.visibility = View.GONE
                    updateTimerText()
                    updateNotification()
                    sendBroadcastTick()
                }
                ACTION_STOP_ALARM -> {
                    stopAlarm()
                    isAlarmRinging = false
                    isRunning = false
                    btnStartPause?.text = "시작"
                    btnStartPause?.visibility = View.VISIBLE
                    btnDismissAlarm?.visibility = View.GONE
                    remainingSeconds = initialSeconds
                    updateTimerText()
                    updateNotification()
                    sendBroadcastTick()
                }
                ACTION_STOP_SERVICE -> {
                    stopAlarm()
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_SHOW_OVERLAY -> {
                    showOverlayView()
                }
                ACTION_HIDE_OVERLAY -> {
                    hideOverlayView()
                }
                else -> {
                    val duration = it.getIntExtra("EXTRA_DURATION", -1)
                    if (duration != -1 && !isRunning && !isAlarmRinging) {
                        initialSeconds = duration
                        remainingSeconds = duration
                        updateTimerText()
                    }
                }
            }
        }

        startForeground(1, createNotification(getTimeString(remainingSeconds)))
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private fun sendBroadcastTick() {
        val broadcastIntent = Intent(BROADCAST_TICK).apply {
            putExtra(EXTRA_REMAINING, remainingSeconds)
            putExtra(EXTRA_RUNNING, isRunning)
            putExtra(EXTRA_ALARM, isAlarmRinging)
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)
    }

    private fun showOverlayView() {
        if (isOverlayVisible || floatingView != null) return

        try {
            floatingView = LayoutInflater.from(this).inflate(R.layout.floating_timer_layout, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            windowManagerParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }

            windowManager.addView(floatingView, windowManagerParams)
            isOverlayVisible = true

            tvTimer = floatingView!!.findViewById(R.id.tvTimer)
            btnStartPause = floatingView!!.findViewById(R.id.btnStartPause)
            val btnReset = floatingView!!.findViewById<TextView>(R.id.btnReset)
            btnDismissAlarm = floatingView!!.findViewById(R.id.btnDismissAlarm)
            val btnClose = floatingView!!.findViewById<TextView>(R.id.btnExit)

            updateTimerText()
            if (isAlarmRinging) {
                btnStartPause?.visibility = View.GONE
                btnDismissAlarm?.visibility = View.VISIBLE
            } else {
                btnStartPause?.text = if (isRunning) "중지" else "시작"
                btnStartPause?.visibility = View.VISIBLE
                btnDismissAlarm?.visibility = View.GONE
            }

            btnStartPause?.setOnClickListener {
                if (isAlarmRinging) return@setOnClickListener
                if (remainingSeconds <= 0) return@setOnClickListener

                isRunning = !isRunning
                if (isRunning) {
                    btnStartPause?.text = "중지"
                    timerHandler.post(updateTimerRunnable)
                } else {
                    btnStartPause?.text = "시작"
                    timerHandler.removeCallbacks(updateTimerRunnable)
                }
                updateNotification()
                sendBroadcastTick()
            }

            btnReset.setOnClickListener {
                stopAlarm()
                isAlarmRinging = false
                isRunning = false
                btnStartPause?.text = "시작"
                timerHandler.removeCallbacks(updateTimerRunnable)
                btnStartPause?.visibility = View.VISIBLE
                btnDismissAlarm?.visibility = View.GONE
                remainingSeconds = initialSeconds
                updateTimerText()
                updateNotification()
                sendBroadcastTick()
            }

            btnDismissAlarm?.setOnClickListener {
                stopAlarm()
                isAlarmRinging = false
                btnStartPause?.visibility = View.VISIBLE
                btnDismissAlarm?.visibility = View.GONE
                remainingSeconds = initialSeconds
                updateTimerText()
                updateNotification()
                sendBroadcastTick()
            }

            btnClose.setOnClickListener {
                stopSelf()
            }

            @Suppress("ClickableViewAccessibility")
            tvTimer?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = windowManagerParams.x
                            initialY = windowManagerParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            windowManagerParams.x = initialX + (event.rawX - initialTouchX).toInt()
                            windowManagerParams.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(floatingView, windowManagerParams)
                            return true
                        }
                    }
                    return false
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideOverlayView() {
        if (!isOverlayVisible || floatingView == null) return
        try {
            windowManager.removeView(floatingView)
            floatingView = null
            isOverlayVisible = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerAlarm() {
        isAlarmRinging = true
        btnStartPause?.visibility = View.GONE
        btnDismissAlarm?.visibility = View.VISIBLE
        playAlarm()
        updateNotification()
        sendBroadcastTick()
        showOverlayView()
    }

    private fun playAlarm() {
        try {
            stopAlarm()
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_bell).apply {
                isLooping = true
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                start()
            }
            alarmHandler.postDelayed(stopAlarmRunnable, 5 * 60 * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                mediaPlayer = MediaPlayer.create(this, uri).apply {
                    isLooping = true
                    start()
                }
                alarmHandler.postDelayed(stopAlarmRunnable, 5 * 60 * 1000L)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun stopAlarm() {
        alarmHandler.removeCallbacks(stopAlarmRunnable)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getTimeString(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
        }
    }

    private fun updateTimerText() {
        if (isOverlayVisible && tvTimer != null) {
            tvTimer?.text = getTimeString(remainingSeconds)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val channelId = "floating_timer_channel"
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "플로팅 타이머",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "타이머 진행 상황"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val actionIntent = Intent(this, FloatingTimerService::class.java).apply {
            action = if (isAlarmRinging) ACTION_STOP_ALARM else ACTION_STOP_SERVICE
        }
        val actionPendingIntent = PendingIntent.getService(
            this, if (isAlarmRinging) 2 else 1, actionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (isAlarmRinging) "🚨 알람 울리는 중!" else "⏱️ 타이머 실행 중")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(0, if (isAlarmRinging) "알람 끄기" else "종료", actionPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val text = if (isAlarmRinging) "알람 울리는 중!" else "남은 시간: ${getTimeString(remainingSeconds)}"
        notificationManager.notify(1, createNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
        timerHandler.removeCallbacks(updateTimerRunnable)
        alarmHandler.removeCallbacks(stopAlarmRunnable)
        hideOverlayView()
    }
}
