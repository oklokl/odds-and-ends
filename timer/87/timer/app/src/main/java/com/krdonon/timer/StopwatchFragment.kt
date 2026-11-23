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
import androidx.constraintlayout.widget.Group
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

class StopWatchFragment : Fragment() {

    companion object {
        private const val KEY_SHOWING_SECOND = "showing_second_stopwatch"
        private const val TAG_SECOND = "StopwatchSecondFragment"
        private const val PERSIST_PREFS = "clock_persist_prefs"
        private const val KEY_STOPWATCH_BASE = "stopwatch_base"
        private const val KEY_STOPWATCH_RUNNING = "stopwatch_running"
    }

    private lateinit var stopwatchText: TextView
    private lateinit var lapListView: ListView
    private lateinit var startStopButton: MaterialButton
    private lateinit var lapResetButton: MaterialButton
    private lateinit var switchButton: MaterialButton
    private lateinit var keepScreenButton: MaterialButton

    private lateinit var classicGroup: Group
    private lateinit var secondContainer: View

    private var showingSecond: Boolean = false
    private var isStarted: Boolean = false

    // "유지"가 켜져 있고 + 스톱워치가 동작 중일 때만 화면이 꺼지지 않도록
    private var keepScreenEnabled: Boolean = false

    private lateinit var viewModel: StopwatchViewModel

    private val handler = Handler(Looper.getMainLooper())
    private var updater: Runnable? = null

    private lateinit var arrayAdapter: ArrayAdapter<String>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_stopwatch, container, false)

        showingSecond = savedInstanceState?.getBoolean(KEY_SHOWING_SECOND, false) ?: false

        viewModel = ViewModelProvider(requireActivity())[StopwatchViewModel::class.java]

        stopwatchText    = view.findViewById(R.id.stopwatchText)
        lapListView      = view.findViewById(R.id.lapListView)
        startStopButton  = view.findViewById(R.id.btnStartStop)
        lapResetButton   = view.findViewById(R.id.btnLapReset)
        switchButton     = view.findViewById(R.id.btnSwitchStopwatch)
        keepScreenButton = view.findViewById(R.id.btnKeepScreenClassic)

        classicGroup     = view.findViewById(R.id.groupClassicStopwatch)
        secondContainer  = view.findViewById(R.id.secondStopwatchContainer)

        arrayAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_lap_classic,
            R.id.lapClassicText,
            viewModel.lapTimes
        )
        lapListView.adapter = arrayAdapter

        updater = object : Runnable {
            override fun run() {
                if (!shouldRunClassicTicker()) return
                val elapsed = currentElapsed()
                updateStopwatchText(elapsed)
                handler.postDelayed(this, 16L)
            }
        }

        startStopButton.setOnClickListener {
            if (viewModel.isRunning) {
                recordLapTime()
            } else {
                startStopwatch()
            }
        }

        lapResetButton.setOnClickListener { resetStopwatch() }
        switchButton.setOnClickListener { showSecondStopwatch() }

        keepScreenButton.setOnClickListener {
            keepScreenEnabled = !keepScreenEnabled
            updateKeepScreenButtonUi()
            updateKeepScreenState()
        }

        updateStopwatchText(currentElapsed())
        updateButtons()
        updateKeepScreenButtonUi()

        applyStopwatchMode()

        return view
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        syncFromPersistedStopwatch1IfAny()
        updateStopwatchText(currentElapsed())
        updateButtons()
        updateKeepScreenState()
        syncTickerState()
        syncSecondFragmentVisibility()
        arrayAdapter.notifyDataSetChanged()
    }

    override fun onStop() {
        super.onStop()
        isStarted = false
        stopClassicTicker()
        resetKeepScreenState()
        syncSecondFragmentVisibility()
    }

    private fun shouldRunClassicTicker(): Boolean =
        isStarted && !isHidden && !showingSecond && viewModel.isRunning

    private fun syncTickerState() {
        stopClassicTicker()
        if (shouldRunClassicTicker()) {
            updater?.let { handler.post(it) }
        }
    }

    private fun stopClassicTicker() {
        updater?.let { handler.removeCallbacks(it) }
    }

    private fun syncFromPersistedStopwatch1IfAny() {
        val prefs = requireContext().getSharedPreferences(PERSIST_PREFS, Context.MODE_PRIVATE)
        val running = prefs.getBoolean(KEY_STOPWATCH_RUNNING, false)
        val base = prefs.getLong(KEY_STOPWATCH_BASE, 0L)
        if ((running && base > 0L) && !viewModel.isRunning) {
            viewModel.isRunning = true
            viewModel.startBaseMs = base
            viewModel.accumulatedMs = 0L
        }
    }

    private fun currentElapsed(): Long {
        return if (viewModel.isRunning) {
            max(0L, viewModel.accumulatedMs + (SystemClock.elapsedRealtime() - viewModel.startBaseMs))
        } else {
            max(0L, viewModel.accumulatedMs)
        }
    }

    private fun startStopwatch() {
        if (!viewModel.isRunning) {
            viewModel.isRunning = true
            viewModel.startBaseMs = SystemClock.elapsedRealtime()
            updateButtons()
            syncTickerState()

            ClockService.startStopwatch(requireContext(), viewModel.accumulatedMs)
        }
    }

    private fun resetStopwatch() {
        viewModel.isRunning = false
        viewModel.startBaseMs = 0L
        viewModel.accumulatedMs = 0L

        viewModel.previousLapTotalMs = 0L
        viewModel.lapCount = 0
        viewModel.lapTimes.clear()
        arrayAdapter.notifyDataSetChanged()

        stopClassicTicker()
        updateStopwatchText(0L)
        updateButtons()

        ClockService.stopStopwatch(requireContext())
    }

    private fun recordLapTime() {
        val total = max(0L, currentElapsed())
        val lap   = total - viewModel.previousLapTotalMs
        viewModel.previousLapTotalMs = total

        viewModel.lapCount++
        val totalStr = formatTime(total)
        val lapStr   = formatTime(lap)
        val row = String.format(Locale.getDefault(), "%02d. %s (%s)", viewModel.lapCount, lapStr, totalStr)

        viewModel.lapTimes.add(0, row)
        arrayAdapter.notifyDataSetChanged()
        lapListView.smoothScrollToPosition(0)
    }

    private fun updateStopwatchText(time: Long) {
        val h  = TimeUnit.MILLISECONDS.toHours(time)
        val m  = TimeUnit.MILLISECONDS.toMinutes(time) % 60
        val s  = TimeUnit.MILLISECONDS.toSeconds(time) % 60
        val ms = (time % 1000)
        stopwatchText.text = String.format(Locale.getDefault(), "%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    private fun formatTime(time: Long): String {
        val h  = TimeUnit.MILLISECONDS.toHours(time)
        val m  = TimeUnit.MILLISECONDS.toMinutes(time) % 60
        val s  = TimeUnit.MILLISECONDS.toSeconds(time) % 60
        val ms = (time % 1000)
        return String.format(Locale.getDefault(), "%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    private fun updateButtons() {
        if (viewModel.isRunning) {
            startStopButton.text = "랩"
            lapResetButton.text = "초기화"
            lapResetButton.isEnabled = true
        } else {
            startStopButton.text = "시작"
            lapResetButton.text = "초기화"
            lapResetButton.isEnabled = (viewModel.accumulatedMs > 0L || viewModel.lapTimes.isNotEmpty())
        }

        updateKeepScreenState()
    }

    private fun updateKeepScreenButtonUi() {
        val ctx = context ?: return
        keepScreenButton.text = if (keepScreenEnabled) "해제" else "유지"
        val bgColor = AppThemePrefs.resolveColor(
            ctx,
            if (keepScreenEnabled) R.attr.appPrimaryContainer else R.attr.appSurfaceCard
        )
        val textColor = AppThemePrefs.resolveColor(
            ctx,
            if (keepScreenEnabled) R.attr.appOnPrimaryContainer else R.attr.appTextPrimary
        )
        keepScreenButton.backgroundTintList = ColorStateList.valueOf(bgColor)
        keepScreenButton.setTextColor(textColor)
    }

    private fun updateKeepScreenState() {
        val root = view ?: return
        val shouldKeep = (!showingSecond) && keepScreenEnabled && viewModel.isRunning
        root.keepScreenOn = shouldKeep
    }

    private fun resetKeepScreenState() {
        keepScreenEnabled = false
        view?.keepScreenOn = false
        if (this::keepScreenButton.isInitialized) {
            updateKeepScreenButtonUi()
        }
    }

    private fun ensureSecondStopwatchFragment() {
        if (childFragmentManager.findFragmentByTag(TAG_SECOND) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.secondStopwatchContainer, StopwatchSecondFragment(), TAG_SECOND)
                .commitNow()
        }
    }

    private fun syncSecondFragmentVisibility() {
        val second = childFragmentManager.findFragmentByTag(TAG_SECOND) as? StopwatchSecondFragment
        second?.setVisibleInParent(isStarted && !isHidden && showingSecond)
    }

    private fun applyStopwatchMode() {
        if (showingSecond) {
            ensureSecondStopwatchFragment()
            classicGroup.visibility = View.GONE
            secondContainer.visibility = View.VISIBLE
        } else {
            secondContainer.visibility = View.GONE
            classicGroup.visibility = View.VISIBLE
        }

        syncTickerState()
        syncSecondFragmentVisibility()
    }

    private fun showSecondStopwatch() {
        resetKeepScreenState()
        showingSecond = true
        applyStopwatchMode()
    }

    fun showClassicStopwatch() {
        resetKeepScreenState()
        showingSecond = false
        applyStopwatchMode()
        updateStopwatchText(currentElapsed())
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)

        if (hidden) {
            stopClassicTicker()
        } else {
            updateStopwatchText(currentElapsed())
            syncTickerState()
        }
        syncSecondFragmentVisibility()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOWING_SECOND, showingSecond)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopClassicTicker()
    }
}
