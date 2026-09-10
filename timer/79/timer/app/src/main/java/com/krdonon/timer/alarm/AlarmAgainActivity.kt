package com.krdonon.timer.alarm

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.krdonon.timer.R
import com.krdonon.timer.alarmclock.SnoozeScheduler
import com.krdonon.timer.alarmclock.SnoozeStateStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 요일(반복) 알람 전용 화면.
 *
 * - 기존 타이머(AlarmActivity/activity_alarm.xml)와 완전히 분리
 * - 밀어서 해제할 때마다 횟수를 카운팅하고, 설정된 횟수 내에서는 스누즈 예약
 * - 마지막 횟수 도달 시 '완전 종료'(스누즈 취소/상태 클리어)
 */
class AlarmAgainActivity : Activity() {

    companion object {
        private const val TAG = "AlarmAgainActivity"

        /**
         * AlarmEditActivity에서 "계속"을 -1(또는 과거 버전의 1)로 저장하는데,
         * 알림 해제 횟수 비교 로직에서는 실제 상한값이 필요합니다.
         *
         * 요구사항: "계속" 선택 시 4444번 해제해야 완전히 종료.
         */
        private const val SNOOZE_COUNT_FOREVER_LIMIT = 4444
    }

    private lateinit var currentTimeAmPm: TextView
    private lateinit var currentTimeClock: TextView
    private lateinit var infoText: TextView
    private lateinit var alarmLabel: TextView
    private lateinit var countText: TextView
    private lateinit var sliderDismiss: SeekBar
    private lateinit var btnFullDismiss: Button

    private val DISMISS_THRESHOLD = 95

    // SeekBar가 threshold에 도달했을 때 onProgressChanged가 여러 번 호출될 수 있으므로 방어
    private var isHandlingDismiss = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeTickRunnable = object : Runnable {
        override fun run() {
            updateCurrentTimeTitle()
            uiHandler.postDelayed(this, 30_000) // 30초마다 갱신(분 바뀜 보장)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1) 잠금 화면 위 표시 + 화면 켜기 (API 레벨별 안전 처리)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // 2) 화면 유지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 3) 노치(디스플레이 컷아웃) 영역 사용: API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_again)

        // 4) 키가드(잠금화면) 해제 요청
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val kg = getSystemService(KeyguardManager::class.java)
            kg?.requestDismissKeyguard(this, null)
        }

        // 5) 몰입 모드
        enterImmersiveMode()

        // 6) 뷰 바인딩
        currentTimeAmPm = findViewById(R.id.currentTimeAmPm)
        currentTimeClock = findViewById(R.id.currentTimeClock)
        infoText = findViewById(R.id.alarmInfoText)
        alarmLabel = findViewById(R.id.alarmAgainLabel)
        countText = findViewById(R.id.alarmAgainCount)
        sliderDismiss = findViewById(R.id.sliderDismissAgain)
        btnFullDismiss = findViewById(R.id.btnFullDismiss)

        // 7) 표시 데이터 세팅
        bindFromIntent()

        // 8) 슬라이더로 해제
        sliderDismiss.progress = 0
        sliderDismiss.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress >= DISMISS_THRESHOLD && !isHandlingDismiss) {
                    isHandlingDismiss = true
                    dismissWithSnoozeOrFinish()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if ((seekBar?.progress ?: 0) < DISMISS_THRESHOLD) {
                    seekBar?.progress = 0
                }
            }
        })

        // 9) 완전 해제 버튼
        btnFullDismiss.setOnClickListener {
            fullDismiss()
        }
    }

    override fun onResume() {
        super.onResume()
        updateCurrentTimeTitle()
        uiHandler.removeCallbacks(timeTickRunnable)
        uiHandler.post(timeTickRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(timeTickRunnable)
        super.onPause()
    }

    // singleTask로 재호출되는 경우 라벨/카운트 갱신
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        bindFromIntent()
    }

    // 포커스가 돌아올 때 몰입 모드 유지
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun bindFromIntent() {
        val alarmId = intent.getLongExtra(WeekdayAlarmService.EXTRA_ALARM_ID, -1L)
        val label = intent.getStringExtra(WeekdayAlarmService.EXTRA_LABEL) ?: "요일 알람"
        val snoozeMinutes = intent.getIntExtra(WeekdayAlarmService.EXTRA_SNOOZE_MINUTES, 5).coerceAtLeast(1)
        val rawSnoozeCount = intent.getIntExtra(WeekdayAlarmService.EXTRA_SNOOZE_COUNT, 3)
        val (snoozeCount, isForever) = resolveSnoozeCount(rawSnoozeCount)

        alarmLabel.text = label

        val store = SnoozeStateStore(applicationContext)
        val dismissCount = if (alarmId > 0) store.getDismissCount(alarmId) else 0
        val displayCount = dismissCount + 1
        countText.text = "${displayCount}회"

        val totalText = if (isForever) "${SNOOZE_COUNT_FOREVER_LIMIT}회(계속)" else "${snoozeCount}회"

        infoText.text = """
이 알람은 요일 알람 입니다
밀어서 해제하면 1분 후에 다시 알림이 울립니다 (각 울림 최대 ${snoozeMinutes}분)
총 ${totalText} 해제하면 완전 종료됩니다
""".trimIndent()
    }

    private fun updateCurrentTimeTitle() {
        val now = Date()
        val ampm = SimpleDateFormat("a", Locale.KOREA).format(now) // 오전/오후
        // 사용자 요구사항: 00:00 형태(선행 0 포함)로 보이도록
        val time = SimpleDateFormat("hh:mm", Locale.KOREA).format(now)

        currentTimeAmPm.text = ampm
        currentTimeClock.text = time
    }

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

    private fun dismissWithSnoozeOrFinish() {
        val alarmId = intent.getLongExtra(WeekdayAlarmService.EXTRA_ALARM_ID, -1L)
        val snoozeMinutes = intent.getIntExtra(WeekdayAlarmService.EXTRA_SNOOZE_MINUTES, 5).coerceAtLeast(1)
        val rawSnoozeCount = intent.getIntExtra(WeekdayAlarmService.EXTRA_SNOOZE_COUNT, 3)
        val (snoozeCount, isForever) = resolveSnoozeCount(rawSnoozeCount)

        // 서비스 정지
        WeekdayAlarmService.stop(applicationContext)

        if (alarmId <= 0) {
            finishAndRemoveTask()
            return
        }

        val store = SnoozeStateStore(applicationContext)
        val newDismissCount = store.incrementDismissCount(alarmId)

        Log.d(
            TAG,
            "dismiss alarmId=$alarmId newDismissCount=$newDismissCount rawSnoozeCount=$rawSnoozeCount resolved=$snoozeCount isForever=$isForever"
        )

        if (newDismissCount >= snoozeCount) {
            // 마지막 해제: 완전 종료
            SnoozeScheduler.cancel(applicationContext, alarmId)
            store.clear(alarmId)
        } else {
            // 다음 스누즈 예약: 항상 1분 후
            Log.d(TAG, "schedule snooze +1min alarmId=$alarmId")
            SnoozeScheduler.scheduleInOneMinute(applicationContext, alarmId)
        }

        // UI 리셋 및 종료
        sliderDismiss.progress = 0
        finishAndRemoveTask()
    }

    private fun fullDismiss() {
        val alarmId = intent.getLongExtra(WeekdayAlarmService.EXTRA_ALARM_ID, -1L)

        WeekdayAlarmService.stop(applicationContext)

        if (alarmId > 0) {
            SnoozeScheduler.cancel(applicationContext, alarmId)
            SnoozeStateStore(applicationContext).clear(alarmId)
        }

        sliderDismiss.progress = 0
        finishAndRemoveTask()
    }

    /**
     * rawSnoozeCount:
     *  - 3/5: 정상
     *  - -1: "계속"(forever) sentinel
     *  - 1: 과거/부분 적용 버전에서 "계속"이 1로 저장된 케이스
     */
    private fun resolveSnoozeCount(rawSnoozeCount: Int): Pair<Int, Boolean> {
        val isForever = rawSnoozeCount <= 1
        val resolved = if (isForever) {
            SNOOZE_COUNT_FOREVER_LIMIT
        } else {
            rawSnoozeCount.coerceAtLeast(1)
        }
        return resolved to isForever
    }
}
