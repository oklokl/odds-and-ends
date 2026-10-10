package com.krdondon.QuickPush

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log

class QuickPushApplication : Application(), ComponentCallbacks2 {

  override fun onCreate() {
    super.onCreate()
    registerComponentCallbacks(this)
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    Log.d("QuickPushApplication", "onTrimMemory level=$level")
  }

  override fun onLowMemory() {
    super.onLowMemory()
    Log.w("QuickPushApplication", "onLowMemory triggered by system")
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
  }

  override fun onTerminate() {
    super.onTerminate()
    unregisterComponentCallbacks(this)
  }
}
