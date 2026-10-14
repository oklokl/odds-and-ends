package com.krdondon.quickpush

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.krdondon.quickpush.compliance.AgeSignalsCompliance
import com.krdondon.quickpush.ui.PushPopGameScreen
import com.krdondon.quickpush.ui.PushPopGameViewModel
import com.krdondon.quickpush.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private val viewModel: PushPopGameViewModel by viewModels()
  private lateinit var ageSignalsCompliance: AgeSignalsCompliance

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    ageSignalsCompliance = AgeSignalsCompliance(this)
    ageSignalsCompliance.checkAgeSignals { category ->
      // Handle in-memory age category safely (MINOR, ADULT, UNKNOWN)
    }

    setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          PushPopGameScreen(viewModel = viewModel)
        }
      }
    }
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    viewModel.onTrimMemory(level)
  }

  override fun onResume() {
    super.onResume()
    // 앱 복귀 시(수동 시간 변경 등 포함) 40분 경과 시 이전 정상 로그 자동 정리
    com.krdondon.quickpush.util.AppLogger.purgeExpiredLogsIfNeeded(this)
    com.krdondon.quickpush.util.AppLogger.logInfo("MainActivity.onResume()")
    viewModel.onResume()
  }

  override fun onPause() {
    super.onPause()
    com.krdondon.quickpush.util.AppLogger.logInfo("MainActivity.onPause()")
    viewModel.onPause()
  }

  override fun onStop() {
    super.onStop()
    com.krdondon.quickpush.util.AppLogger.logInfo("MainActivity.onStop()")
    viewModel.onStop()
    com.krdondon.quickpush.util.AppLogger.saveSessionSnapshot(this, isTerminating = false)
  }

  override fun onDestroy() {
    super.onDestroy()
    com.krdondon.quickpush.util.AppLogger.logInfo("MainActivity.onDestroy() isFinishing=$isFinishing")
    com.krdondon.quickpush.util.AppLogger.saveSessionSnapshot(this, isTerminating = true)
  }
}
