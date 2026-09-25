package com.krdonon.timer.alarm

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import com.krdonon.timer.R

class AlarmActivity : Activity() {

    private lateinit var alarmTitle: TextView
    private lateinit var alarmLabel: TextView
    private lateinit var sliderDismiss: SeekBar

    private val DISMISS_THRESHOLD = 95

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1) 잠금 화면 위 표시 + 화면 켜기 (API 레벨별 안전 처리)
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

        // 2) 알람 표시 동안 화면 꺼지지 않도록 유지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 3) 노치(디스플레이 컷아웃) 영역 사용: API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)

        // 4) 잠금 화면은 해제하지 않는다.
        //    - 이 Activity는 showWhenLocked로 잠금 화면 위에만 표시한다.
        //    - requestDismissKeyguard()를 호출하면 보안 잠금 상태에서 지문/패턴 UI가 즉시 올라와
        //      알람 화면과 겹칠 수 있고, 신뢰 상태에서는 인증 없이 잠금이 풀릴 수도 있다.
        //    - 사용자가 알람을 해제해 Activity가 종료된 뒤에 시스템 잠금 화면이 다시 드러나고,
        //      그 시점부터 기기 자체의 지문/패턴 인증 흐름을 사용하도록 둔다.

        // 5) 몰입 모드 진입(상/하단 시스템바 숨김)
        enterImmersiveMode()

        // 6) 뷰 바인딩
        alarmTitle = findViewById(R.id.alarmTitle)
        alarmLabel = findViewById(R.id.alarmLabel)
        sliderDismiss = findViewById(R.id.sliderDismiss)

        // 7) 라벨 세팅 (새 인텐트로 갱신될 수 있으므로 별도 함수로 처리)
        applyLabelFromIntent(intent?.getStringExtra(AlarmService.EXTRA_LABEL))

        // 8) 슬라이더로 해제
        sliderDismiss.progress = 0
        sliderDismiss.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress >= DISMISS_THRESHOLD) {
                    dismissAlarm()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if ((seekBar?.progress ?: 0) < DISMISS_THRESHOLD) {
                    seekBar?.progress = 0
                }
            }
        })
    }

    // 풀스크린(몰입) 모드 유지
    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun applyLabelFromIntent(extra: String?) {
        val label = extra ?: getString(R.string.timer_default)
        alarmLabel.text = label
    }

    private fun dismissAlarm() {
        // 알람 사운드/서비스 종료 (잠금 상태에서도 확실히 중지되도록 applicationContext 사용)
        AlarmService.stop(applicationContext)

        // UI 리셋 및 종료
        if (this::sliderDismiss.isInitialized) sliderDismiss.progress = 0

        // 태스크까지 정리(알람 화면이 최근앱에 남지 않게)
        finishAndRemoveTask() // minSdk 26이므로 바로 사용 가능
    }

    // singleTask로 재호출되는 경우 라벨 갱신
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        applyLabelFromIntent(intent?.getStringExtra(AlarmService.EXTRA_LABEL))
    }

    // 포커스가 돌아올 때 몰입 모드 유지
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    // 뒤로가기로 실수로 빠져나가지 않도록 방지(원하면 주석 해제)
    // override fun onBackPressed() { /* no-op: 슬라이더로만 종료 */ }
}
