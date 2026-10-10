package com.krdondon.QuickPush.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class HapticManager(private val context: Context) {

  private val vibrator: Vibrator? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      manager?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
  } catch (e: Exception) {
    Log.w("HapticManager", "Failed to access vibrator service", e)
    null
  }

  var isHapticEnabled: Boolean = true

  fun playBubblePopHaptic() {
    if (!isHapticEnabled) return
    val v = vibrator ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        v.vibrate(effect)
      } else {
        @Suppress("DEPRECATION")
        v.vibrate(12L)
      }
    } catch (e: Exception) {
      // Ignore vibration failures safely
    }
  }

  fun playCelebrationHaptic() {
    if (!isHapticEnabled) return
    val v = vibrator ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val timings = longArrayOf(0, 30, 60, 30, 60, 45)
        val amplitudes = intArrayOf(0, 180, 0, 200, 0, 255)
        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        v.vibrate(effect)
      } else {
        @Suppress("DEPRECATION")
        v.vibrate(longArrayOf(0, 30, 60, 30, 60, 45), -1)
      }
    } catch (e: Exception) {
      // Ignore vibration failures safely
    }
  }

  fun playFailureHaptic() {
    if (!isHapticEnabled) return
    val v = vibrator ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = VibrationEffect.createOneShot(60L, 160)
        v.vibrate(effect)
      } else {
        @Suppress("DEPRECATION")
        v.vibrate(60L)
      }
    } catch (e: Exception) {
      // Ignore vibration failures safely
    }
  }
}
