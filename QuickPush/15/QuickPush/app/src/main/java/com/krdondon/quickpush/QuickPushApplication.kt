package com.krdondon.quickpush

import android.app.Application
import android.util.Log

class QuickPushApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    com.krdondon.quickpush.util.AppLogger.init(this)
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    com.krdondon.quickpush.util.AppLogger.logInfo("System onTrimMemory level=$level")
  }

  override fun onLowMemory() {
    super.onLowMemory()
    com.krdondon.quickpush.util.AppLogger.logWarn("System onLowMemory received!")
  }
}
