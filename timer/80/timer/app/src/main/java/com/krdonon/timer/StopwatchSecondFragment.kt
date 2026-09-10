package com.krdonon.timer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

class StopwatchSecondFragment : Fragment() {

    companion object {
        private val CLOCK_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }

    private lateinit var rootView: View
    private lateinit var timeText: TextView
    private lateinit var clockButton: MaterialButton
    private lateinit var startStopButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var switchButton: MaterialButton
    private lateinit var keepScreenButton: MaterialButton

    private lateinit var lapListView: ListView
    private lateinit var lapAdapter: ArrayAdapter<String>

    /**
     * '화면꺼짐 방지' 토글 상태
     * - 스톱워치 동작 중뿐 아니라 시계 모드에서도 동일하게 공유한다.
     * - 사용자가 2번째 스톱워치 화면에서 나가면 기존 정책대로 false로 복원한다.
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
        clockButton = view.findViewById(R.id.btnSecondClock)
        startStopButton = view.findViewById(R.id.btnSecondStartStop)
        resetButton = view.findViewById(R.id.btnSecondReset)
        switchButton = view.findViewById(R.id.btnSecondSwitch)
        keepScreenButton = view.findViewById(R.id.btnSecondKeepScreen)

        lapListView = view.findViewById(R.id.secondLapListView)

        viewModel = ViewModelProvider(this)[StopwatchSecondViewModel::class.java]

        lapAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_lap_small,
            R.id.lapText,
            viewModel.lapTimes
        )
        lapListView.adapter = lapAdapter

        clockButton.setOnClickListener { toggleClockMode() }
        startStopButton.setOnClickListener { toggleStartStop() }
        resetButton.setOnClickListener {
            // 시계는 현재 시간을 보여주는 모드이므로 '초기화'의 의미가 없다.
            if (viewModel.isClockMode) return@setOnClickListener

            if (viewModel.isRunning) {
                recordLapTime()
            } else {
                resetAll()
            }
        }
        keepScreenButton.setOnClickListener { toggleKeepScreen() }
        switchButton.setOnClickListener {
            // 화면 전환 시 화면꺼짐 방지는 자동 복원하되,
            // 시계 알림 자체는 백그라운드에서도 유지한다.
            resetKeepScreenState()
            (parentFragment as? StopWatchFragment)?.showClassicStopwatch()
        }

        updater = Runnable {
            // 숫자 영역만 부드럽게 갱신한다. 버튼/알림은 상태가 바뀔 때만 갱신하여
            // 불필요한 UI/Notification 재구성을 피한다.
            timeText.text = if (viewModel.isClockMode) {
                currentClockText()
            } else {
                formatTime(currentElapsed())
            }
            handler.postDelayed(updater!!, 16L)
        }

        timeText.text = if (viewModel.isClockMode) currentClockText() else formatTime(currentElapsed())
        updateButtons()
        updateKeepScreenButton()
        applyKeepScreenPolicy()

        return view
    }

    override fun onStart() {
        super.onStart()
        syncFromPersistedStopwatch2IfAny()
        syncClockModeIfAny()
        updater?.let { handler.post(it) }
        if (this::lapAdapter.isInitialized) lapAdapter.notifyDataSetChanged()
    }

    override fun onStop() {
        super.onStop()
        updater?.let { handler.removeCallbacks(it) }

        // 탭 이동/백그라운드 등으로 화면을 벗어나면 화면꺼짐 방지만 원복한다.
        // 시계/스톱워치의 백그라운드 알림은 계속 유지된다.
        resetKeepScreenState()
    }

    /**
     * 시작/정지 버튼.
     * 시계 모드에서 '시작'을 누르면 시계 모드를 종료하고 기존 스톱워치로 돌아가 시작/재개한다.
     */
    private fun toggleStartStop() {
        if (viewModel.isClockMode) {
            exitClockMode()
            startStopwatchFromAccumulated()
            return
        }

        if (viewModel.isRunning) {
            pauseStopwatchAtCurrentElapsed()
        } else {
            startStopwatchFromAccumulated()
        }
        updateButtons()
        applyKeepScreenPolicy()
    }

    private fun startStopwatchFromAccumulated() {
        val accum = viewModel.accumulatedMs
        viewModel.isClockMode = false
        ClockNotificationManager.stop(requireContext())

        // 서비스의 base(=elapsedRealtime - accumulated)와 동일하게 startBaseMs를 맞추어
        // running 상태에서 시간이 0으로 튀지 않고 기존 누적 시간부터 자연스럽게 이어지도록 한다.
        viewModel.startBaseMs = SystemClock.elapsedRealtime() - accum
        viewModel.accumulatedMs = 0L
        viewModel.isRunning = true
        ClockService.startStopwatch2(requireContext(), accum)

        updateButtons()
        applyKeepScreenPolicy()
    }

    private fun pauseStopwatchAtCurrentElapsed() {
        if (!viewModel.isRunning) return

        val elapsed = currentElapsed()
        viewModel.accumulatedMs = elapsed
        viewModel.isRunning = false
        viewModel.startBaseMs = 0L
        ClockService.pauseStopwatch2(requireContext(), elapsed)
    }

    /**
     * '시계' 버튼은 토글 방식이다.
     * - OFF -> ON: 실행 중 스톱워치가 있다면 현재 값에서 일시정지하고 현재 시각 표시
     * - ON -> OFF: 시계 알림을 제거하고, 일시정지된 기존 스톱워치 값으로 복귀
     */
    private fun toggleClockMode() {
        if (viewModel.isClockMode) {
            exitClockMode()
        } else {
            enterClockMode()
        }
    }

    private fun enterClockMode() {
        // 같은 숫자 영역에서 두 모드가 동시에 '실행'되지 않게 한다.
        // 스톱워치는 값을 잃지 않고 일시정지 상태로 보존한다.
        pauseStopwatchAtCurrentElapsed()

        viewModel.isClockMode = true
        ClockNotificationManager.start(requireContext())
        timeText.text = currentClockText()
        updateButtons()
        applyKeepScreenPolicy()
    }

    private fun exitClockMode() {
        viewModel.isClockMode = false
        ClockNotificationManager.stop(requireContext())
        timeText.text = formatTime(currentElapsed())
        updateButtons()
        applyKeepScreenPolicy()
    }

    private fun resetAll() {
        ClockNotificationManager.stop(requireContext())
        ClockService.resetStopwatch2(requireContext())

        viewModel.isClockMode = false
        viewModel.isRunning = false
        viewModel.startBaseMs = 0L
        viewModel.accumulatedMs = 0L

        viewModel.previousLapTotalMs = 0L
        viewModel.lapCount = 0
        viewModel.lapTimes.clear()
        if (this::lapAdapter.isInitialized) lapAdapter.notifyDataSetChanged()

        timeText.text = formatTime(0L)
        updateButtons()
        applyKeepScreenPolicy()
    }

    private fun recordLapTime() {
        if (viewModel.isClockMode) return

        val total = max(0L, currentElapsed())
        val lap = total - viewModel.previousLapTotalMs
        viewModel.previousLapTotalMs = total

        viewModel.lapCount++
        val totalStr = formatTime(total)
        val lapStr = formatTime(lap)
        val row = String.format("%02d. %s (%s)", viewModel.lapCount, lapStr, totalStr)

        viewModel.lapTimes.add(0, row)
        if (this::lapAdapter.isInitialized) {
            lapAdapter.notifyDataSetChanged()
            lapListView.smoothScrollToPosition(0)
        }
    }

    private fun toggleKeepScreen() {
        keepScreenEnabled = !keepScreenEnabled
        updateKeepScreenButton()
        applyKeepScreenPolicy()
    }

    /**
     * 화면꺼짐 방지는 시계와 스톱워치가 공유한다.
     * - 시계 모드: 시계가 표시되는 동안 적용 가능
     * - 스톱워치 모드: 기존처럼 실제 동작 중일 때 적용
     */
    private fun applyKeepScreenPolicy() {
        val activeDisplay = viewModel.isClockMode || viewModel.isRunning
        rootView.keepScreenOn = keepScreenEnabled && activeDisplay
    }

    private fun updateKeepScreenButton() {
        keepScreenButton.text = if (keepScreenEnabled) {
            "방지 해제"
        } else {
            "화면꺼짐 방지"
        }
    }

    private fun updateClockButtonAppearance() {
        if (!this::clockButton.isInitialized) return

        if (viewModel.isClockMode) {
            clockButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.purple_500)
            )
            clockButton.setTextColor(Color.WHITE)
        } else {
            clockButton.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            clockButton.setTextColor(Color.BLACK)
        }
    }

    private fun resetKeepScreenState() {
        keepScreenEnabled = false
        if (this::rootView.isInitialized) rootView.keepScreenOn = false
        if (this::keepScreenButton.isInitialized) updateKeepScreenButton()
    }

    private fun syncFromPersistedStopwatch2IfAny() {
        val prefs = requireContext().getSharedPreferences("clock_persist_prefs", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("stopwatch2_running", false)
        val base = prefs.getLong("stopwatch2_base", 0L)
        val accum = prefs.getLong("stopwatch2_accumulated", 0L)

        if (running && base > 0L) {
            // 서비스의 base를 그대로 사용: elapsedRealtime - base = elapsed
            viewModel.isRunning = true
            viewModel.startBaseMs = base
            viewModel.accumulatedMs = 0L
        } else {
            // paused/idle: 누적값만 복원
            viewModel.isRunning = false
            viewModel.startBaseMs = 0L
            viewModel.accumulatedMs = max(0L, accum)
        }

        timeText.text = formatTime(currentElapsed())
        updateButtons()
        applyKeepScreenPolicy()
    }

    /**
     * 시계 모드는 독립 알림 관리자의 저장 상태를 기준으로 복원한다.
     * 혹시 이전 프로세스 종료 타이밍 때문에 Stopwatch2 running 상태가 함께 남아 있어도
     * 화면에서는 둘이 동시에 실행되지 않도록 스톱워치를 일시정지시킨다.
     */
    private fun syncClockModeIfAny() {
        val active = ClockNotificationManager.isActive(requireContext())
        viewModel.isClockMode = active

        if (active) {
            pauseStopwatchAtCurrentElapsed()
            ClockNotificationManager.restoreIfActive(requireContext())
            timeText.text = currentClockText()
        } else {
            timeText.text = formatTime(currentElapsed())
        }

        updateButtons()
        applyKeepScreenPolicy()
    }

    private fun currentElapsed(): Long {
        return if (viewModel.isRunning) {
            max(0L, viewModel.accumulatedMs + (SystemClock.elapsedRealtime() - viewModel.startBaseMs))
        } else {
            max(0L, viewModel.accumulatedMs)
        }
    }

    private fun currentClockText(): String = LocalTime.now().format(CLOCK_FORMATTER)

    private fun updateButtons() {
        if (viewModel.isClockMode) {
            // 현재 시각은 초기화할 수 없다. '시작'을 누르면 기존 스톱워치로 복귀하여 시작/재개.
            startStopButton.text = "시작"
            resetButton.text = "초기화"
            resetButton.isEnabled = false
        } else if (viewModel.isRunning) {
            startStopButton.text = "정지"
            resetButton.text = "기록"
            resetButton.isEnabled = true
        } else {
            startStopButton.text = "시작"
            resetButton.text = "초기화"
            resetButton.isEnabled = (viewModel.accumulatedMs > 0L || viewModel.lapTimes.isNotEmpty())
        }

        updateClockButtonAppearance()
    }

    /** HHH:MM:SS.mmm (예: 000:00:00.000) */
    private fun formatTime(ms: Long): String {
        val totalMs = max(0L, ms)
        val totalSeconds = totalMs / 1_000L
        val millis = (totalMs % 1_000L)

        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val totalHours = totalMinutes / 60

        // hours는 3자리로 고정 (000~999). 999를 넘어가면 그대로 출력
        val hStr = if (totalHours <= 999) String.format("%03d", totalHours) else totalHours.toString()
        return String.format("%s:%02d:%02d.%03d", hStr, minutes, seconds, millis)
    }
}
