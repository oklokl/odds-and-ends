package com.krdondon.quickpush

import android.app.Application
import android.util.Log

class QuickPushApplication : Application() {

  override fun onCreate() {
    super.onCreate()
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    Log.d("QuickPushApplication", "onTrimMemory level=$level")
  }

  override fun onLowMemory() {
    super.onLowMemory()
    Log.w("QuickPushApplication", "onLowMemory triggered by system")
  }
}
