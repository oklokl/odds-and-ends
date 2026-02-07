package com.krdonon.metronome

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

class FlashManager(context: Context) {

    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraIdWithFlash: String? by lazy {
        // 플래시 기능을 지원하는 카메라 찾기
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            null
        } else {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }
    }

    /**
     * 강박 순간에 잠깐 켰다가 끄는 함수
     */
    fun pulse(durationMs: Long = 80L) {
        val id = cameraIdWithFlash ?: return

        try {
            cameraManager.setTorchMode(id, true)
        } catch (e: CameraAccessException) {
            return
        }

        // durationMs 후에 다시 끄기
        mainHandler.postDelayed({
            try {
                cameraManager.setTorchMode(id, false)
            } catch (_: Exception) {
            }
        }, durationMs)
    }

    fun release() {
        // 지금 구조에서는 특별히 해제할 것은 없음
        // 나중에 필요해지면 여기서 정리
    }
}
