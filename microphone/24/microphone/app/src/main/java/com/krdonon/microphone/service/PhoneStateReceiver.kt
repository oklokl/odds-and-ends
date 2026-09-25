package com.krdonon.microphone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING,
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // ✅ 실제로 "녹음 중"일 때만 일시정지 명령을 보냄
                    // (녹음 중이 아닌데 서비스가 시작되면서 "00:00:00 일시정지됨" 알림이 뜨는 버그 방지)
                    val isRecording = RecordingStateManager.isRecording.value
                    val isPaused = RecordingStateManager.isPaused.value
                    if (isRecording && !isPaused) {
                        val pauseIntent = Intent(context, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_PAUSE_RECORDING
                        }
                        context?.startService(pauseIntent)
                    }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // 전화가 끝났을 때 녹음 재개 (설정에 따라)
                    // 사용자가 수동으로 재개하도록 하는 것이 더 안전할 수 있음
                }
            }
        }
    }
}
