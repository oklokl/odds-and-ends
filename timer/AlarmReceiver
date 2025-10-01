package com.krdonon.timer.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * AlarmManager가 깨워줄 때 실행됨
 * → 짧게 WakeLock을 잡아 CPU가 잠들지 않도록 하고 서비스 시작
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(AlarmService.EXTRA_LABEL)
            ?: intent.getStringExtra("label")
            ?: "Timer Alarm"

        // 짧은 WakeLock (최대 30초)로 서비스 시작을 안정화
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "timer:alarm_wakelock"
        ).apply { setReferenceCounted(false) }

        try {
            wl.acquire(30_000) // 30초 한도
        } catch (_: Throwable) { /* 일부 기기에서 보안 정책으로 실패 가능 */ }

        try {
            AlarmService.start(context, label)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}
