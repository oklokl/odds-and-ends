package com.krdonon.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import org.json.JSONArray
import org.json.JSONObject
import com.krdonon.timer.alarm.AlarmService

/**
 * 보조 타이머 종료 알람.
 * - ClockService가 백그라운드에서 종료/정지되어도 정확하게 울리도록 AlarmManager로 예약한다.
 * - 여기서 알람(소리/진동)을 시작하고, 지속 알림/저장 상태를 정리한다.
 */
class ExtraTimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(ClockService.EXTRA_ID) ?: return
        val label = intent.getStringExtra(ClockService.EXTRA_LABEL) ?: "보조 타이머"

        // 1) 알람 울리기 (Foreground 서비스)
        AlarmService.start(context, label)

        // 2) 보조 타이머 지속 알림/저장 상태 정리 (최소한의 정리)
        cleanupPersistedExtraTimer(context, id)

        // 3) 해당 개별 알림도 취소 (서비스가 살아있으면 다시 올라올 수 있음)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nid = ClockService.extraNotifyIdStatic(id)
        nm.cancel(nid)
        // 요약 알림도 남아있을 수 있어 취소(안전)
        nm.cancel(ClockService.EXTRA_SUMMARY_ID)
    }

    private fun cleanupPersistedExtraTimer(context: Context, id: String) {
        val prefs = context.getSharedPreferences(ClockService.PERSIST_PREFS_STATIC, Context.MODE_PRIVATE)
        val raw = prefs.getString(ClockService.KEY_EXTRA_TIMERS_JSON_STATIC, null) ?: return
        try {
            val arr = JSONArray(raw)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val obj: JSONObject = arr.getJSONObject(i)
                if (obj.optString("id") != id) out.put(obj)
            }
            prefs.edit().putString(ClockService.KEY_EXTRA_TIMERS_JSON_STATIC, out.toString()).apply()
        } catch (_: Exception) {
            // 파싱 실패 시엔 삭제해도 무방
            prefs.edit().remove(ClockService.KEY_EXTRA_TIMERS_JSON_STATIC).apply()
        }
    }
}
