package com.krdonon.timer

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.krdonon.timer.alarmclock.AlarmListFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CODE_POST_NOTIFICATIONS = 1001


        private const val PREFS_UI = "ui_prefs"
        private const val KEY_LAST_TAB = "last_tab"
        private const val EXTRA_FROM_NOTIFICATION = "from_notification"

        // 🔹 알림에서 특정 탭을 직접 열기 위한 액션 (ClockService와 동일 문자열)
        const val ACTION_OPEN_TIMER = "com.krdonon.timer.action.OPEN_TIMER"
        const val ACTION_OPEN_STOPWATCH = "com.krdonon.timer.action.OPEN_STOPWATCH"
    }

    // 🔹 ViewModel 추가
    private lateinit var viewModel: TimerViewModel

    private lateinit var btnAlarm: MaterialButton
    private lateinit var btnStopwatch: MaterialButton
    private lateinit var btnTimer: MaterialButton
    private lateinit var indicatorAlarm: View
    private lateinit var indicatorStopwatch: View
    private lateinit var indicatorTimer: View

    private enum class BottomTab {
        ALARM,
        STOPWATCH,
        TIMER
    }


    private val uiPrefs by lazy { getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE) }

    private fun saveLastTab(tab: BottomTab) {
        uiPrefs.edit().putString(KEY_LAST_TAB, tab.name).apply()
    }

    private fun loadLastTab(): BottomTab {
        val saved = uiPrefs.getString(KEY_LAST_TAB, null)
        return runCatching { if (saved.isNullOrBlank()) null else BottomTab.valueOf(saved) }
            .getOrNull() ?: BottomTab.TIMER
    }

    private fun maybeSuspendNotificationUpdates(i: Intent?) {
        val fromNotif = i?.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) == true
        if (!fromNotif) return

        // 알림 패널 닫힘 애니메이션 중 알림이 계속 갱신되면(SystemUI 경합) "접히려다 마는" 현상이 발생할 수 있어
        // 아주 짧은 시간 동안 알림 갱신을 중단하도록 서비스에 요청한다.
        runCatching {
            val s = Intent(this, ClockService::class.java).apply {
                action = ClockService.ACTION_SUSPEND_NOTIFY_UPDATES
                putExtra(ClockService.EXTRA_SUSPEND_MS, 900L)
            }
            startService(s)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 🔹 ViewModel 초기화 (추가)
        viewModel = ViewModelProvider(this)[TimerViewModel::class.java]

        // ✅ 앱 시작 시 필요한 권한 요청
        requestNotificationPermission()
        requestExactAlarmPermission()
        requestBatteryOptimizationException()

        // ✅ 하단 네비게이션 뷰 참조
        btnAlarm = findViewById(R.id.btnAlarm)
        btnStopwatch = findViewById(R.id.btnStopwatch)
        btnTimer = findViewById(R.id.btnTimer)
        indicatorAlarm = findViewById(R.id.indicatorAlarm)
        indicatorStopwatch = findViewById(R.id.indicatorStopwatch)
        indicatorTimer = findViewById(R.id.indicatorTimer)

        // ✅ 버튼 리스너 연결
        btnAlarm.setOnClickListener {
            loadFragment(AlarmListFragment(), "alarm", clearBackStack = true, persistLastTab = true)
        }
        btnStopwatch.setOnClickListener {
            loadFragment(StopWatchFragment(), "stopwatch", clearBackStack = true, persistLastTab = true)
        }
        btnTimer.setOnClickListener {
            loadFragment(TimerFragment(), "timer", clearBackStack = true, persistLastTab = true)
        }

        // ✅ 알림에서 들어왔을 때 "잠깐 흐려졌다"(=초기 프래그먼트 로드 후 즉시 교체) 현상이

        // ✅ 알림에서 들어온 경우: 패널 애니메이션 경합을 줄이기 위해 알림 갱신을 잠깐 중단
        maybeSuspendNotificationUpdates(intent)

        // 발생하지 않도록, 최초 로드부터 목적 탭을 바로 선택한다.
        if (savedInstanceState == null) {
            when (resolveRequestedTab(intent)) {
                BottomTab.ALARM -> loadFragment(AlarmListFragment(), "alarm", clearBackStack = true)
                BottomTab.TIMER -> loadFragment(TimerFragment(), "timer", clearBackStack = true)
                BottomTab.STOPWATCH -> loadFragment(StopWatchFragment(), "stopwatch", clearBackStack = true)
            }
        } else {
            // 복원된 프래그먼트 상태에 맞춰 하단 탭 UI만 동기화
            syncSelectedTabFromVisibleFragment()
        }
    }

    // 🔹 앱이 다시 활성화될 때 상태 검증 (추가)
    override fun onResume() {
        super.onResume()

        // 🔹 과거 데이터 자동 제거
        viewModel.validateState()
    }

    // ✅ 싱글탑으로 재사용될 때도 알림 액션 처리
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ✅ 알림에서 들어온 경우: 알림 갱신 잠시 중단
        maybeSuspendNotificationUpdates(intent)
        handleIntentAction(intent)
    }

    /** 🔹 알림/외부 인텐트 액션 처리 (필요 시만 타이머 탭으로 전환) */
    private fun handleIntentAction(i: Intent?) {
        when (resolveRequestedTab(i)) {
            BottomTab.ALARM -> {
                val current = supportFragmentManager.findFragmentByTag("alarm")
                if (current?.isVisible != true) {
                    loadFragment(AlarmListFragment(), "alarm", clearBackStack = true)
                } else {
                    setSelectedTab(BottomTab.ALARM)
                }
            }
            BottomTab.TIMER -> {
                // 이미 타이머가 보이는 중이면 재로드하지 않음
                val current = supportFragmentManager.findFragmentByTag("timer")
                if (current?.isVisible != true) {
                    loadFragment(TimerFragment(), "timer", clearBackStack = true)
                } else {
                    setSelectedTab(BottomTab.TIMER)
                }
            }
            BottomTab.STOPWATCH -> {
                val current = supportFragmentManager.findFragmentByTag("stopwatch")
                if (current?.isVisible != true) {
                    loadFragment(StopWatchFragment(), "stopwatch", clearBackStack = true)
                } else {
                    setSelectedTab(BottomTab.STOPWATCH)
                }
            }
        }
    }

    /** 알림/외부 인텐트로 요청된 탭을 해석 */
    private fun resolveRequestedTab(i: Intent?): BottomTab {
        // ClockService에서 extra로도 넘기므로(action이 덮이거나 null이어도 안전)
        val extraAction = i?.getStringExtra("open_tab_action")?.takeIf { it.isNotBlank() }
        val action = extraAction ?: i?.action

        return when (action) {
            ACTION_OPEN_TIMER -> BottomTab.TIMER
            ACTION_OPEN_STOPWATCH -> BottomTab.STOPWATCH
            else -> loadLastTab()
        }
    }

    /** 현재 보이는 프래그먼트를 기준으로 하단 탭 선택 UI를 동기화 */
    private fun syncSelectedTabFromVisibleFragment() {
        val alarmVisible = supportFragmentManager.findFragmentByTag("alarm")?.isVisible == true
        val timerVisible = supportFragmentManager.findFragmentByTag("timer")?.isVisible == true
        setSelectedTab(
            when {
                alarmVisible -> BottomTab.ALARM
                timerVisible -> BottomTab.TIMER
                else -> BottomTab.STOPWATCH
            },
            persistLastTab = false
        )
    }

    /** ✅ 프래그먼트 교체 (NumberPad 등 백스택 지우고 전환 가능) */
    private fun loadFragment(
        fragment: Fragment,
        tag: String,
        clearBackStack: Boolean = false,
        persistLastTab: Boolean = false
    ) {
        val ft = supportFragmentManager.beginTransaction()

        if (clearBackStack) {
            // 🔑 기존에 쌓인 NumberPadFragment 같은 백스택을 모두 지움
            supportFragmentManager.popBackStackImmediate(
                null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
        }

        val existing = supportFragmentManager.findFragmentByTag(tag)

        // 현재 붙어있는 프래그먼트들 모두 숨기기
        supportFragmentManager.fragments.forEach { ft.hide(it) }

        if (existing == null) {
            ft.add(R.id.fragment_container, fragment, tag)
        } else {
            ft.show(existing)
        }

        ft.commit()

        // ✅ 탭 선택 UI 업데이트(필요 시 last_tab 저장)
        when (tag) {
            "alarm" -> setSelectedTab(BottomTab.ALARM, persistLastTab)
            "stopwatch" -> setSelectedTab(BottomTab.STOPWATCH, persistLastTab)
            "timer" -> setSelectedTab(BottomTab.TIMER, persistLastTab)
        }
    }

    /** 하단 탭 선택 시각 효과(글자 진하게/색상/인디케이터) */
    private fun setSelectedTab(tab: BottomTab, persistLastTab: Boolean = true) {
        fun apply(btn: MaterialButton, indicator: View, selected: Boolean) {
            val colorRes = if (selected) R.color.orange_500 else R.color.gray_500
            val c = ContextCompat.getColor(this, colorRes)

            btn.setTextColor(c)
            btn.iconTint = ColorStateList.valueOf(c)
            btn.typeface = Typeface.create(btn.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            btn.alpha = if (selected) 1.0f else 0.75f

            indicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }

        apply(btnAlarm, indicatorAlarm, tab == BottomTab.ALARM)
        apply(btnStopwatch, indicatorStopwatch, tab == BottomTab.STOPWATCH)
        apply(btnTimer, indicatorTimer, tab == BottomTab.TIMER)

        if (persistLastTab) saveLastTab(tab)
    }

    /** ✅ Android 13 이상: 알림 권한 요청 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_CODE_POST_NOTIFICATIONS
                )
            }
        }
    }

    /**
     * ✅ Android 12+ 정확한 알람 권한
     * - 삼성 등 일부 기기에서 "우리 앱 상세 토글"이 바로 보이도록 package: URI를 명시
     * - 예외 시 대비해 몇 가지 설정 화면으로 폴백
     */
    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                // 1차: 정확한 알람 요청 화면(우리 패키지로 바로 이동)
                val exact = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                try {
                    startActivity(exact)
                    return
                } catch (_: ActivityNotFoundException) { /* 폴백 진행 */ }

                // 2차: 앱 상세 설정
                val details = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                try {
                    startActivity(details)
                    return
                } catch (_: Exception) { /* 폴백 진행 */ }

                // 3차: 일반 알림 설정 (여기서도 "알람 및 리마인더" 진입 가능)
                val notif = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { startActivity(notif) }
            }
        }
    }

    /** ✅ Android 6.0 이상: 배터리 최적화 무시 요청 (Doze 모드 해제) */
    private fun requestBatteryOptimizationException() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { startActivity(intent) }
            }
        }
    }

    /** 권한 요청 결과 처리 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 알림 권한 허용됨
            } else {
                // 알림 권한 거부됨 → 사용자 안내 가능
            }
        }
    }
}
