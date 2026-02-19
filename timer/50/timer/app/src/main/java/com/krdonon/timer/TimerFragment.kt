package com.krdonon.timer

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import com.krdonon.timer.alarm.AlarmReceiver
import com.krdonon.timer.alarm.AlarmService
import com.krdonon.timer.alarm.TimerAlarmPrefs
import com.krdonon.timer.databinding.FragmentTimerBinding
import android.provider.DocumentsContract
import kotlin.concurrent.thread
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class TimerFragment : Fragment() {

    private var _binding: FragmentTimerBinding? = null
    private val binding get() = _binding!!

    private lateinit var timerText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var nextTimeText: TextView
    private lateinit var extraSummaryText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnReset: Button
    private lateinit var btnAdd: Button
    private lateinit var presetScrollView: HorizontalScrollView
    private lateinit var presetContainer: LinearLayout

    private lateinit var btn5: Button
    private lateinit var btn10: Button
    private lateinit var btn15: Button
    private lateinit var btn30: Button
    private lateinit var btn40: Button
    private lateinit var btn50: Button
    private lateinit var btnAlertMode: Button
    private lateinit var btnPickTimerSound: Button
    private lateinit var btnTimerRingDuration: Button
    private lateinit var timerSoundNameText: TextView
    private lateinit var extraTimersContainer: LinearLayout

    // ====== Timer: mp3 선택(Activity Result) ======
    private val pickTimerSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uri: Uri? = data?.data
            if (uri != null) {
                // 1) Persist permission when possible (SAF)
                TimerAlarmPrefs.persistReadPermission(requireContext(), uri, data.flags)

                // 2) Always copy to app-private storage as fallback (works even if persist fails)
                val appCtx = requireContext().applicationContext
                thread {
                    val name = TimerAlarmPrefs.resolveDisplayName(appCtx, uri)
                    val localPath = TimerAlarmPrefs.copyToInternalStorage(appCtx, uri, name)
                    TimerAlarmPrefs.setCustom(appCtx, uri, name, localPath)
                    handler.post {
                        updateTimerSoundLabel()
                        Toast.makeText(requireContext(), "타이머 소리 설정됨", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private var timeLeftInMillis: Long = 0L
    private var mainTimer: CountDownTimer? = null
    private var running = false

    private lateinit var viewModel: TimerViewModel

    // 🔹 보조 타이머의 종료 시각을 저장
    private val extraTimerEndTimes = mutableMapOf<String, Long>()

    // ✅ 무한 루프 방지: 브로드캐스트 처리 중 플래그
    private var isProcessingBroadcast = false

    // 🔹 상태 동기화 강화: 마지막 처리한 상태 기록
    private var lastProcessedState = ""
    private var lastProcessedTime = 0L

    // ====== ClockService ↔ Fragment 동기화용 브로드캐스트 ======
    private val ACTION_TIMER_STATE = "com.krdonon.timer.action.TIMER_STATE"
    private val EXTRA_STATE = "state"
    private val EXTRA_REMAIN_MS = "remain_ms"
    private val STATE_RUNNING = "RUNNING"
    private val STATE_PAUSED  = "PAUSED"
    private val STATE_STOPPED = "STOPPED"
    private val STATE_FINISHED = "FINISHED"

    private var isReceiverRegistered = false

    // 🔹 브로드캐스트 처리 디바운싱
    private val broadcastDebouncer = Handler(Looper.getMainLooper())
    private var pendingBroadcastRunnable: Runnable? = null

    // 서비스 상태 수신
    private val timerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_TIMER_STATE) return

            val state = intent.getStringExtra(EXTRA_STATE) ?: return
            val remain = intent.getLongExtra(EXTRA_REMAIN_MS, 0L).coerceAtLeast(0L)

            android.util.Log.d("TimerFragment", "📡 브로드캐스트 수신: state=$state, remain=$remain")

            // 🔹 이전과 같은 상태가 100ms 이내에 왔으면 무시
            val now = System.currentTimeMillis()
            if (state == lastProcessedState && (now - lastProcessedTime) < 100) {
                android.util.Log.d("TimerFragment", "⏩ 중복 브로드캐스트 스킵")
                return
            }

            // 🔹 디바운싱: 이전 처리 취소하고 새로운 처리 예약
            pendingBroadcastRunnable?.let { broadcastDebouncer.removeCallbacks(it) }

            pendingBroadcastRunnable = Runnable {
                processBroadcastState(state, remain)
                lastProcessedState = state
                lastProcessedTime = System.currentTimeMillis()
            }

            // 🔹 50ms 지연 후 처리 (연속된 브로드캐스트 그룹핑)
            broadcastDebouncer.postDelayed(pendingBroadcastRunnable!!, 50)
        }
    }

    // 🔹 실제 브로드캐스트 상태 처리
    private fun processBroadcastState(state: String, remain: Long) {
        if (isProcessingBroadcast) {
            android.util.Log.d("TimerFragment", "⚠️ 이미 처리 중 - 대기열에 추가")
            handler.postDelayed({
                processBroadcastState(state, remain)
            }, 100)
            return
        }

        isProcessingBroadcast = true

        android.util.Log.d("TimerFragment", "✅ 브로드캐스트 처리: state=$state, remain=$remain")

        try {
            when (state) {
                STATE_PAUSED -> {
                    // 🔹 일시정지 상태: 앱 UI와 동기화
                    handler.post {
                        mainTimer?.cancel()
                        running = false
                        timeLeftInMillis = remain
                        btnStart.text = getString(R.string.btn_start)
                        updateMainTimerUI()
                        cancelExactAlarm()
                        viewModel.setMain(null, false)
                    }
                }
                STATE_RUNNING -> {
                    // 🔹 실행 중 상태: 타이머 재시작 필요 여부 확인
                    handler.post {
                        val needRestart = !running || abs(timeLeftInMillis - remain) > 2000
                        timeLeftInMillis = remain
                        updateMainTimerUI()
                        if (needRestart) {
                            startMainFromRemain(remain)
                        }
                    }
                }
                STATE_STOPPED, STATE_FINISHED -> {
                    // 🔹 정지/완료 상태: UI만 업데이트
                    handler.post {
                        resetMainUIOnly()
                    }
                }
            }
        } finally {
            // 🔹 처리 완료 후 플래그 해제
            handler.postDelayed({
                isProcessingBroadcast = false
            }, 100)
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
            android.util.Log.d("TimerFragment", "✅ 알람 등록: $triggerAtMillis")
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun cancelExactAlarm() {
        val am = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent()
        runCatching {
            am.cancel(pi)
            pi.cancel()
            android.util.Log.d("TimerFragment", "✅ 알람 취소됨")
        }
    }

    private fun updateTimerSoundLabel() {
        // alarm 메뉴의 표기 방식과 유사하게: "사용자: xxx.mp3" / "기본: alarm_sound.mp3"
        val name = TimerAlarmPrefs.getCustomName(requireContext())
        timerSoundNameText.text = if (name.isNullOrBlank()) {
            "기본: alarm_sound.mp3"
        } else {
            "사용자: $name"
        }
    }

    // ====== 생명주기 ======
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTimerBinding.inflate(inflater, container, false)
        val v = binding.root

        viewModel = ViewModelProvider(requireActivity())[TimerViewModel::class.java]

        timerText = binding.timerText
        currentTimeText = binding.currentTimeText
        nextTimeText = binding.nextTimeText
        extraSummaryText = binding.extraSummaryText
        btnStart = binding.btnStartTimer
        btnReset = binding.btnResetTimer
        btnAdd   = binding.btnAddTimer

        presetScrollView = binding.presetScrollView
        presetContainer  = binding.presetContainer
        btn5  = binding.btnPreset5
        btn10 = binding.btnPreset10
        btn15 = binding.btnPreset15
        btn30 = binding.btnPreset30
        btn40 = binding.btnPreset40
        btn50 = binding.btnPreset50

        extraTimersContainer = binding.extraTimersContainer

        timerSoundNameText = binding.timerSoundNameText

        // 프리셋 버튼 라벨(표시용): 00:10:00 -> 10분
        // ※ 텍스트 자동 크기 조절은 layout(fragment_timer.xml)의 autoSize 속성으로 처리
        btn5.text  = formatPresetLabel(TimeUnit.MINUTES.toMillis(5))
        btn10.text = formatPresetLabel(TimeUnit.MINUTES.toMillis(10))
        btn15.text = formatPresetLabel(TimeUnit.MINUTES.toMillis(15))
        btn30.text = formatPresetLabel(TimeUnit.MINUTES.toMillis(30))
        btn40.text = formatPresetLabel(TimeUnit.MINUTES.toMillis(40))
        btn50.text = formatPresetLabel(TimeUnit.MINUTES.toMillis(50))

        setupPresetSlider()

        // 알림모드 토글
        btnAlertMode = binding.btnAlertMode
        btnPickTimerSound = binding.btnPickTimerSound
        btnTimerRingDuration = binding.btnTimerRingDuration
        updateTimerSoundLabel()
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

        // ====== 타이머 mp3 선택(알림 메뉴와 분리) ======
        fun launchTimerSoundPicker() {
            val initialUri = runCatching {
                Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
            }.getOrNull()

            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                if (initialUri != null) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
            }
            pickTimerSoundLauncher.launch(intent)
        }

        btnPickTimerSound.setOnClickListener { launchTimerSoundPicker() }
        btnPickTimerSound.setOnLongClickListener {
            TimerAlarmPrefs.clearCustom(requireContext())
            updateTimerSoundLabel()
            Toast.makeText(requireContext(), "타이머 소리: 기본으로 복원", Toast.LENGTH_SHORT).show()
            true
        }

        // ====== 타이머 소리 지속 시간(5~60분 + 연속) ======
        fun showTimerRingDurationDialog() {
            val prefs = requireContext().getSharedPreferences(AlarmService.PREFS, Context.MODE_PRIVATE)
            val currentForever = prefs.getBoolean(TimerAlarmPrefs.KEY_TIMER_RING_FOREVER, false)
            val currentMinutes = prefs.getInt(TimerAlarmPrefs.KEY_TIMER_RING_DURATION_MINUTES, 5).coerceIn(5, 60)

            val labels = (5..60).map { "${it}분" } + listOf("연속")
            val picker = NumberPicker(requireContext()).apply {
                minValue = 0
                maxValue = labels.size - 1
                displayedValues = labels.toTypedArray()
                wrapSelectorWheel = false
                value = if (currentForever) labels.size - 1 else (currentMinutes - 5)
            }

            AlertDialog.Builder(requireContext())
                .setTitle("지속 설정")
                .setMessage("5분부터 60분까지, 또는 연속을 선택할 수 있습니다.")
                .setView(picker)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val idx = picker.value
                    val isForever = (idx == labels.size - 1)
                    val minutes = (idx + 5).coerceIn(5, 60)
                    prefs.edit()
                        .putBoolean(TimerAlarmPrefs.KEY_TIMER_RING_FOREVER, isForever)
                        .putInt(TimerAlarmPrefs.KEY_TIMER_RING_DURATION_MINUTES, minutes)
                        .apply()

                    val msg = if (isForever) "타이머 지속: 연속" else "타이머 지속: ${minutes}분"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        btnTimerRingDuration.setOnClickListener { showTimerRingDurationDialog() }
        btnTimerRingDuration.setOnLongClickListener {
            val prefs = requireContext().getSharedPreferences(AlarmService.PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(TimerAlarmPrefs.KEY_TIMER_RING_FOREVER, false)
                .putInt(TimerAlarmPrefs.KEY_TIMER_RING_DURATION_MINUTES, 5)
                .apply()
            Toast.makeText(requireContext(), "타이머 지속: 5분(기본)으로 복원", Toast.LENGTH_SHORT).show()
            true
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
            override fun run() { updateNow(); handler.postDelayed(this, 10L) }
        }
        handler.post(ticker!!)

        btn5.setOnClickListener  { setDurationAndPreview(TimeUnit.MINUTES.toMillis(5)) }
        btn10.setOnClickListener { setDurationAndPreview(TimeUnit.MINUTES.toMillis(10)) }
        btn15.setOnClickListener { setDurationAndPreview(TimeUnit.MINUTES.toMillis(15)) }
        btn30.setOnClickListener { setDurationAndPreview(TimeUnit.MINUTES.toMillis(30)) }
        btn40.setOnClickListener { setDurationAndPreview(TimeUnit.MINUTES.toMillis(40)) }
        btn50.setOnClickListener { setDurationAndPreview(TimeUnit.MINUTES.toMillis(50)) }

        btnStart.setOnClickListener { if (running) pauseMain() else startMain() }
        btnReset.setOnClickListener { resetMain() }
        btnAdd.setOnClickListener   { addNewBlock(timeLeftInMillis) }

        // 보조 타이머 UI 복원
        restoreExtrasUI()

        // 초기 렌더 & 메인 복원
        timerText.text = formatDuration4(0L)
        nextTimeText.text = getString(R.string.next_time_prefix, "--")
        btnStart.text = getString(R.string.btn_start)
        restoreMainState()

        // 브로드캐스트 리시버 등록
        registerTimerStateReceiver()

        return v
    }

    private fun registerTimerStateReceiver() {
        if (isReceiverRegistered) return

        val filter = IntentFilter(ACTION_TIMER_STATE)
        ContextCompat.registerReceiver(
            requireContext(),
            timerStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
        android.util.Log.d("TimerFragment", "✅ 브로드캐스트 리시버 등록됨")
    }

    private fun unregisterTimerStateReceiver() {
        if (!isReceiverRegistered) return

        runCatching {
            requireContext().unregisterReceiver(timerStateReceiver)
            isReceiverRegistered = false
            android.util.Log.d("TimerFragment", "✅ 브로드캐스트 리시버 해제됨")
        }
    }

    override fun onResume() {
        super.onResume()

        // 🔹 약간의 지연 후 동기화 (서비스 상태가 안정화될 시간 확보)
        handler.postDelayed({
            syncFromServiceStateIfAny()
            validateAndSyncExtraTimers()
        }, 100)

        val p = requireContext().getSharedPreferences(AlarmService.PREFS, Context.MODE_PRIVATE)
        val isRinging = p.getBoolean(AlarmService.KEY_RINGING, false)
        val startedFromKeyguard = p.getBoolean(AlarmService.KEY_STARTED_FROM_KEYGUARD, false)
        val km = requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (isRinging && startedFromKeyguard && !km.isKeyguardLocked) {
            AlarmService.stop(requireContext())
        }
    }

    // 🔹 보조 타이머 상태 검증
    private fun validateAndSyncExtraTimers() {
        val now = System.currentTimeMillis()

        // 각 보조 타이머 검사
        viewModel.extras.value?.forEach { timer ->
            if (timer.running) {
                // endTime이 있는지 확인
                val endTime = extraTimerEndTimes[timer.id]

                if (endTime != null) {
                    // 종료 시간이 지났으면 타이머 정지
                    if (endTime <= now) {
                        // 타이머가 이미 완료된 상태
                        timer.running = false
                        timer.remainingMs = 0L
                        viewModel.setRunning(timer.id, false)
                        viewModel.setRemaining(timer.id, 0L)
                        extraTimerEndTimes.remove(timer.id)

                        // UI 업데이트를 위해 뷰 다시 그리기
                        restoreExtrasUI()

                        android.util.Log.d("TimerFragment", "✅ 완료된 보조 타이머 정리: ${timer.label}")
                    } else {
                        // 남은 시간 업데이트
                        val remaining = (endTime - now).coerceAtLeast(0L)
                        timer.remainingMs = remaining
                        viewModel.setRemaining(timer.id, remaining)
                    }
                } else if (timer.remainingMs > 0) {
                    // endTime이 없지만 remainingMs가 있는 경우 (비정상 상태)
                    // endTime을 재계산
                    extraTimerEndTimes[timer.id] = now + timer.remainingMs
                } else {
                    // 실행 중이지만 시간이 0인 경우 - 정지 처리
                    timer.running = false
                    viewModel.setRunning(timer.id, false)
                    restoreExtrasUI()
                }
            }
        }

        // ClockService의 실제 상태와 동기화
        syncExtraTimersWithService()
    }

    // 🔹 ClockService와 동기화
    private fun syncExtraTimersWithService() {
        // ClockService의 지속 저장 데이터 확인
        val prefs = requireContext().getSharedPreferences("clock_persist_prefs", Context.MODE_PRIVATE)
        val extrasJson = prefs.getString("extra_timers_json", null)

        if (extrasJson.isNullOrBlank()) {
            // 서비스에 보조 타이머가 없으면 모든 타이머 정지
            viewModel.extras.value?.forEach { timer ->
                if (timer.running) {
                    timer.running = false
                    timer.remainingMs = 0L
                    viewModel.setRunning(timer.id, false)
                    viewModel.setRemaining(timer.id, 0L)
                    extraTimerEndTimes.remove(timer.id)
                }
            }
            restoreExtrasUI()
        }
    }

    // -------- 보조 타이머 UI --------
    private fun restoreExtrasUI() {
        extraTimersContainer.removeAllViews()
        viewModel.extras.value?.forEach { addBlockTimerView(it) }
        updateExtraSummaryUI()
    }

    private fun addNewBlock(initialMs: Long) {
        if (initialMs <= 0L) return
        val state = viewModel.addExtra(label = "타이머", durationMs = initialMs)
        addBlockTimerView(state)
        updateExtraSummaryUI()
    }

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

        val updateRunnable = object : Runnable {
            override fun run() {
                if (state.running) {
                    val endTime = extraTimerEndTimes[state.id] ?: 0L
                    if (endTime > 0L) {
                        val remain = (endTime - System.currentTimeMillis()).coerceAtLeast(0L)
                        state.remainingMs = remain
                        viewModel.setRemaining(state.id, remain)
                        tv.text = formatDurationShort(remain)
                        updateExtraSummaryUI()

                        if (remain <= 0L) {
                            state.running = false
                            viewModel.setRunning(state.id, false)
                            btnStart.text = getString(R.string.btn_start)
                            tv.text = formatDurationShort(0L)
                            extraTimerEndTimes.remove(state.id)
                            return
                        }
                    }
                    handler.postDelayed(this, 100L)
                }
            }
        }

        btnStart.setOnClickListener {
            if (state.running) {
                handler.removeCallbacks(updateRunnable)
                viewModel.setRunning(state.id, false)
                state.running = false
                btnStart.text = getString(R.string.btn_start)
                extraTimerEndTimes.remove(state.id)
                runCatching { ClockService.stopExtraTimer(requireContext(), state.id) }
                updateExtraSummaryUI()
            } else {
                if (state.remainingMs <= 0L) return@setOnClickListener

                extraTimerEndTimes[state.id] = System.currentTimeMillis() + state.remainingMs
                viewModel.setRunning(state.id, true)
                state.running = true
                btnStart.text = getString(R.string.btn_pause)

                runCatching {
                    ClockService.startExtraTimer(
                        requireContext(),
                        state.id,
                        state.label,
                        state.remainingMs
                    )
                }
                handler.post(updateRunnable)
                updateExtraSummaryUI()
            }
        }

        btnDel.setOnClickListener {
            handler.removeCallbacks(updateRunnable)
            extraTimerEndTimes.remove(state.id)
            viewModel.removeExtra(state.id)
            runCatching { ClockService.stopExtraTimer(requireContext(), state.id) }
            extraTimersContainer.removeView(root)
            updateExtraSummaryUI()
        }

        if (state.running && state.remainingMs > 0L) {
            // 🔹 수정: endTime이 이미 있는 경우 그대로 사용
            if (!extraTimerEndTimes.containsKey(state.id)) {
                extraTimerEndTimes[state.id] = System.currentTimeMillis() + state.remainingMs
            }

            runCatching {
                // 🔹 수정: 실제 남은 시간으로 서비스 시작
                val actualRemaining = (extraTimerEndTimes[state.id]!! - System.currentTimeMillis()).coerceAtLeast(0L)
                if (actualRemaining > 0L) {
                    ClockService.startExtraTimer(
                        requireContext(),
                        state.id,
                        state.label,
                        actualRemaining
                    )
                }
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
                nextTimeText.text = getString(R.string.next_time_prefix, "--")
                cancelExactAlarm()

                // ✅ 서비스 호출 (브로드캐스트가 전송됨)
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

        // 🔹 서비스 호출 강화: 재시도 로직 추가
        runCatching {
            ClockService.pauseTimer(requireContext())
        }.onFailure {
            // 실패 시 100ms 후 재시도
            handler.postDelayed({
                runCatching { ClockService.pauseTimer(requireContext()) }
            }, 100)
        }

        android.util.Log.d("TimerFragment", "✅ pauseMain() 호출 완료")
    }

    private fun resetMain() {
        mainTimer?.cancel()
        running = false
        timeLeftInMillis = 0L
        timerText.text = formatDuration4(0L)
        btnStart.text = getString(R.string.btn_start)
        nextTimeText.text = getString(R.string.next_time_prefix, "--")
        cancelExactAlarm()

        // 🔹 서비스 호출 강화: 재시도 로직 추가
        runCatching {
            ClockService.stopTimer(requireContext())
        }.onFailure {
            // 실패 시 100ms 후 재시도
            handler.postDelayed({
                runCatching { ClockService.stopTimer(requireContext()) }
            }, 100)
        }

        viewModel.setMain(null, false)

        android.util.Log.d("TimerFragment", "✅ resetMain() 호출 완료")
    }

    // ✅ 브로드캐스트 수신 시 호출: 서비스 호출 없이 UI만 업데이트
    private fun resetMainUIOnly() {
        mainTimer?.cancel()
        running = false
        timeLeftInMillis = 0L
        timerText.text = formatDuration4(0L)
        btnStart.text = getString(R.string.btn_start)
        nextTimeText.text = getString(R.string.next_time_prefix, "--")
        cancelExactAlarm()
        viewModel.setMain(null, false)

        android.util.Log.d("TimerFragment", "✅ resetMainUIOnly() - 브로드캐스트 수신 전용")
    }
    private fun updateMainTimerUI() {
        timerText.text = formatDuration4(timeLeftInMillis)

        val alarmText = if (timeLeftInMillis <= 0L) {
            "--"
        } else {
            val endAt = if (running) {
                viewModel.mainEndAtMs.value ?: (System.currentTimeMillis() + timeLeftInMillis)
            } else {
                System.currentTimeMillis() + timeLeftInMillis
            }
            formatEndAtKorean(endAt)
        }

        nextTimeText.text = getString(R.string.next_time_prefix, alarmText)
    }

    private fun updateExtraSummaryUI() {
        // 보조 타이머 요약(메인 화면 하단 작은 글씨)
        val extras = viewModel.extras.value ?: emptyList()
        val activeExtras = extras.filter { it.remainingMs > 0L }
        if (!this::extraSummaryText.isInitialized) return

        if (activeExtras.isEmpty()) {
            extraSummaryText.visibility = View.GONE
            extraSummaryText.text = ""
            return
        }

        val now = System.currentTimeMillis()

        val parts = activeExtras.take(3).map { t ->
            val remain = t.remainingMs.coerceAtLeast(0L)
            if (t.running) {
                val endAt = extraTimerEndTimes[t.id] ?: (now + remain)
                val endText = formatEndAtKorean(endAt)
                // 예: "타이머 00:28:10 (내일 1월 4일 오후 3:12)"
                "${t.label} ${formatDurationShort(remain)} (${endText})"
            } else {
                // 예: "타이머 00:28:10 (일시정지)"
                "${t.label} ${formatDurationShort(remain)} (일시정지)"
            }
        }

        var text = "보조 타이머: " + parts.joinToString(" · ")
        if (activeExtras.size > 3) text += " …"

        extraSummaryText.text = text
        extraSummaryText.visibility = View.VISIBLE
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
                nextTimeText.text = getString(R.string.next_time_prefix, "--")
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

        android.util.Log.d("TimerFragment", "✅ syncFromServiceStateIfAny: state=$state, remain=$remain")

        if (state != null) {
            // ✅ 상태를 읽은 후 즉시 clear (중복 처리 방지)
            p.edit().remove("key_state").remove("key_remain_ms").apply()

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
                    val needRestart = !running || abs(timeLeftInMillis - remain) > 2000
                    timeLeftInMillis = remain
                    updateMainTimerUI()
                    if (needRestart) startMainFromRemain(remain)
                }
                STATE_STOPPED, STATE_FINISHED -> {
                    // ✅ timeLeftInMillis가 0이 아니면 유지 (NumberPad에서 돌아온 경우)
                    if (timeLeftInMillis == 0L) {
                        resetMainUIOnly()
                    }
                }
            }
        }
    }

    private fun updateNow() {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        val s = now.get(Calendar.SECOND)
        val ms = now.get(Calendar.MILLISECOND)
        currentTimeText.text = String.format(Locale.getDefault(), "%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    private fun formatDuration4(msTotal: Long): String {
        val t = max(0L, msTotal)
        val h = (t / 3_600_000)
        val mm = (t / 60_000) % 60
        val s = (t / 1_000) % 60
        val ms = t % 1_000
        return String.format(Locale.getDefault(), "%02d:%02d:%02d.%03d", h, mm, s, ms)
    }

    private fun formatDurationShort(msTotal: Long): String {
        val m = max(0L, msTotal)
        val h = (m / 3_600_000)
        val mm = (m / 60_000) % 60
        val s = (m / 1_000) % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, mm, s)
    }


    private fun formatEndAtKorean(endAtMillis: Long): String {
        val now = java.util.Calendar.getInstance()
        val today0 = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val end = java.util.Calendar.getInstance().apply { timeInMillis = endAtMillis }
        val end0 = (end.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val diffDays = ((end0 - today0) / 86_400_000L).toInt()

        val date = java.util.Date(endAtMillis)
        val timeFmt = java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREA)
        val dayFmt = java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREA)

        return when (diffDays) {
            0 -> "오늘 ${timeFmt.format(date)}"
            1 -> "내일 ${dayFmt.format(date)} ${timeFmt.format(date)}"
            else -> "${dayFmt.format(date)} ${timeFmt.format(date)}"
        }
    }


    // ====== 프리셋(5/10/15/30/40/50) 슬라이더 + 스냅 ======
    private fun setupPresetSlider() {
        // 초기에는 10/15/30이 기본으로 보이도록 15분을 가운데로 위치
        binding.root.post { centerPresetOn(btn15) }

        // 스크롤이 멈추면 가장 가까운 버튼으로 스냅(터치 드래그/플링 모두 대응)
        val snapRunnable = Runnable { snapPresetToNearest() }

        fun scheduleSnap() {
            presetScrollView.removeCallbacks(snapRunnable)
            presetScrollView.postDelayed(snapRunnable, 120L)
        }

        presetScrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            scheduleSnap()
        }

        presetScrollView.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_UP ||
                ev.action == android.view.MotionEvent.ACTION_CANCEL
            ) {
                scheduleSnap()
            }
            false
        }
    }

    private fun centerPresetOn(target: View) {
        val viewport = presetScrollView.width
        if (viewport <= 0) return

        val targetCenter = target.left + (target.width / 2)
        val desiredScrollX = targetCenter - (viewport / 2)

        val maxScroll = max(0, presetContainer.width - viewport)
        presetScrollView.scrollTo(desiredScrollX.coerceIn(0, maxScroll), 0)
    }

    private fun snapPresetToNearest() {
        val viewport = presetScrollView.width
        if (viewport <= 0) return

        val centerX = presetScrollView.scrollX + (viewport / 2)

        var bestChild: View? = null
        var bestDist = Long.MAX_VALUE

        for (i in 0 until presetContainer.childCount) {
            val child = presetContainer.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val childCenter = child.left + (child.width / 2)
            val dist = abs((childCenter - centerX).toLong())
            if (dist < bestDist) {
                bestDist = dist
                bestChild = child
            }
        }

        val target = bestChild ?: return
        val targetCenter = target.left + (target.width / 2)
        val desiredScrollX = targetCenter - (viewport / 2)

        val maxScroll = max(0, presetContainer.width - viewport)
        presetScrollView.smoothScrollTo(desiredScrollX.coerceIn(0, maxScroll), 0)
    }


    /**
     * 프리셋(원형 버튼) 표시용 라벨
     * - 10분, 15분, 30분 등
     * - 향후 1시간, 1시간 30분 같은 케이스도 자연스럽게 표시
     */
    private fun formatPresetLabel(durationMillis: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(max(0L, durationMillis))
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}시간 ${minutes}분"
            hours > 0 -> "${hours}시간"
            else -> "${totalMinutes}분"
        }
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

        _binding = null

        // 🔹 정리 작업 강화
        pendingBroadcastRunnable?.let {
            broadcastDebouncer.removeCallbacks(it)
        }
        pendingBroadcastRunnable = null

        mainTimer?.cancel()
        ticker?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        broadcastDebouncer.removeCallbacksAndMessages(null)
        extraTimerEndTimes.clear()

        unregisterTimerStateReceiver()
    }
}