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
        appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeTorchCameraId: String? = null
    private var pendingOffRunnable: Runnable? = null

    private val cameraIdWithFlash: String? by lazy {
        val cm = cameraManager ?: return@lazy null
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            null
        } else {
            runCatching {
                cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            }.getOrNull()
        }
    }

    /** 강박 순간에 플래시를 잠깐 켰다가 끕니다. */
    fun pulse(durationMs: Long = 80L) {
        val id = cameraIdWithFlash ?: return
        val cm = cameraManager ?: return

        pendingOffRunnable?.let { mainHandler.removeCallbacks(it) }

        try {
            cm.setTorchMode(id, true)
            activeTorchCameraId = id
        } catch (_: CameraAccessException) {
            return
        } catch (_: Exception) {
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
        val cm = cameraManager ?: return
        try {
            cm.setTorchMode(id, false)
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
