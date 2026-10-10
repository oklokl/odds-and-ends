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

  override fun onStop() {
    super.onStop()
    viewModel.onTrimMemory(TRIM_MEMORY_UI_HIDDEN)
  }
}
