package com.krdonon.timer

import android.content.Context
import android.content.res.ColorStateList
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
import java.util.Locale
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

    private var keepScreenEnabled: Boolean = false
    private var isStarted: Boolean = false
    private var isVisibleInParent: Boolean = true

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
            if (viewModel.isClockMode) return@setOnClickListener

            if (viewModel.isRunning) {
                recordLapTime()
            } else {
                resetAll()
            }
        }
        keepScreenButton.setOnClickListener { toggleKeepScreen() }
        switchButton.setOnClickListener {
            resetKeepScreenState()
            (parentFragment as? StopWatchFragment)?.showClassicStopwatch()
        }

        updater = Runnable {
            if (!shouldRunTicker()) return@Runnable
            timeText.text = if (viewModel.isClockMode) {
                currentClockText()
            } else {
                formatTime(currentElapsed())
            }
            handler.postDelayed(updater!!, 16L)
        }

        renderCurrentTime()
        updateButtons()
        updateKeepScreenButton()
        applyKeepScreenPolicy()

        return view
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        syncFromPersistedStopwatch2IfAny()
        syncClockModeIfAny()
        syncTickerState()
        if (this::lapAdapter.isInitialized) lapAdapter.notifyDataSetChanged()
    }

    override fun onStop() {
        super.onStop()
        isStarted = false
        stopTicker()
        resetKeepScreenState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTicker()
    }

    fun setVisibleInParent(visible: Boolean) {
        if (isVisibleInParent == visible) return
        isVisibleInParent = visible
        if (visible) {
            renderCurrentTime()
            syncTickerState()
        } else {
            stopTicker()
            resetKeepScreenState()
        }
    }

    private fun shouldRunTicker(): Boolean =
        isStarted && !isHidden && isVisibleInParent && (viewModel.isRunning || viewModel.isClockMode)

    private fun syncTickerState() {
        stopTicker()
        if (shouldRunTicker()) {
            updater?.let { handler.post(it) }
        }
    }

    private fun stopTicker() {
        updater?.let { handler.removeCallbacks(it) }
    }

    private fun renderCurrentTime() {
        if (!this::timeText.isInitialized) return
        timeText.text = if (viewModel.isClockMode) currentClockText() else formatTime(currentElapsed())
    }

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
        syncTickerState()
        renderCurrentTime()
    }

    private fun startStopwatchFromAccumulated() {
        val accum = viewModel.accumulatedMs
        viewModel.isClockMode = false
        ClockNotificationManager.stop(requireContext())

        viewModel.startBaseMs = SystemClock.elapsedRealtime() - accum
        viewModel.accumulatedMs = 0L
        viewModel.isRunning = true
        ClockService.startStopwatch2(requireContext(), accum)

        updateButtons()
        applyKeepScreenPolicy()
        syncTickerState()
    }

    private fun pauseStopwatchAtCurrentElapsed() {
        if (!viewModel.isRunning) return

        val elapsed = currentElapsed()
        viewModel.accumulatedMs = elapsed
        viewModel.isRunning = false
        viewModel.startBaseMs = 0L
        ClockService.pauseStopwatch2(requireContext(), elapsed)
        stopTicker()
    }

    private fun toggleClockMode() {
        if (viewModel.isClockMode) {
            exitClockMode()
        } else {
            enterClockMode()
        }
    }

    private fun enterClockMode() {
        pauseStopwatchAtCurrentElapsed()

        viewModel.isClockMode = true
        ClockNotificationManager.start(requireContext())
        timeText.text = currentClockText()
        updateButtons()
        applyKeepScreenPolicy()
        syncTickerState()
    }

    private fun exitClockMode() {
        viewModel.isClockMode = false
        ClockNotificationManager.stop(requireContext())
        timeText.text = formatTime(currentElapsed())
        updateButtons()
        applyKeepScreenPolicy()
        syncTickerState()
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

        stopTicker()
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
        val row = String.format(Locale.getDefault(), "%02d. %s (%s)", viewModel.lapCount, lapStr, totalStr)

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

    private fun applyKeepScreenPolicy() {
        if (!this::rootView.isInitialized) return
        val activeDisplay = viewModel.isClockMode || viewModel.isRunning
        rootView.keepScreenOn = keepScreenEnabled && activeDisplay
    }

    private fun updateKeepScreenButton() {
        val ctx = context ?: return
        if (!this::keepScreenButton.isInitialized) return
        keepScreenButton.text = if (keepScreenEnabled) {
            "방지 해제"
        } else {
            "화면꺼짐 방지"
        }
        val bg = ContextCompat.getColor(
            ctx,
            if (keepScreenEnabled) R.color.accent_primary_container else R.color.surface_card
        )
        val fg = ContextCompat.getColor(
            ctx,
            if (keepScreenEnabled) R.color.on_primary_container else R.color.text_primary
        )
        keepScreenButton.backgroundTintList = ColorStateList.valueOf(bg)
        keepScreenButton.setTextColor(fg)
    }

    private fun updateClockButtonAppearance() {
        val ctx = context ?: return
        if (!this::clockButton.isInitialized) return

        if (viewModel.isClockMode) {
            clockButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.accent_primary)
            )
            clockButton.setTextColor(ContextCompat.getColor(ctx, R.color.on_accent_primary))
        } else {
            clockButton.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.surface_card)
            )
            clockButton.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
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
            viewModel.isRunning = true
            viewModel.startBaseMs = base
            viewModel.accumulatedMs = 0L
        } else {
            viewModel.isRunning = false
            viewModel.startBaseMs = 0L
            viewModel.accumulatedMs = max(0L, accum)
        }

        renderCurrentTime()
        updateButtons()
        applyKeepScreenPolicy()
    }

    private fun syncClockModeIfAny() {
        val active = ClockNotificationManager.isActive(requireContext())
        viewModel.isClockMode = active

        if (active) {
            pauseStopwatchAtCurrentElapsed()
            ClockNotificationManager.restoreIfActive(requireContext())
            timeText.text = currentClockText()
        } else {
            renderCurrentTime()
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

        val hStr = if (totalHours <= 999) String.format(Locale.getDefault(), "%03d", totalHours) else totalHours.toString()
        return String.format(Locale.getDefault(), "%s:%02d:%02d.%03d", hStr, minutes, seconds, millis)
    }
}
