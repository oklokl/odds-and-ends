package com.krdonon.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 재부팅 후 진행 중이던 타이머를 복원하는 BroadcastReceiver
 *
 * 🔹 Android 14+ (API 34+) 대응:
 * BOOT_COMPLETED에서 포그라운드 서비스를 직접 시작하지 않음
 * 대신 데이터만 복원하고, 사용자가 앱을 열 때 자동으로 재시작
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        // 🔹 Android 14+ 대응: BOOT_COMPLETED에서는 포그라운드 서비스를 시작하지 않음
        // 대신 SharedPreferences 데이터 정리 및 검증만 수행

        val prefs = context.getSharedPreferences("clock_persist_prefs", Context.MODE_PRIVATE)

        // 🔹 메인 타이머 검증 (System.currentTimeMillis() 기반)
        val timerEndElapsed = prefs.getLong("timer_end_elapsed", 0L)
        val timerPaused = prefs.getBoolean("timer_paused", false)

        if (timerEndElapsed > 0L && !timerPaused) {
            // 이미 종료된 타이머인지 확인
            val now = System.currentTimeMillis()
            if (timerEndElapsed < now) {
                // 이미 종료된 타이머는 제거
                prefs.edit().apply {
                    remove("timer_end_elapsed")
                    remove("timer_paused")
                    remove("timer_paused_remain")
                }.apply()
            }
        }

        // 🔹 보조 타이머 검증
        // ClockService가 시작될 때 자동으로 복원되므로 여기서는 검증만

        // 🔹 스톱워치는 재부팅 시 리셋
        prefs.edit().apply {
            remove("stopwatch_base")
            putBoolean("stopwatch_running", false)
        }.apply()

        // 🔹 중요: 사용자가 앱을 열 때 ClockService.onCreate()에서
        // 자동으로 타이머를 복원합니다.
        // BOOT_COMPLETED에서 직접 서비스를 시작하지 않습니다!
    }
}