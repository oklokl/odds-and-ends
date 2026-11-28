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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import kotlin.math.roundToInt
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.krdonon.timer.alarmclock.AlarmListFragment

class MainActivity : BaseActivity() {

    companion object {
        private const val REQ_CODE_POST_NOTIFICATIONS = 1001

        private const val PREFS_UI = "ui_prefs"
        private const val KEY_LAST_TAB = "last_tab"
        private const val KEY_STATE_CURRENT_TAB = "state_current_tab"
        private const val KEY_EXACT_ALARM_PROMPTED = "exact_alarm_prompted_v1"
        private const val KEY_BATTERY_OPT_PROMPTED = "battery_opt_prompted_v1"
        private const val EXTRA_FROM_NOTIFICATION = "from_notification"

        // 🔹 알림에서 특정 탭을 직접 열기 위한 액션 (ClockService와 동일 문자열)
        const val ACTION_OPEN_TIMER = "com.krdonon.timer.action.OPEN_TIMER"
        const val ACTION_OPEN_STOPWATCH = "com.krdonon.timer.action.OPEN_STOPWATCH"

        private const val BOTTOM_BAR_AUTO_HIDE_MS = 10_000L
    }

    private lateinit var viewModel: TimerViewModel

    // Google Play Age Signals 결과는 메모리에만 유지합니다.
    private val ageSignalsCompliance by lazy { AgeSignalsCompliance(applicationContext) }
    private var ageSignalsRequestedThisActivity = false

    private lateinit var fragmentContainer: View
    private lateinit var bottomNavContainer: View
    private lateinit var bottomNavBar: View
    private lateinit var btnAlarm: MaterialButton
    private lateinit var btnStopwatch: MaterialButton
    private lateinit var btnTimer: MaterialButton
    private lateinit var btnSettings: MaterialButton

    private val bottomBarHandler = Handler(Looper.getMainLooper())
    private val hideBottomBarRunnable = Runnable { hideBottomBar() }
    private var isBottomBarVisible = true
    private var bottomBarFraction = 1f
    private var bottomBarAnimator: ValueAnimator? = null
    private var bottomBarHeightPx = 0
    private var systemInsetLeft = 0
    private var systemInsetTop = 0
    private var systemInsetRight = 0
    private var systemInsetBottom = 0

    private enum class BottomTab {
        ALARM,
        STOPWATCH,
        TIMER,
        SETTINGS
    }

    private var currentTab: BottomTab = BottomTab.TIMER

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
        AppThemePrefs.applyTheme(this)
        val barStyle = AppThemePrefs.systemBarStyle(this)
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle,
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
        btnSettings = findViewById(R.id.btnSettings)

        btnAlarm.setOnClickListener {
            loadFragment(AlarmListFragment(), "alarm", clearBackStack = true, persistLastTab = true)
        }
        btnStopwatch.setOnClickListener {
            loadFragment(StopWatchFragment(), "stopwatch", clearBackStack = true, persistLastTab = true)
        }
        btnTimer.setOnClickListener {
            loadFragment(TimerFragment(), "timer", clearBackStack = true, persistLastTab = true)
        }
        btnSettings.setOnClickListener {
            loadFragment(SettingsFragment(), "settings", clearBackStack = true, persistLastTab = false)
        }

        maybeSuspendNotificationUpdates(intent)

        if (savedInstanceState == null) {
            when (resolveRequestedTab(intent)) {
                BottomTab.ALARM -> loadFragment(AlarmListFragment(), "alarm", clearBackStack = true)
                BottomTab.TIMER -> loadFragment(TimerFragment(), "timer", clearBackStack = true)
                BottomTab.STOPWATCH -> loadFragment(StopWatchFragment(), "stopwatch", clearBackStack = true)
                BottomTab.SETTINGS -> loadFragment(SettingsFragment(), "settings", clearBackStack = true)
            }
        } else {
            val savedTab = savedInstanceState.getString(KEY_STATE_CURRENT_TAB)
                ?.let { name -> runCatching { BottomTab.valueOf(name) }.getOrNull() }
            if (savedTab != null) {
                setSelectedTab(savedTab, persistLastTab = false)
            } else {
                syncSelectedTabFromVisibleFragment()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE_CURRENT_TAB, currentTab.name)
    }

    private fun setupEdgeToEdgeInsets() {
        val root = findViewById<View>(R.id.mainRoot)
        fragmentContainer = findViewById(R.id.fragment_container)
        bottomNavContainer = findViewById(R.id.bottom_nav_container)
        bottomNavBar = findViewById(R.id.bottom_navigation_bar)

        val baseNavStart = bottomNavBar.paddingStart
        val baseNavEnd = bottomNavBar.paddingEnd
        val baseNavTop = bottomNavBar.paddingTop
        val baseNavBottom = bottomNavBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemInsetLeft = bars.left
            systemInsetTop = bars.top
            systemInsetRight = bars.right
            systemInsetBottom = bars.bottom

            bottomNavBar.setPaddingRelative(
                baseNavStart + bars.left,
                baseNavTop,
                baseNavEnd + bars.right,
                baseNavBottom + bars.bottom,
            )
            applyBottomBarFraction(bottomBarFraction)
            bottomNavContainer.post {
                if (bottomNavContainer.height > 0) {
                    bottomBarHeightPx = bottomNavContainer.height
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyBottomBarFraction(fraction: Float) {
        bottomBarFraction = fraction.coerceIn(0f, 1f)
        val hiddenFraction = 1f - bottomBarFraction

        if (this::fragmentContainer.isInitialized) {
            fragmentContainer.setPadding(
                systemInsetLeft,
                systemInsetTop,
                systemInsetRight,
                (systemInsetBottom * hiddenFraction).roundToInt(),
            )
        }

        if (this::bottomNavContainer.isInitialized) {
            val h = bottomNavContainer.height.takeIf { it > 0 } ?: bottomBarHeightPx
            if (h > 0) {
                bottomBarHeightPx = h
                val lp = bottomNavContainer.layoutParams as? ConstraintLayout.LayoutParams
                if (lp != null) {
                    val targetMargin = (-h * hiddenFraction).roundToInt()
                    if (lp.bottomMargin != targetMargin) {
                        lp.bottomMargin = targetMargin
                        bottomNavContainer.layoutParams = lp
                    }
                }
            }
            bottomNavContainer.alpha = bottomBarFraction
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!isBottomBarVisible) {
                showBottomBar()
            }
            scheduleBottomBarAutoHide()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scheduleBottomBarAutoHide() {
        bottomBarHandler.removeCallbacks(hideBottomBarRunnable)
        bottomBarHandler.postDelayed(hideBottomBarRunnable, BOTTOM_BAR_AUTO_HIDE_MS)
    }

    private fun showBottomBar() {
        if (!this::bottomNavContainer.isInitialized) return
        if (isBottomBarVisible && bottomBarFraction >= 0.99f) return

        bottomBarAnimator?.cancel()
        bottomNavContainer.visibility = View.VISIBLE
        isBottomBarVisible = true

        val h = bottomNavContainer.height.takeIf { it > 0 } ?: bottomBarHeightPx
        if (h <= 0) {
            applyBottomBarFraction(1f)
            return
        }
        bottomBarHeightPx = h

        bottomBarAnimator = ValueAnimator.ofFloat(bottomBarFraction, 1f).apply {
            duration = 220L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                applyBottomBarFraction(anim.animatedValue as Float)
            }
            start()
        }
    }

    private fun hideBottomBar() {
        if (!this::bottomNavContainer.isInitialized || !isBottomBarVisible) return
        val h = bottomNavContainer.height.takeIf { it > 0 } ?: bottomBarHeightPx
        if (h <= 0) return

        bottomBarHeightPx = h
        bottomBarAnimator?.cancel()
        isBottomBarVisible = false

        bottomBarAnimator = ValueAnimator.ofFloat(bottomBarFraction, 0f).apply {
            duration = 250L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                applyBottomBarFraction(anim.animatedValue as Float)
            }
            doOnEnd {
                if (!isBottomBarVisible) {
                    bottomNavContainer.visibility = View.INVISIBLE
                }
            }
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.validateState()
        ClockNotificationManager.restoreIfActive(this)
        ClockService.restoreIfActive(this)
        showBottomBar()
        scheduleBottomBarAutoHide()

        if (!ageSignalsRequestedThisActivity) {
            ageSignalsRequestedThisActivity = true
            window.decorView.post {
                if (!isFinishing && !isDestroyed) {
                    ageSignalsCompliance.refresh(this)
                }
            }
        }
    }

    override fun onPause() {
        bottomBarHandler.removeCallbacks(hideBottomBarRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        bottomBarHandler.removeCallbacks(hideBottomBarRunnable)
        bottomBarAnimator?.cancel()
        super.onDestroy()
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
            BottomTab.SETTINGS -> {
                val current = supportFragmentManager.findFragmentByTag("settings")
                if (current?.isVisible != true) {
                    loadFragment(SettingsFragment(), "settings", clearBackStack = true, persistLastTab = false)
                } else {
                    setSelectedTab(BottomTab.SETTINGS, persistLastTab = false)
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
        fun isTabActive(tag: String): Boolean {
            val f = supportFragmentManager.findFragmentByTag(tag)
            return f != null && f.isAdded && !f.isHidden
        }
        val currentContainerFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        setSelectedTab(
            when {
                isTabActive("settings") -> BottomTab.SETTINGS
                isTabActive("alarm") -> BottomTab.ALARM
                isTabActive("timer") || currentContainerFragment is NumberPadFragment -> BottomTab.TIMER
                isTabActive("stopwatch") -> BottomTab.STOPWATCH
                else -> loadLastTab()
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
            "settings" -> setSelectedTab(BottomTab.SETTINGS, persistLastTab = false)
        }
    }

    /** 하단 탭 선택 시각 효과 (M3 Pill Indicator) */
    private fun setSelectedTab(tab: BottomTab, persistLastTab: Boolean = true) {
        currentTab = tab
        val activeBg = AppThemePrefs.resolveColor(this, R.attr.appTabIndicatorBg)
        val activeText = AppThemePrefs.resolveColor(this, R.attr.appTabActiveText)
        val inactiveText = AppThemePrefs.resolveColor(this, R.attr.appTabInactiveText)
        val activeStrokeColor = AppThemePrefs.resolveColor(this, R.attr.appPrimaryStrokeColor)
        val activeStrokeWidth = AppThemePrefs.resolveDimensionPx(this, R.attr.appPrimaryStrokeWidth)

        fun apply(btn: MaterialButton, selected: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(if (selected) activeBg else Color.TRANSPARENT)
            btn.strokeColor = ColorStateList.valueOf(if (selected) activeStrokeColor else Color.TRANSPARENT)
            btn.strokeWidth = if (selected) activeStrokeWidth else 0
            btn.setTextColor(if (selected) activeText else inactiveText)
            btn.typeface = Typeface.create(btn.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        apply(btnAlarm, tab == BottomTab.ALARM)
        apply(btnStopwatch, tab == BottomTab.STOPWATCH)
        apply(btnTimer, tab == BottomTab.TIMER)

        val settingsSelected = (tab == BottomTab.SETTINGS)
        btnSettings.backgroundTintList = ColorStateList.valueOf(if (settingsSelected) activeBg else Color.TRANSPARENT)
        btnSettings.strokeColor = ColorStateList.valueOf(if (settingsSelected) activeStrokeColor else Color.TRANSPARENT)
        btnSettings.strokeWidth = if (settingsSelected) activeStrokeWidth else 0
        btnSettings.iconTint = ColorStateList.valueOf(if (settingsSelected) activeText else inactiveText)

        if (persistLastTab && tab != BottomTab.SETTINGS) saveLastTab(tab)
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
