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
    private var activeTorchCameraId: String? = null
    private var pendingOffRunnable: Runnable? = null

    private val cameraIdWithFlash: String? by lazy {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            null
        } else {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }
    }

    /** 강박 순간에 플래시를 잠깐 켰다가 끕니다. */
    fun pulse(durationMs: Long = 80L) {
        val id = cameraIdWithFlash ?: return

        // 이전 OFF 예약을 제거해 Handler 큐에 콜백이 누적되지 않도록 합니다.
        pendingOffRunnable?.let { mainHandler.removeCallbacks(it) }

        try {
            cameraManager.setTorchMode(id, true)
            activeTorchCameraId = id
        } catch (_: CameraAccessException) {
            return
        }

        val offRunnable = Runnable {
            turnOffTorch()
            pendingOffRunnable = null
        }
        pendingOffRunnable = offRunnable
        mainHandler.postDelayed(offRunnable, durationMs.coerceAtLeast(1L))
    }

    private fun turnOffTorch() {
        val id = activeTorchCameraId ?: return
        try {
            cameraManager.setTorchMode(id, false)
        } catch (_: Exception) {
        } finally {
            activeTorchCameraId = null
        }
    }

    fun release() {
        pendingOffRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingOffRunnable = null
        turnOffTorch()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
