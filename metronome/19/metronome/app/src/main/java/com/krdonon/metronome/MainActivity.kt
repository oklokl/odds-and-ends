package com.krdonon.metronome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: MetronomeViewModel by viewModels()

    private lateinit var ageSignalsCompliance: AgeSignalsCompliance

    // Runtime-only compliance state. Never persist this value and never use it
    // for advertising, marketing, profiling, or analytics.
    private var ageCategory: AgeSignalsCompliance.AgeCategory =
        AgeSignalsCompliance.AgeCategory.UNKNOWN
    private var ageSignalsRequested = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // 권한이 거부되었을 때의 처리
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ageSignalsCompliance = AgeSignalsCompliance(applicationContext)

        requestPermissions()
        requestBatteryOptimizationExemption()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val soundSetNames by viewModel.soundSetNames.collectAsStateWithLifecycle()

                    MetronomeScreen(
                        state = state,
                        onPlayPauseClick = { viewModel.togglePlayPause() },
                        onBpmChange = { viewModel.setBpm(it) },
                        onBeatsChange = { viewModel.setBeatsPerMeasure(it) },
                        onBeatUnitChange = { viewModel.setBeatUnit(it) },
                        onSoundSetNext = { viewModel.nextSoundSet() },
                        onSoundSetSelect = { idx -> viewModel.setSoundSetIndex(idx) },
                        soundSetNames = soundSetNames,
                        currentSoundSet = state.soundSetName,
                        onVibrationToggle = { viewModel.toggleVibrationMode() },   // ← 추가
                        onKeepScreenToggle = { viewModel.toggleKeepScreenOn() },
                        onSettingsClick = {
                            val intent = Intent(this, SettingsActivity::class.java)
                            startActivity(intent)
                        }
                    )

                }
            }
        }

    }

    override fun onStart() {
        super.onStart()
        viewModel.setUiVisible(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // The app already asks for notification permission / battery settings at
        // startup. Wait until this Activity actually has focus so the Play age
        // sharing UI never competes with another system dialog.
        if (hasFocus && !ageSignalsRequested) {
            ageSignalsRequested = true
            ageSignalsCompliance.requestAgeCategory(this) { result ->
                ageCategory = result
                // Future age-appropriate feature gating may branch on MINOR,
                // ADULT, or UNKNOWN here. UNKNOWN must remain the safe fallback.
            }
        }
    }

    override fun onStop() {
        // 앱이 백그라운드로 가면 항상 시스템 기본 동작으로 복구
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
        // 화면이 보이지 않을 때 20 FPS 상태 폴링을 중지합니다.
        viewModel.setUiVisible(false)
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
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val currentPackageName = packageName

        if (!powerManager.isIgnoringBatteryOptimizations(currentPackageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$currentPackageName")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                // 권한 요청 실패 시 무시
            }
        }
    }
}


