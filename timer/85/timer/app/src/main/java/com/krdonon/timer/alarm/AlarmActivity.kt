package com.krdonon.timer.alarm

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.krdonon.timer.R

class AlarmActivity : AppCompatActivity() {

    private lateinit var alarmTitle: TextView
    private lateinit var alarmLabel: TextView
    private lateinit var alarmHint: TextView
    private lateinit var sliderDismiss: SeekBar
    private var slideAnimator: SlideDismissAnimator? = null

    private val DISMISS_THRESHOLD = 92

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)

        val rootScroll = findViewById<View>(R.id.alarmRootScroll)
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        enterImmersiveMode()

        alarmTitle = findViewById(R.id.alarmTitle)
        alarmLabel = findViewById(R.id.alarmLabel)
        alarmHint = findViewById(R.id.alarmHint)
        sliderDismiss = findViewById(R.id.sliderDismiss)

        applyLabelFromIntent(intent?.getStringExtra(AlarmService.EXTRA_LABEL))

        slideAnimator = SlideDismissAnimator(sliderDismiss, alarmHint)

        sliderDismiss.progress = 0
        sliderDismiss.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress >= DISMISS_THRESHOLD) {
                    dismissAlarm()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                slideAnimator?.pauseForTouch()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if ((seekBar?.progress ?: 0) < DISMISS_THRESHOLD) {
                    seekBar?.progress = 0
                    slideAnimator?.resumeAfterTouch()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        slideAnimator?.start()
    }

    override fun onPause() {
        slideAnimator?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        slideAnimator?.stop()
        super.onDestroy()
    }

    private fun enterImmersiveMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyLabelFromIntent(extra: String?) {
        val label = extra ?: getString(R.string.timer_default)
        alarmLabel.text = label
    }

    private fun dismissAlarm() {
        slideAnimator?.stop()
        AlarmService.stop(applicationContext)
        if (this::sliderDismiss.isInitialized) sliderDismiss.progress = 0
        finishAndRemoveTask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLabelFromIntent(intent.getStringExtra(AlarmService.EXTRA_LABEL))
        if (this::sliderDismiss.isInitialized) sliderDismiss.progress = 0
        slideAnimator?.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }
}
