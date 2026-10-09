package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.model.OrbitPreset
import com.example.model.TouchMode
import com.example.physics.ThreeBodySimulation
import com.example.ui.BottomControlsDock
import com.example.ui.TelemetryOverlay
import com.example.ui.ThreeBodyCanvasView
import com.example.ui.TopStatusBar
import com.example.ui.TuningBottomSheet
import com.example.ui.theme.CosmosBackground
import com.example.ui.theme.MyApplicationTheme
import com.example.util.HapticHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                ThreeBodyScreen()
            }
        }
    }
}

@Composable
fun ThreeBodyScreen() {
    val context = LocalContext.current
    val hapticHelper = remember { HapticHelper(context) }

    val simulation = remember {
        ThreeBodySimulation().apply {
            onSlingshotEncounter = {
                hapticHelper.playSlingshotPulse()
            }
        }
    }

    var isPaused by remember { mutableStateOf(false) }
    var showTelemetry by remember { mutableStateOf(true) }
    var showTuningSheet by remember { mutableStateOf(false) }
    var showFieldLines by remember { mutableStateOf(true) }
    var enableHaptics by remember { mutableStateOf(true) }
    var touchMode by remember { mutableStateOf(TouchMode.IMPULSE) }
    var activePreset by remember { mutableStateOf(OrbitPreset.ELLIPTICAL) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CosmosBackground)
    ) {
        // Fullscreen interactive canvas simulation
        ThreeBodyCanvasView(
            simulation = simulation,
            isPaused = isPaused,
            showFieldLines = showFieldLines,
            modifier = Modifier.fillMaxSize(),
            onTapTriggered = {
                if (enableHaptics) {
                    hapticHelper.playTapPulse()
                }
            }
        )

        // Top UI: Status Bar + Telemetry Overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            TopStatusBar(
                era = simulation.telemetry.currentEra,
                isPaused = isPaused,
                showTelemetry = showTelemetry,
                onToggleTelemetry = { showTelemetry = !showTelemetry },
                onTogglePause = { isPaused = !isPaused },
                onOpenSettings = { showTuningSheet = true },
                onReset = {
                    simulation.loadPreset(activePreset)
                    if (enableHaptics) hapticHelper.playTapPulse()
                }
            )

            AnimatedVisibility(
                visible = showTelemetry,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 }
            ) {
                TelemetryOverlay(
                    telemetry = simulation.telemetry,
                    stars = simulation.stars,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Bottom UI: Controls Dock
        BottomControlsDock(
            touchMode = touchMode,
            onChangeTouchMode = { newMode ->
                touchMode = newMode
                simulation.touchMode = newMode
                if (enableHaptics) hapticHelper.playTapPulse()
            },
            activePreset = activePreset,
            onSelectPreset = { preset ->
                activePreset = preset
                simulation.loadPreset(preset)
                if (enableHaptics) hapticHelper.playTapPulse()
            },
            onInjectChaos = {
                // Large impulse across all bodies from screen center
                val cx = simulation.width / 2f
                val cy = simulation.height / 2f
                simulation.applyTouchImpulse(cx, cy, 380f)
                if (enableHaptics) hapticHelper.playSlingshotPulse()
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Settings / Physics Tuning Modal Bottom Sheet
        if (showTuningSheet) {
            TuningBottomSheet(
                simulation = simulation,
                showFieldLines = showFieldLines,
                onToggleFieldLines = { showFieldLines = it },
                enableHaptics = enableHaptics,
                onToggleHaptics = { enableHaptics = it },
                onDismiss = { showTuningSheet = false }
            )
        }
    }
}
