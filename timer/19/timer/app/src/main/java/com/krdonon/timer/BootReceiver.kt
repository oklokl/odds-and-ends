package com.krdonon.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 재부팅 후 진행 중이던 타이머를 복원하는 BroadcastReceiver
 * - 단, 재부팅으로 인해 SystemClock.elapsedRealtime()이 초기화되므로
 *   wall clock 기준 종료 시각을 별도 저장해야 완벽한 복원 가능
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        val prefs = context.getSharedPreferences("clock_persist_prefs", Context.MODE_PRIVATE)

        // 메인 타이머 복원
        val timerEndElapsed = prefs.getLong("timer_end_elapsed", 0L)
        val timerPaused = prefs.getBoolean("timer_paused", false)
        val pausedRemain = prefs.getLong("timer_paused_remain", 0L)

        if (timerPaused && pausedRemain > 0L) {
            // 일시정지 상태 복원 (일시정지는 wall clock 영향 없음)
            val i = Intent(context, ClockService::class.java).apply {
                action = "com.krdonon.timer.action.START_TIMER"
                putExtra("duration_ms", pausedRemain)
            }
            ContextCompat.startForegroundService(context, i)

            // 바로 일시정지
            val pauseIntent = Intent(context, ClockService::class.java).apply {
                action = "com.krdonon.timer.action.PAUSE_TIMER"
            }
            context.startService(pauseIntent)
        } else if (timerEndElapsed > 0L) {
            // 실행 중이던 타이머는 elapsedRealtime 기준이라 재부팅 후 복원 불가
            // 대신 wall clock 기준 종료 시각을 저장하도록 개선 필요
            // 현재는 단순히 서비스만 재시작 (상태는 ClockService.onCreate에서 복원)
            val i = Intent(context, ClockService::class.java).apply {
                action = "com.krdonon.timer.action.KEEP_ALIVE"
            }
            ContextCompat.startForegroundService(context, i)
        }

        // 스톱워치 복원
        val swBase = prefs.getLong("stopwatch_base", 0L)
        val swRunning = prefs.getBoolean("stopwatch_running", false)
        if (swRunning && swBase > 0L) {
            // 스톱워치도 elapsedRealtime 기준이라 재부팅 후 정확한 복원 불가
            // wall clock으로 시작 시각을 저장하도록 개선 권장
            val i = Intent(context, ClockService::class.java).apply {
                action = "com.krdonon.timer.action.START_STOPWATCH"
                putExtra("stopwatch_base", 0L) // 재부팅 후 0부터 시작
            }
            ContextCompat.startForegroundService(context, i)
        }
    }
}