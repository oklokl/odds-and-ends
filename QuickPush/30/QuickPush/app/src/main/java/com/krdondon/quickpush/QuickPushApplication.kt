package com.krdondon.quickpush

import android.app.Application
import com.krdondon.quickpush.util.AppLogger

class QuickPushApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    AppLogger.init(this)
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    // TRIM_MEMORY_UI_HIDDEN(20)은 일반 백그라운드 전환마다 발생하므로 실제 메모리 부족(BACKGROUND=40 이상) 시에만 기록
    if (level >= TRIM_MEMORY_BACKGROUND) {
      AppLogger.logWarn("System onTrimMemory level=$level")
    }
  }

  override fun onLowMemory() {
    super.onLowMemory()
    AppLogger.logWarn("System onLowMemory received")
  }
}
