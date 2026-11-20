package com.krdonon.microphone

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.krdonon.microphone.compliance.AgeSignalsCompliance
import com.krdonon.microphone.ui.MainViewModel
import com.krdonon.microphone.ui.screens.HomeScreen
import com.krdonon.microphone.ui.screens.CategoryManagementScreen
import com.krdonon.microphone.ui.screens.RecordingScreen
import com.krdonon.microphone.ui.screens.SettingsScreen
import com.krdonon.microphone.ui.theme.MicrophoneTheme
import com.krdonon.microphone.service.RecordingService

class MainActivity : ComponentActivity() {

    private lateinit var ageSignalsCompliance: AgeSignalsCompliance
    private val openRecordingEvent = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(RecordingService.EXTRA_OPEN_RECORDING_SCREEN, false)) {
            openRecordingEvent.value = true
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBatteryOptimizationExemption()

        // Google Play Age Signals 0.0.4: runtime-only compliance state (not persisted).
        ageSignalsCompliance = AgeSignalsCompliance(applicationContext)

        // 알림에서 들어온 경우인지 체크
        if (intent?.getBooleanExtra(RecordingService.EXTRA_OPEN_RECORDING_SCREEN, false) == true) {
            openRecordingEvent.value = true
        }

        setContent {
            MicrophoneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val viewModel: MainViewModel = viewModel { MainViewModel(applicationContext) }
                    val shouldOpenRecording by openRecordingEvent
                    val configuration = LocalConfiguration.current

                    // 휴대폰(작은 화면)에서는 세로 고정, 태블릿/데스크톱/XR(큰 화면)에서는 자유 회전 허용
                    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp) {
                        val isCompact = configuration.screenWidthDp < 600 || configuration.screenHeightDp < 600
                        requestedOrientation = if (isCompact) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }

                    LaunchedEffect(shouldOpenRecording) {
                        if (shouldOpenRecording) {
                            navController.navigate("recording") {
                                launchSingleTop = true
                            }
                            openRecordingEvent.value = false
                        }
                    }

                    // 권한 요청
                    val permissions = mutableListOf(
                        Manifest.permission.RECORD_AUDIO
                    ).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }

                    val permissionsState = rememberMultiplePermissionsState(permissions)

                    LaunchedEffect(Unit) {
                        if (!permissionsState.allPermissionsGranted) {
                            permissionsState.launchMultiplePermissionRequest()
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            HomeScreen(
                                viewModel = viewModel,
                                onNavigateToRecording = { navController.navigate("recording") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToCategoryManagement = { navController.navigate("categoryManagement") }
                            )
                        }

                        composable("recording") {
                            RecordingScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("categoryManagement") {
                            CategoryManagementScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                    }
                }
            }
        }

        // Request age-sharing access first; AgeSignalsCompliance calls checkAgeSignals() only
        // when Google Play reports AgeSignalsStatus.SHARED.
        ageSignalsCompliance.refresh(this)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}