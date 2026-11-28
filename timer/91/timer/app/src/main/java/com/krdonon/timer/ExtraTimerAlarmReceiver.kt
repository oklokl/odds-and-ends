package com.krdonon.timer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.krdonon.timer.alarm.AlarmService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 보조 타이머 종료 알람.
 * - ClockService가 백그라운드에서 종료/정지되어도 정확하게 울리도록 AlarmManager로 예약한다.
 * - 여기서 알람(소리/진동)을 시작하고, 지속 알림/저장 상태를 정리한다.
 */
class ExtraTimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(ClockService.EXTRA_ID) ?: return
        val lCtx = AppLanguagePrefs.wrapContext(context)
        val fallbackExtra = lCtx.getString(R.string.extra_timer_section_title)
        val rawLabel = intent.getStringExtra(ClockService.EXTRA_LABEL) ?: fallbackExtra
        val alarmLabel = ClockService.formatNotificationTimerTitle(lCtx, rawLabel, defaultFallback = fallbackExtra)

        // 1) 알람 울리기 (Foreground 서비스)
        AlarmService.start(context, alarmLabel)

        // 2) 보조 타이머 지속 알림/저장 상태 정리 (최소한의 정리)
        val meta = cleanupPersistedExtraTimer(context, id)

        // 2.5) 서비스가 살아있다면 해당 보조 타이머를 종료(STATE_FINISHED)로 정리
        runCatching { ClockService.onExtraTimerAlarmFired(context, id) }

        // 2.8) 서비스가 죽어 있던 경우에도 UI가 살아있다면 복원/삭제를 반영하도록 브로드캐스트 전송
        runCatching {
            val stateIntent = Intent(ClockService.ACTION_EXTRA_STATE).apply {
                putExtra(ClockService.EXTRA_ID, id)
                putExtra("state", "FINISHED")
                putExtra("remain_ms", 0L)
                putExtra(ClockService.EXTRA_TIMER_END_AT_WALL, 0L)
                putExtra(ClockService.EXTRA_LABEL, rawLabel)
                putExtra(ClockService.EXTRA_LOCKED, meta.first)
                putExtra("total_duration_ms", meta.second)
                setPackage(context.packageName)
            }
            context.sendBroadcast(stateIntent)
        }

        // 3) 해당 개별 알림도 취소 (서비스가 살아있으면 다시 올라올 수 있음)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nid = ClockService.extraNotifyIdStatic(id)
        nm.cancel(nid)
        // 요약 알림도 남아있을 수 있어 취소(안전)
        nm.cancel(ClockService.EXTRA_SUMMARY_ID)
        // Foreground 리더가 무엇이었든 남아있을 수 있으므로 모두 정리
        nm.cancel(ClockService.FOREGROUND_ID_TIMER)
        nm.cancel(ClockService.FOREGROUND_ID_EXTRA)
        nm.cancel(ClockService.FOREGROUND_ID_STOPWATCH)
        nm.cancel(ClockService.FOREGROUND_ID_STOPWATCH2)
    }

    private fun cleanupPersistedExtraTimer(context: Context, id: String): Pair<Boolean, Long> {
        val prefs = context.getSharedPreferences(ClockService.PERSIST_PREFS_STATIC, Context.MODE_PRIVATE)
        val raw = prefs.getString(ClockService.KEY_EXTRA_TIMERS_JSON_STATIC, null) ?: return Pair(false, 0L)
        var locked = false
        var totalDurationMs = 0L
        try {
            val arr = JSONArray(raw)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val obj: JSONObject = arr.getJSONObject(i)
                if (obj.optString("id") == id) {
                    locked = obj.optBoolean("locked", false)
                    totalDurationMs = obj.optLong("durationMs", 0L)
                } else {
                    out.put(obj)
                }
            }
            prefs.edit().putString(ClockService.KEY_EXTRA_TIMERS_JSON_STATIC, out.toString()).apply()
        } catch (_: Exception) {
            // 파싱 실패 시엔 삭제해도 무방
            prefs.edit().remove(ClockService.KEY_EXTRA_TIMERS_JSON_STATIC).apply()
        }
        return Pair(locked, totalDurationMs)
    }
}
