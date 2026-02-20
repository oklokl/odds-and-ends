package com.krdonon.timer.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.krdonon.timer.ClockService
import com.krdonon.timer.alarmclock.AlarmScheduler
import com.krdonon.timer.alarmclock.AlarmStore
import com.krdonon.timer.alarmclock.SnoozeScheduler
import com.krdonon.timer.alarmclock.SnoozeStateStore

/**
 * AlarmManager가 깨워줄 때 실행됨
 * → 짧게 WakeLock을 잡아 CPU가 잠들지 않도록 하고 서비스 시작
 *
 * - 타이머(메인/추가 타이머)는 기존 AlarmService 그대로 사용
 * - 요일(반복) 알람은 WeekdayAlarmService + AlarmAgainActivity 사용 (멀티/독립 동작 보장)
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 짧은 WakeLock (최대 30초)로 서비스 시작을 안정화
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "timer:alarm_wakelock"
        ).apply { setReferenceCounted(false) }

        try {
            wl.acquire(30_000)
        } catch (_: Throwable) { /* 일부 기기에서 보안 정책으로 실패 가능 */ }

        try {
            val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)

            if (alarmId > 0) {
                // ---------- 요일(반복) 알람 ----------
                val item = AlarmStore(context).getById(alarmId) ?: return

                // 정규 알람이 울릴 때는 스누즈 카운트를 새로 시작
                SnoozeScheduler.cancel(context, alarmId) // 혹시 남아있는 스누즈가 있다면 취소
                SnoozeStateStore(context).reset(alarmId)

                WeekdayAlarmService.start(
                    context = context,
                    alarmId = item.id,
                    label = if (item.label.isBlank()) "요일 알람" else item.label,
                    soundEnabled = item.soundEnabled,
                    vibrateEnabled = item.vibrateEnabled,
                    snoozeMinutes = item.snoozeMinutes,
                    snoozeCount = item.snoozeCount,
                )

                // 다음 주기(요일 반복) 재등록
                AlarmScheduler.scheduleNext(context, alarmId)
                return
            }

            // ---------- 타이머 ----------
            val label = intent.getStringExtra(AlarmService.EXTRA_LABEL)
                ?: intent.getStringExtra("label")
                ?: "Timer Alarm"
            AlarmService.start(context, label)

            // ✅ 실기기/Doze 환경에서 CountDownTimer/ClockService 코루틴이 멈추면
            // 알람(소리)만 울리고 알림(RemoteViews Chronometer)은 종료 처리를 못 해
            // "-00:12" 처럼 음수로 계속 내려가며 버튼이 안 눌리는 상태가 발생할 수 있음.
            // 알람이 울리는 순간 ClockService에도 "메인 타이머 종료"를 통지해
            // 알림/상태를 확실히 정리한다.
            val endAt = intent.getLongExtra(ClockService.EXTRA_TIMER_END_AT_WALL, -1L)
            runCatching { ClockService.onMainTimerAlarmFired(context, endAt) }

        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}
