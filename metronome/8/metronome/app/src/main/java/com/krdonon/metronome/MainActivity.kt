package com.krdonon.metronome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: MetronomeViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // 권한이 거부되었을 때의 처리
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()
        requestBatteryOptimizationExemption()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.state.collectAsState()

                    MetronomeScreen(
                        state = state,
                        onPlayPauseClick = { viewModel.togglePlayPause() },
                        onBpmChange = { viewModel.setBpm(it) },
                        onBeatsChange = { viewModel.setBeatsPerMeasure(it) },
                        onBeatUnitChange = { viewModel.setBeatUnit(it) },
                        onSoundSetChange = { viewModel.nextSoundSet() },
                        currentSoundSet = viewModel.getCurrentSoundSet(),
                        onVibrationToggle = { viewModel.toggleVibrationMode() },   // ← 추가
                        onSettingsClick = {
                            val intent = Intent(this, SettingsActivity::class.java)
                            startActivity(intent)
                        }
                    )

                }
            }
        }

    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // 권한 요청 실패 시 무시
                }
            }
        }
    }
}


