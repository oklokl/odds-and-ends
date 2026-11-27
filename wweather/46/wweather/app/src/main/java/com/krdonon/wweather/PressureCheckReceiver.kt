package com.krdonon.wweather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 레거시 백그라운드 기압 체크 리시버.
 *
 * 현재 앱 정책상 백그라운드 실행 및 백그라운드 알림을 사용하지 않으므로
 * AndroidManifest에서 비활성화(android:enabled="false")되어 있으며,
 * 호출되더라도 아무 작업 없이 즉시 반환합니다.
 */
class PressureCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AlarmScheduler.cancel(context.applicationContext)
    }
}
