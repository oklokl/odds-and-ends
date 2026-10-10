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
    viewModel.onResume()
  }

  override fun onPause() {
    super.onPause()
    viewModel.onPause()
  }

  override fun onStop() {
    super.onStop()
    // 백그라운드 전환 시 불필요한 이펙트 메모리만 정리하고 BGM은 onPause()에서 정지 관리
    viewModel.clearVisualEffectsMemory()
  }
}
