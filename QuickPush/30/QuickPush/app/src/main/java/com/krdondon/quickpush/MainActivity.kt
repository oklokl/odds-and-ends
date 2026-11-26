package com.krdondon.quickpush

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krdondon.quickpush.compliance.AgeSignalsCompliance
import com.krdondon.quickpush.ui.PushPopGameScreen
import com.krdondon.quickpush.ui.PushPopGameViewModel
import com.krdondon.quickpush.ui.theme.MyApplicationTheme
import com.krdondon.quickpush.util.AppLogger

class MainActivity : ComponentActivity() {

  private val viewModel: PushPopGameViewModel by viewModels()
  private lateinit var ageSignalsCompliance: AgeSignalsCompliance
  private var wasInBackground = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    ageSignalsCompliance = AgeSignalsCompliance(this)
    ageSignalsCompliance.checkAgeSignals()

    setContent {
      MyApplicationTheme {
        val ageState by ageSignalsCompliance.state.collectAsStateWithLifecycle()
        var verificationPromptDismissed by rememberSaveable { mutableStateOf(false) }

        Surface(modifier = Modifier.fillMaxSize()) {
          PushPopGameScreen(viewModel = viewModel)
        }

        if (ageState.requiresPlayStoreVerification && !verificationPromptDismissed) {
          AgeVerificationNoticeDialog(
            onOpenPlayStore = { openPlayStoreForVerification() },
            onDismiss = { verificationPromptDismissed = true },
          )
        }
      }
    }
  }

  private fun openPlayStoreForVerification() {
    val playStoreIntent = packageManager.getLaunchIntentForPackage("com.android.vending")
    val fallbackIntent = Intent(
      Intent.ACTION_VIEW,
      Uri.parse("https://play.google.com/store")
    )
    try {
      startActivity(playStoreIntent ?: fallbackIntent)
    } catch (_: Exception) {
      try {
        startActivity(fallbackIntent)
      } catch (_: Exception) {
        AppLogger.logWarn("Unable to open Play Store for age verification")
      }
    }
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    viewModel.onTrimMemory(level)
  }

  override fun onResume() {
    super.onResume()
    // 40분 경과 시 이전 정상 로그 자동 정리
    AppLogger.purgeExpiredLogsIfNeeded(this)
    if (wasInBackground) {
      wasInBackground = false
      AppLogger.logInfo("App -> FG")
    }
    ageSignalsCompliance.refreshIfVerificationRequired()
    viewModel.onResume()
  }

  override fun onPause() {
    super.onPause()
    viewModel.onPause()
  }

  override fun onStop() {
    super.onStop()
    wasInBackground = true
    AppLogger.logInfo("App -> BG")
    viewModel.onStop()
    AppLogger.saveSessionSnapshot(this, isTerminating = false)
  }

  override fun onDestroy() {
    super.onDestroy()
    if (isFinishing) {
      AppLogger.logInfo("App finish")
    }
    AppLogger.saveSessionSnapshot(this, isTerminating = isFinishing)
  }
}

@Composable
private fun AgeVerificationNoticeDialog(
  onOpenPlayStore: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("연령 확인 안내") },
    text = {
      Text(
        "거주 지역의 Google Play 정책에 따라 연령 확인 또는 가족 보호 설정이 필요합니다. " +
          "Google Play 스토어에서 연령 확인을 완료할 수 있습니다."
      )
    },
    confirmButton = {
      Button(onClick = onOpenPlayStore) {
        Text("Play 스토어 열기")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("나중에")
      }
    },
  )
}
