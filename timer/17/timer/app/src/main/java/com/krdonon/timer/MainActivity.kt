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
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CODE_POST_NOTIFICATIONS = 1001

        // 🔹 알림에서 타이머 탭을 직접 열기 위한 액션 (ClockService와 동일 문자열)
        const val ACTION_OPEN_TIMER = "com.krdonon.timer.action.OPEN_TIMER"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ✅ 앱 시작 시 필요한 권한 요청
        requestNotificationPermission()
        requestExactAlarmPermission()
        requestBatteryOptimizationException()

        // ✅ 초기 화면은 스톱워치
        if (savedInstanceState == null) {
            loadFragment(StopWatchFragment(), "stopwatch", clearBackStack = true)
        }

        // ✅ 버튼 리스너 연결
        findViewById<Button>(R.id.btnStopwatch).setOnClickListener {
            loadFragment(StopWatchFragment(), "stopwatch", clearBackStack = true)
        }
        findViewById<Button>(R.id.btnTimer).setOnClickListener {
            loadFragment(TimerFragment(), "timer", clearBackStack = true)
        }

        // ✅ 알림에서 진입했는지 확인하여 바로 타이머 탭으로 전환
        handleIntentAction(intent)
    }

    // ✅ 싱글탑으로 재사용될 때도 알림 액션 처리
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAction(intent)
    }

    /** 🔹 알림/외부 인텐트 액션 처리 (필요 시만 타이머 탭으로 전환) */
    private fun handleIntentAction(i: Intent?) {
        when (i?.action) {
            ACTION_OPEN_TIMER -> {
                loadFragment(TimerFragment(), "timer", clearBackStack = true)
            }
            // 추후 필요하면 여기서 다른 액션도 분기 가능
        }
    }

    /** ✅ 프래그먼트 교체 (NumberPad 등 백스택 지우고 전환 가능) */
    private fun loadFragment(fragment: Fragment, tag: String, clearBackStack: Boolean = false) {
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
     * - 삼성 등 일부 기기에서 “우리 앱 상세 토글”이 바로 보이도록 package: URI를 명시
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

                // 3차: 일반 알림 설정 (여기서도 “알람 및 리마인더” 진입 가능)
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
