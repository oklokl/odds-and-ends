package com.krdonon.timer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import kotlin.math.max

class StopwatchSecondFragment : Fragment() {

    private lateinit var rootView: View
    private lateinit var timeText: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var switchButton: MaterialButton
    private lateinit var keepScreenButton: MaterialButton

    /**
     * '화면꺼짐 방지' 토글 상태
     * - 사용자가 2번째 스톱워치 화면(전환 화면)에서 나가면 자동으로 false로 복원
     */
    private var keepScreenEnabled: Boolean = false

    private lateinit var viewModel: StopwatchSecondViewModel

    private val handler = Handler(Looper.getMainLooper())
    private var updater: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.activity_stopwatch_second, container, false)
        rootView = view

        timeText = view.findViewById(R.id.secondStopwatchText)
        startStopButton = view.findViewById(R.id.btnSecondStartStop)
        resetButton = view.findViewById(R.id.btnSecondReset)
        switchButton = view.findViewById(R.id.btnSecondSwitch)
        keepScreenButton = view.findViewById(R.id.btnSecondKeepScreen)

        viewModel = ViewModelProvider(this)[StopwatchSecondViewModel::class.java]

        startStopButton.setOnClickListener { toggleStartStop() }
        resetButton.setOnClickListener { reset() }
        keepScreenButton.setOnClickListener { toggleKeepScreen() }
        switchButton.setOnClickListener {
            // ✅ 화면 전환 시(=2번째 스톱워치 화면에서 나갈 때) 화면꺼짐 방지 자동 복원
            resetKeepScreenState()
            (parentFragment as? StopWatchFragment)?.showClassicStopwatch()
        }

        updater = Runnable {
            timeText.text = formatTime(currentElapsed())
            updateButtons()
            applyKeepScreenPolicy()
            handler.postDelayed(updater!!, 16L)
        }

        // 첫 진입 시 000:00:00.000 표시
        timeText.text = formatTime(currentElapsed())
        updateButtons()
        updateKeepScreenButton()
        applyKeepScreenPolicy()

        return view
    }

    override fun onStart() {
        super.onStart()
        updater?.let { handler.post(it) }
    }

    override fun onStop() {
        super.onStop()
        updater?.let { handler.removeCallbacks(it) }

        // ✅ 탭 이동/백그라운드 등으로 2번째 스톱워치 화면을 벗어나면 자동으로 원복
        resetKeepScreenState()
    }

    private fun toggleStartStop() {
        if (viewModel.isRunning) {
            // 정지
            viewModel.accumulatedMs = currentElapsed()
            viewModel.isRunning = false
        } else {
            // 시작(또는 재시작)
            viewModel.startBaseMs = SystemClock.elapsedRealtime()
            viewModel.isRunning = true
        }
        updateButtons()
        applyKeepScreenPolicy()
    }

    private fun reset() {
        viewModel.isRunning = false
        viewModel.startBaseMs = 0L
        viewModel.accumulatedMs = 0L
        timeText.text = formatTime(0L)
        updateButtons()
        applyKeepScreenPolicy()
    }

    private fun toggleKeepScreen() {
        keepScreenEnabled = !keepScreenEnabled
        updateKeepScreenButton()
        applyKeepScreenPolicy()
    }

    /**
     * keepScreenEnabled가 true 이면서 스톱워치가 동작 중(isRunning)일 때만 화면꺼짐 방지 적용
     */
    private fun applyKeepScreenPolicy() {
        // View.keepScreenOn은 view가 보이는 동안만 화면 유지 요청을 걸기 때문에
        // Window flag 대비 안전하게 자동 원복(참조 카운팅)되는 편입니다.
        rootView.keepScreenOn = keepScreenEnabled && viewModel.isRunning
    }

    private fun updateKeepScreenButton() {
        keepScreenButton.text = if (keepScreenEnabled) {
            "방지 해제"
        } else {
            "화면꺼짐 방지"
        }
    }

    private fun resetKeepScreenState() {
        keepScreenEnabled = false
        if (this::rootView.isInitialized) rootView.keepScreenOn = false
        if (this::keepScreenButton.isInitialized) updateKeepScreenButton()
    }

    private fun currentElapsed(): Long {
        return if (viewModel.isRunning) {
            max(0L, viewModel.accumulatedMs + (SystemClock.elapsedRealtime() - viewModel.startBaseMs))
        } else {
            max(0L, viewModel.accumulatedMs)
        }
    }

    private fun updateButtons() {
        if (viewModel.isRunning) {
            startStopButton.text = "정지"
            resetButton.isEnabled = false
        } else {
            startStopButton.text = "시작"
            resetButton.isEnabled = (viewModel.accumulatedMs > 0L)
        }
    }

    /** HHH:MM:SS.mmm (예: 123:00:00.000) */
    private fun formatTime(ms: Long): String {
        val totalMs = max(0L, ms)
        val hours = totalMs / 3_600_000L
        val minutes = (totalMs % 3_600_000L) / 60_000L
        val seconds = (totalMs % 60_000L) / 1_000L
        val millis = (totalMs % 1_000L)

        // hours는 3자리로 고정 (000~999). 999를 넘어가면 그대로 출력
        val hStr = if (hours <= 999) String.format("%03d", hours) else hours.toString()
        return String.format("%s:%02d:%02d.%03d", hStr, minutes, seconds, millis)
    }
}
