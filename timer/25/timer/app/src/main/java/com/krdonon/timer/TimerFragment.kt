package com.krdonon.timer

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import com.krdonon.timer.alarm.AlarmReceiver
import com.krdonon.timer.alarm.AlarmService
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class TimerFragment : Fragment() {

    private lateinit var timerText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var nextTimeText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnReset: Button
    private lateinit var btnAdd: Button
    private lateinit var btn10: Button
    private lateinit var btn15: Button
    private lateinit var btn30: Button
    private lateinit var btnAlertMode: Button
    private lateinit var extraTimersContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private var timeLeftInMillis: Long = 0L
    private var mainTimer: CountDownTimer? = null
    private var running = false

    private lateinit var viewModel: TimerViewModel

    // 🔹 보조 타이머의 종료 시각을 저장
    private val extraTimerEndTimes = mutableMapOf<String, Long>()

    // ====== ClockService ↔ Fragment 동기화용 브로드캐스트 ======
    private val ACTION_TIMER_STATE = "com.krdonon.timer.action.TIMER_STATE"
    private val EXTRA_STATE = "state"
    private val EXTRA_REMAIN_MS = "remain_ms"
    private val STATE_RUNNING = "RUNNING"
    private val STATE_PAUSED  = "PAUSED"
    private val STATE_STOPPED = "STOPPED"
    private val STATE_FINISHED = "FINISHED"

    // 서비스 상태 수신
    private val timerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_TIMER_STATE) return

            val state = intent.getStringExtra(EXTRA_STATE) ?: return
            val remain = intent.getLongExtra(EXTRA_REMAIN_MS, 0L).coerceAtLeast(0L)

            when (state) {
                STATE_PAUSED -> {
                    mainTimer?.cancel()
                    running = false
                    timeLeftInMillis = remain
                    btnStart.text = getString(R.string.btn_start)
                    updateMainTimerUI()
                    cancelExactAlarm()
                    viewModel.setMain(null, false)
                }
                STATE_RUNNING -> {
                    val needRestart = !running || abs(timeLeftInMillis - remain) > 1000
                    timeLeftInMillis = remain
                    updateMainTimerUI()
                    if (needRestart) startMainFromRemain(remain)
                }
                STATE_STOPPED, STATE_FINISHED -> {
                    resetMain()
                }
            }
        }
    }

    // ====== 정확 알람 ======
    private val alarmRequestCode = 10011
    private fun alarmPendingIntent(label: String = "메인 타이머"): PendingIntent {
        val intent = Intent(requireContext(), AlarmReceiver::class.java).apply {
            putExtra(AlarmService.EXTRA_LABEL, label)
        }
        return PendingIntent.getBroadcast(
            requireContext(),
            alarmRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleExactAlarm(triggerAtMillis: Long, label: String = "메인 타이머") {
        val am = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent(label)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms())
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                else am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun cancelExactAlarm() {
        val am = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(alarmPendingIntent()) }
    }

    // ====== 생명주기 ======
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_timer, container, false)

        viewModel = ViewModelProvider(requireActivity())[TimerViewModel::class.java]

        timerText = v.findViewById(R.id.timerText)
        currentTimeText = v.findViewById(R.id.currentTimeText)
        nextTimeText = v.findViewById(R.id.nextTimeText)
        btnStart = v.findViewById(R.id.btnStartTimer)
        btnReset = v.findViewById(R.id.btnResetTimer)
        btnAdd   = v.findViewById(R.id.btnAddTimer)
        btn10 = v.findViewById(R.id.btnPreset10)
        btn15 = v.findViewById(R.id.btnPreset15)
        btn30 = v.findViewById(R.id.btnPreset30)
        extraTimersContainer = v.findViewById(R.id.extraTimersContainer)

        // 알림모드 토글
        btnAlertMode = v.findViewById(R.id.btnAlertMode)
        val alarmPrefs = requireContext().getSharedPreferences(AlarmService.PREFS, Context.MODE_PRIVATE)
        var currentMode = alarmPrefs.getString(AlarmService.KEY_ALERT_MODE, AlarmService.MODE_SOUND)
            ?: AlarmService.MODE_SOUND
        fun updateAlertModeButton() { btnAlertMode.text = if (currentMode == AlarmService.MODE_VIBRATE) "진동" else "소리" }
        updateAlertModeButton()
        btnAlertMode.setOnClickListener {
            currentMode = if (currentMode == AlarmService.MODE_VIBRATE) AlarmService.MODE_SOUND else AlarmService.MODE_VIBRATE
            alarmPrefs.edit().putString(AlarmService.KEY_ALERT_MODE, currentMode).apply()
            updateAlertModeButton()
        }

        // 숫자패드 이동
        val goInput: (View) -> Unit = {
            parentFragmentManager.commit {
                replace(R.id.fragment_container, NumberPadFragment())
                addToBackStack(null)
            }
        }
        timerText.setOnClickListener(goInput)
        nextTimeText.setOnClickListener(goInput)

        // 숫자패드 결과 수신
        parentFragmentManager.setFragmentResultListener("numberPadResult", viewLifecycleOwner) { _, bundle ->
            val targetAt = bundle.getLong("targetAt", -1L)
            val dur = bundle.getLong("duration", -1L)
            when {
                targetAt > 0L -> setTargetAt(targetAt)
                dur >= 0L     -> setTargetTime(dur)
            }
        }

        // 시각 표기
        ticker = object : Runnable {
            override fun run() { updateNow(); handler.postDelayed(this, 100L) }
        }
        handler.post(ticker!!)

        btn10.setOnClickListener { setDurationAndPreview(TimeUnit.MINUTES.toMillis(10)) }
        btn15.setOnClickListener { setDurationAndPreview(TimeUnit.MINUTES.toMillis(15)) }
        btn30.setOnClickListener { setDurationAndPreview(TimeUnit.MINUTES.toMillis(30)) }

        btnStart.setOnClickListener { if (running) pauseMain() else startMain() }
        btnReset.setOnClickListener { resetMain() }
        btnAdd.setOnClickListener   { addNewBlock(timeLeftInMillis) }

        // 보조 타이머 UI 복원
        restoreExtrasUI()

        // 초기 렌더 & 메인 복원
        timerText.text = formatDuration4(0L)
        nextTimeText.text = getString(R.string.next_time_prefix, formatDuration4(0L))
        btnStart.text = getString(R.string.btn_start)
        restoreMainState()

        return v
    }

    override fun onStart() {
        super.onStart()
        // 브로드캐스트 등록
        val filter = IntentFilter(ACTION_TIMER_STATE)
        ContextCompat.registerReceiver(
            requireContext(),
            timerStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 만약 방송을 못 받았으면 Prefs로 한 번 동기화
        syncFromServiceStateIfAny()
    }

    override fun onStop() {
        super.onStop()
        runCatching { requireContext().unregisterReceiver(timerStateReceiver) }
    }

    override fun onResume() {
        super.onResume()
        val p = requireContext().getSharedPreferences(AlarmService.PREFS, Context.MODE_PRIVATE)
        val isRinging = p.getBoolean(AlarmService.KEY_RINGING, false)
        val startedFromKeyguard = p.getBoolean(AlarmService.KEY_STARTED_FROM_KEYGUARD, false)
        val km = requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (isRinging && startedFromKeyguard && !km.isKeyguardLocked) {
            AlarmService.stop(requireContext())
        }
    }

    // -------- 보조 타이머 UI --------
    private fun restoreExtrasUI() {
        extraTimersContainer.removeAllViews()
        viewModel.extras.value?.forEach { addBlockTimerView(it) }
    }

    private fun addNewBlock(initialMs: Long) {
        if (initialMs <= 0L) return
        val state = viewModel.addExtra(label = "타이머", durationMs = initialMs)
        addBlockTimerView(state)
    }

    // 🔹🔹🔹 보조 타이머 UI (알람 수정됨!) 🔹🔹🔹
    private fun addBlockTimerView(state: TimerViewModel.ExtraTimer) {
        val root = layoutInflater.inflate(R.layout.timer_block, extraTimersContainer, false)
        val labelView = root.findViewById<TextView>(R.id.blockLabel)
        val tv       = root.findViewById<TextView>(R.id.blockTimerText)
        val btnStart = root.findViewById<Button>(R.id.blockStartBtn)
        val btnDel   = root.findViewById<Button>(R.id.blockDeleteBtn)

        fun render() {
            tv.text = formatDurationShort(state.remainingMs)
            btnStart.text = if (state.running) getString(R.string.btn_pause) else getString(R.string.btn_start)
            labelView.text = state.label
        }
        render()

        tv.setOnClickListener { setTargetTime(state.remainingMs) }

        labelView.setOnClickListener {
            showRenameDialog(labelView) { newLabel ->
                viewModel.renameExtra(state.id, newLabel)
                state.label = newLabel
                render()
            }
        }

        // 🔹 핵심: 종료 시각 기준으로 남은 시간 계산
        val updateRunnable = object : Runnable {
            override fun run() {
                if (state.running) {
                    val endTime = extraTimerEndTimes[state.id] ?: 0L
                    if (endTime > 0L) {
                        val remain = (endTime - System.currentTimeMillis()).coerceAtLeast(0L)
                        state.remainingMs = remain
                        viewModel.setRemaining(state.id, remain)
                        tv.text = formatDurationShort(remain)

                        // 타이머 종료 확인
                        if (remain <= 0L) {
                            state.running = false
                            viewModel.setRunning(state.id, false)
                            btnStart.text = getString(R.string.btn_start)
                            tv.text = formatDurationShort(0L)
                            extraTimerEndTimes.remove(state.id)

                            // ✅ 알람은 ClockService에서 처리함 (UI는 업데이트만)
                            // Fragment는 백그라운드에서 동작하지 않으므로
                            // ClockService에서 알람을 울리는 것이 더 안정적

                            return
                        }
                    }

                    // 100ms마다 업데이트
                    handler.postDelayed(this, 100L)
                }
            }
        }

        btnStart.setOnClickListener {
            if (state.running) {
                // 일시정지
                handler.removeCallbacks(updateRunnable)
                viewModel.setRunning(state.id, false)
                state.running = false
                btnStart.text = getString(R.string.btn_start)
                extraTimerEndTimes.remove(state.id)
                runCatching { ClockService.stopExtraTimer(requireContext(), state.id) }
            } else {
                // 시작
                if (state.remainingMs <= 0L) return@setOnClickListener

                // 종료 시각 저장
                extraTimerEndTimes[state.id] = System.currentTimeMillis() + state.remainingMs

                viewModel.setRunning(state.id, true)
                state.running = true
                btnStart.text = getString(R.string.btn_pause)

                // ClockService 시작
                runCatching {
                    ClockService.startExtraTimer(
                        requireContext(),
                        state.id,
                        state.label,
                        state.remainingMs
                    )
                }

                // UI 업데이트 시작
                handler.post(updateRunnable)
            }
        }

        btnDel.setOnClickListener {
            handler.removeCallbacks(updateRunnable)
            extraTimerEndTimes.remove(state.id)
            viewModel.removeExtra(state.id)
            runCatching { ClockService.stopExtraTimer(requireContext(), state.id) }
            extraTimersContainer.removeView(root)
        }

        // 초기 상태가 실행 중이면 복원
        if (state.running && state.remainingMs > 0L) {
            extraTimerEndTimes[state.id] = System.currentTimeMillis() + state.remainingMs

            runCatching {
                ClockService.startExtraTimer(
                    requireContext(),
                    state.id,
                    state.label,
                    state.remainingMs
                )
            }
            handler.post(updateRunnable)
        }

        extraTimersContainer.addView(root)
    }

    // -------- 메인 타이머 --------
    private fun setTargetAt(targetAtMillis: Long) {
        timeLeftInMillis = (targetAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        updateMainTimerUI()
    }

    private fun setTargetTime(durationMillis: Long) {
        timeLeftInMillis = durationMillis.coerceAtLeast(0L)
        updateMainTimerUI()
    }

    private fun setDurationAndPreview(durationMillis: Long) {
        timeLeftInMillis = durationMillis.coerceAtLeast(0L)
        updateMainTimerUI()
    }

    private fun startMainFromRemain(remain: Long) {
        mainTimer?.cancel()
        if (remain <= 0L) { resetMain(); return }
        val endAt = System.currentTimeMillis() + remain

        mainTimer = object : CountDownTimer(remain, 10L) {
            override fun onTick(ms: Long) { timeLeftInMillis = ms; updateMainTimerUI() }
            override fun onFinish() {
                running = false
                btnStart.text = getString(R.string.btn_start)
                timerText.text = formatDuration4(0L)
                nextTimeText.text = getString(R.string.next_time_prefix, formatDuration4(0L))
                cancelExactAlarm()
                runCatching { ClockService.stopTimer(requireContext()) }
                viewModel.setMain(null, false)
            }
        }.start()

        scheduleExactAlarm(endAt, "메인 타이머")
        running = true
        btnStart.text = getString(R.string.btn_pause)
        viewModel.setMain(endAt, true)
    }

    private fun startMain() {
        if (timeLeftInMillis <= 0L) return
        startMainFromRemain(timeLeftInMillis)
        runCatching { ClockService.startTimer(requireContext(), timeLeftInMillis) }
    }

    private fun pauseMain() {
        mainTimer?.cancel()
        running = false
        btnStart.text = getString(R.string.btn_start)
        cancelExactAlarm()
        viewModel.setMain(null, false)
        runCatching { ClockService.pauseTimer(requireContext()) }
    }

    private fun resetMain() {
        mainTimer?.cancel()
        running = false
        timeLeftInMillis = 0L
        timerText.text = formatDuration4(0L)
        btnStart.text = getString(R.string.btn_start)
        nextTimeText.text = getString(R.string.next_time_prefix, formatDuration4(0L))
        cancelExactAlarm()
        runCatching { ClockService.stopTimer(requireContext()) }
        viewModel.setMain(null, false)
    }

    private fun updateMainTimerUI() {
        timerText.text = formatDuration4(timeLeftInMillis)
        nextTimeText.text = getString(R.string.next_time_prefix, formatDuration4(timeLeftInMillis))
    }

    private fun restoreMainState() {
        val endAt = viewModel.mainEndAtMs.value
        val runningSaved = viewModel.mainRunning.value == true
        if (!runningSaved || endAt == null) return

        val remain = endAt - System.currentTimeMillis()
        if (remain <= 0L) {
            viewModel.setMain(null, false)
            updateMainTimerUI()
            return
        }
        timeLeftInMillis = remain
        updateMainTimerUI()
        running = true
        btnStart.text = getString(R.string.btn_pause)

        mainTimer?.cancel()
        mainTimer = object : CountDownTimer(remain, 10L) {
            override fun onTick(ms: Long) { timeLeftInMillis = ms; updateMainTimerUI() }
            override fun onFinish() {
                running = false
                btnStart.text = getString(R.string.btn_start)
                timerText.text = formatDuration4(0L)
                nextTimeText.text = getString(R.string.next_time_prefix, formatDuration4(0L))
                viewModel.setMain(null, false)
                runCatching { ClockService.stopTimer(requireContext()) }
            }
        }.start()

        runCatching { ClockService.startTimer(requireContext(), remain) }
        scheduleExactAlarm(endAt, "메인 타이머")
    }

    // -------- 유틸 --------
    private fun syncFromServiceStateIfAny() {
        val p = requireContext().getSharedPreferences("clock_sync_prefs", Context.MODE_PRIVATE)
        val state = p.getString("key_state", null)
        val remain = p.getLong("key_remain_ms", 0L).coerceAtLeast(0L)
        if (state != null) {
            when (state) {
                STATE_PAUSED -> {
                    mainTimer?.cancel()
                    running = false
                    timeLeftInMillis = remain
                    btnStart.text = getString(R.string.btn_start)
                    updateMainTimerUI()
                    viewModel.setMain(null, false)
                }
                STATE_RUNNING -> {
                    val needRestart = !running || abs(timeLeftInMillis - remain) > 1000
                    timeLeftInMillis = remain
                    updateMainTimerUI()
                    if (needRestart) startMainFromRemain(remain)
                }
                STATE_STOPPED, STATE_FINISHED -> resetMain()
            }
        }
    }

    private fun updateNow() {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        val s = now.get(Calendar.SECOND)
        val ms = now.get(Calendar.MILLISECOND) * 10
        currentTimeText.text = String.format(Locale.getDefault(), "%02d:%02d:%02d:%04d", h, m, s, ms)
    }

    // 🔹 메인 타이머용 긴 형식 (00:00:00:0000)
    private fun formatDuration4(msTotal: Long): String {
        val m = max(0L, msTotal)
        val h = (m / 3_600_000) % 100
        val mm = (m / 60_000) % 60
        val s = (m / 1_000) % 60
        val ms4 = (m % 1_000) * 10
        return String.format(Locale.getDefault(), "%02d:%02d:%02d:%04d", h, mm, s, ms4)
    }

    // 🔹 보조 타이머용 짧은 형식 (00:00:00)
    private fun formatDurationShort(msTotal: Long): String {
        val m = max(0L, msTotal)
        val h = (m / 3_600_000) % 100
        val mm = (m / 60_000) % 60
        val s = (m / 1_000) % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, mm, s)
    }

    private fun showRenameDialog(target: TextView, onRenamed: ((String) -> Unit)? = null) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(target.text?.toString() ?: "")
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("라벨 변경")
            .setView(input)
            .setPositiveButton("확인") { dlg, _ ->
                val txt = input.text?.toString()?.ifBlank { "타이머" } ?: "타이머"
                target.text = txt
                onRenamed?.invoke(txt)
                dlg.dismiss()
            }
            .setNegativeButton("취소") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainTimer?.cancel()
        ticker?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        extraTimerEndTimes.clear()
    }
}