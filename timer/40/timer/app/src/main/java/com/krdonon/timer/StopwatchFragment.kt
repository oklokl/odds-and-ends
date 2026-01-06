package com.krdonon.timer

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
import java.util.concurrent.TimeUnit
import kotlin.math.max

class StopWatchFragment : Fragment() {

    companion object {
        private const val KEY_SHOWING_SECOND = "showing_second_stopwatch"
        private const val TAG_SECOND = "StopwatchSecondFragment"
    }

    private lateinit var stopwatchText: TextView
    private lateinit var lapListView: ListView
    private lateinit var startStopButton: MaterialButton
    private lateinit var lapResetButton: MaterialButton
    private lateinit var switchButton: MaterialButton

    private lateinit var classicGroup: Group
    private lateinit var secondContainer: View

    private var showingSecond: Boolean = false

    private lateinit var viewModel: StopwatchViewModel

    private val handler = Handler(Looper.getMainLooper())
    private var updater: Runnable? = null

    private lateinit var arrayAdapter: ArrayAdapter<String>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_stopwatch, container, false)

        // 2번째 스톱워치 표시 상태 복원
        showingSecond = savedInstanceState?.getBoolean(KEY_SHOWING_SECOND, false) ?: false

        viewModel = ViewModelProvider(requireActivity())[StopwatchViewModel::class.java]

        stopwatchText   = view.findViewById(R.id.stopwatchText)
        lapListView     = view.findViewById(R.id.lapListView)
        startStopButton = view.findViewById(R.id.btnStartStop)
        lapResetButton  = view.findViewById(R.id.btnLapReset)
        switchButton    = view.findViewById(R.id.btnSwitchStopwatch)

        classicGroup    = view.findViewById(R.id.groupClassicStopwatch)
        secondContainer = view.findViewById(R.id.secondStopwatchContainer)

        arrayAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, viewModel.lapTimes)
        lapListView.adapter = arrayAdapter

        // UI 업데이트 루프
        updater = object : Runnable {
            override fun run() {
                val elapsed = currentElapsed()
                updateStopwatchText(elapsed)
                handler.postDelayed(this, 10L)
            }
        }

        // 버튼 동작
        startStopButton.setOnClickListener {
            if (viewModel.isRunning) {
                // 달리는 중에는 랩 기록
                recordLapTime()
            } else {
                startStopwatch()
            }
        }

        lapResetButton.setOnClickListener { resetStopwatch() }
        switchButton.setOnClickListener { showSecondStopwatch() }

        updateStopwatchText(currentElapsed())
        updateButtons()

        applyStopwatchMode()

        return view
    }

    override fun onStart() {
        super.onStart()
        updater?.let { handler.post(it) }
        updateStopwatchText(currentElapsed())
        updateButtons()
        arrayAdapter.notifyDataSetChanged()
    }

    override fun onStop() {
        super.onStop()
        updater?.let { handler.removeCallbacks(it) }
    }

    private fun currentElapsed(): Long {
        return if (viewModel.isRunning) {
            viewModel.accumulatedMs + (SystemClock.elapsedRealtime() - viewModel.startBaseMs)
        } else {
            viewModel.accumulatedMs
        }
    }

    private fun startStopwatch() {
        if (!viewModel.isRunning) {
            viewModel.isRunning = true
            viewModel.startBaseMs = SystemClock.elapsedRealtime()
            updateButtons()

            // ✅ 서비스 알림 시작
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

        updateStopwatchText(0L)
        updateButtons()

        // ✅ 서비스 알림 정지
        ClockService.stopStopwatch(requireContext())
    }

    private fun recordLapTime() {
        val total = max(0L, currentElapsed())
        val lap   = total - viewModel.previousLapTotalMs
        viewModel.previousLapTotalMs = total

        viewModel.lapCount++
        val totalStr = formatTime(total)
        val lapStr   = formatTime(lap)
        val row = String.format("%02d. %s (%s)", viewModel.lapCount, lapStr, totalStr)

        viewModel.lapTimes.add(0, row)
        arrayAdapter.notifyDataSetChanged()
        lapListView.smoothScrollToPosition(0)
    }

    /** ✅ 밀리초 → 천분의 1초(밀리초) 단위 (000~999) */
    private fun updateStopwatchText(time: Long) {
        val h  = TimeUnit.MILLISECONDS.toHours(time)
        val m  = TimeUnit.MILLISECONDS.toMinutes(time) % 60
        val s  = TimeUnit.MILLISECONDS.toSeconds(time) % 60
        val ms = (time % 1000)
        stopwatchText.text = String.format("%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    private fun formatTime(time: Long): String {
        val h  = TimeUnit.MILLISECONDS.toHours(time)
        val m  = TimeUnit.MILLISECONDS.toMinutes(time) % 60
        val s  = TimeUnit.MILLISECONDS.toSeconds(time) % 60
        val ms = (time % 1000)
        return String.format("%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    private fun updateButtons() {
        if (viewModel.isRunning) {
            startStopButton.text = "계속"
            lapResetButton.text = "초기화"
            lapResetButton.isEnabled = true
        } else {
            startStopButton.text = "시작"
            lapResetButton.text = "초기화"
            lapResetButton.isEnabled = (viewModel.accumulatedMs > 0L || viewModel.lapTimes.isNotEmpty())
        }
    }



    private fun ensureSecondStopwatchFragment() {
        if (childFragmentManager.findFragmentByTag(TAG_SECOND) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.secondStopwatchContainer, StopwatchSecondFragment(), TAG_SECOND)
                .commitNow()
        }
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
    }

    private fun showSecondStopwatch() {
        showingSecond = true
        applyStopwatchMode()
    }

    fun showClassicStopwatch() {
        showingSecond = false
        applyStopwatchMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOWING_SECOND, showingSecond)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updater?.let { handler.removeCallbacks(it) }
    }
}
