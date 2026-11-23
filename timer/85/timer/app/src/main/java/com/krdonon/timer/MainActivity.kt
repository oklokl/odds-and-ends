package com.krdonon.timer

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.krdonon.timer.alarmclock.AlarmListFragment

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CODE_POST_NOTIFICATIONS = 1001

        private const val PREFS_UI = "ui_prefs"
        private const val KEY_LAST_TAB = "last_tab"
        private const val KEY_EXACT_ALARM_PROMPTED = "exact_alarm_prompted_v1"
        private const val KEY_BATTERY_OPT_PROMPTED = "battery_opt_prompted_v1"
        private const val EXTRA_FROM_NOTIFICATION = "from_notification"

        // 🔹 알림에서 특정 탭을 직접 열기 위한 액션 (ClockService와 동일 문자열)
        const val ACTION_OPEN_TIMER = "com.krdonon.timer.action.OPEN_TIMER"
        const val ACTION_OPEN_STOPWATCH = "com.krdonon.timer.action.OPEN_STOPWATCH"
    }

    private lateinit var viewModel: TimerViewModel

    // Google Play Age Signals 결과는 메모리에만 유지합니다.
    private val ageSignalsCompliance by lazy { AgeSignalsCompliance(applicationContext) }
    private var ageSignalsRequestedThisActivity = false

    private lateinit var fragmentContainer: View
    private lateinit var bottomNavBar: View
    private lateinit var btnAlarm: MaterialButton
    private lateinit var btnStopwatch: MaterialButton
    private lateinit var btnTimer: MaterialButton

    private enum class BottomTab {
        ALARM,
        STOPWATCH,
        TIMER
    }

    private val uiPrefs by lazy { getSharedPreferences(PREFS_UI, MODE_PRIVATE) }

    private fun saveLastTab(tab: BottomTab) {
        uiPrefs.edit { putString(KEY_LAST_TAB, tab.name) }
    }

    private fun loadLastTab(): BottomTab {
        val saved = uiPrefs.getString(KEY_LAST_TAB, null)
        return runCatching { if (saved.isNullOrBlank()) null else BottomTab.valueOf(saved) }
            .getOrNull() ?: BottomTab.TIMER
    }

    private fun maybeSuspendNotificationUpdates(i: Intent?) {
        val fromNotif = i?.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) == true
        if (!fromNotif) return

        runCatching {
            val s = Intent(this, ClockService::class.java).apply {
                action = ClockService.ACTION_SUSPEND_NOTIFY_UPDATES
                putExtra(ClockService.EXTRA_SUSPEND_MS, 900L)
            }
            startService(s)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContentView(R.layout.activity_main)

        setupEdgeToEdgeInsets()

        viewModel = ViewModelProvider(this)[TimerViewModel::class.java]

        // 권한 요청이 서로 겹치지 않도록 단계적으로 확인
        if (!requestNotificationPermissionIfNeeded()) {
            requestCriticalBackgroundPermissionsIfNeeded()
        }

        btnAlarm = findViewById(R.id.btnAlarm)
        btnStopwatch = findViewById(R.id.btnStopwatch)
        btnTimer = findViewById(R.id.btnTimer)

        btnAlarm.setOnClickListener {
            loadFragment(AlarmListFragment(), "alarm", clearBackStack = true, persistLastTab = true)
        }
        btnStopwatch.setOnClickListener {
            loadFragment(StopWatchFragment(), "stopwatch", clearBackStack = true, persistLastTab = true)
        }
        btnTimer.setOnClickListener {
            loadFragment(TimerFragment(), "timer", clearBackStack = true, persistLastTab = true)
        }

        maybeSuspendNotificationUpdates(intent)

        if (savedInstanceState == null) {
            when (resolveRequestedTab(intent)) {
                BottomTab.ALARM -> loadFragment(AlarmListFragment(), "alarm", clearBackStack = true)
                BottomTab.TIMER -> loadFragment(TimerFragment(), "timer", clearBackStack = true)
                BottomTab.STOPWATCH -> loadFragment(StopWatchFragment(), "stopwatch", clearBackStack = true)
            }
        } else {
            syncSelectedTabFromVisibleFragment()
        }
    }

    private fun setupEdgeToEdgeInsets() {
        val root = findViewById<View>(R.id.mainRoot)
        fragmentContainer = findViewById(R.id.fragment_container)
        bottomNavBar = findViewById(R.id.bottom_navigation_bar)

        val baseNavStart = bottomNavBar.paddingStart
        val baseNavEnd = bottomNavBar.paddingEnd
        val baseNavTop = bottomNavBar.paddingTop
        val baseNavBottom = bottomNavBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            fragmentContainer.setPadding(bars.left, bars.top, bars.right, 0)
            bottomNavBar.setPaddingRelative(
                baseNavStart + bars.left,
                baseNavTop,
                baseNavEnd + bars.right,
                baseNavBottom + bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        viewModel.validateState()

        if (!ageSignalsRequestedThisActivity) {
            ageSignalsRequestedThisActivity = true
            window.decorView.post {
                if (!isFinishing && !isDestroyed) {
                    ageSignalsCompliance.refresh(this)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeSuspendNotificationUpdates(intent)
        handleIntentAction(intent)
    }

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

    private fun resolveRequestedTab(i: Intent?): BottomTab {
        val extraAction = i?.getStringExtra("open_tab_action")?.takeIf { it.isNotBlank() }
        val action = extraAction ?: i?.action

        return when (action) {
            ACTION_OPEN_TIMER -> BottomTab.TIMER
            ACTION_OPEN_STOPWATCH -> BottomTab.STOPWATCH
            else -> loadLastTab()
        }
    }

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

    private fun loadFragment(
        fragment: Fragment,
        tag: String,
        clearBackStack: Boolean = false,
        persistLastTab: Boolean = false
    ) {
        val ft = supportFragmentManager.beginTransaction()

        if (clearBackStack) {
            supportFragmentManager.popBackStackImmediate(
                null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
        }

        val existing = supportFragmentManager.findFragmentByTag(tag)
        supportFragmentManager.fragments.forEach { ft.hide(it) }

        if (existing == null) {
            ft.add(R.id.fragment_container, fragment, tag)
        } else {
            ft.show(existing)
        }

        ft.commit()

        when (tag) {
            "alarm" -> setSelectedTab(BottomTab.ALARM, persistLastTab)
            "stopwatch" -> setSelectedTab(BottomTab.STOPWATCH, persistLastTab)
            "timer" -> setSelectedTab(BottomTab.TIMER, persistLastTab)
        }
    }

    /** 하단 탭 선택 시각 효과 (M3 Pill Indicator) */
    private fun setSelectedTab(tab: BottomTab, persistLastTab: Boolean = true) {
        val activeBg = ContextCompat.getColor(this, R.color.tab_indicator_bg)
        val activeText = ContextCompat.getColor(this, R.color.tab_active_text)
        val inactiveText = ContextCompat.getColor(this, R.color.tab_inactive_text)

        fun apply(btn: MaterialButton, selected: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(if (selected) activeBg else Color.TRANSPARENT)
            btn.setTextColor(if (selected) activeText else inactiveText)
            btn.typeface = Typeface.create(btn.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        apply(btnAlarm, tab == BottomTab.ALARM)
        apply(btnStopwatch, tab == BottomTab.STOPWATCH)
        apply(btnTimer, tab == BottomTab.TIMER)

        if (persistLastTab) saveLastTab(tab)
    }

    /** Android 13+: 알림 권한 요청. 요청 다이얼로그를 띄웠으면 true 반환 */
    private fun requestNotificationPermissionIfNeeded(): Boolean {
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
                return true
            }
        }
        return false
    }

    private fun requestCriticalBackgroundPermissionsIfNeeded() {
        if (requestExactAlarmPermissionIfNeeded()) return
        requestBatteryOptimizationExceptionIfNeeded()
    }

    private fun requestExactAlarmPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms() &&
                !uiPrefs.getBoolean(KEY_EXACT_ALARM_PROMPTED, false)
            ) {
                uiPrefs.edit { putBoolean(KEY_EXACT_ALARM_PROMPTED, true) }
                val exact = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = "package:$packageName".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                try {
                    startActivity(exact)
                    return true
                } catch (_: ActivityNotFoundException) { /* fallback */ }

                val details = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$packageName".toUri()
                )
                try {
                    startActivity(details)
                    return true
                } catch (_: Exception) { /* fallback */ }

                val notif = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (runCatching { startActivity(notif) }.isSuccess) return true
            }
        }
        return false
    }

    private fun requestBatteryOptimizationExceptionIfNeeded() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName) &&
            !uiPrefs.getBoolean(KEY_BATTERY_OPT_PROMPTED, false)
        ) {
            uiPrefs.edit { putBoolean(KEY_BATTERY_OPT_PROMPTED, true) }
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { startActivity(intent) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_POST_NOTIFICATIONS) {
            requestCriticalBackgroundPermissionsIfNeeded()
        }
    }
}
