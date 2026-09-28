package com.krdondon.exorcismprayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.krdondon.exorcismprayer.ui.theme.ExorcismPrayerTheme

class MainActivity : ComponentActivity() {

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private lateinit var ageSignalsCompliance: AgeSignalsCompliance
    private var ageClassification: AgeClassification = AgeClassification.UNKNOWN

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled by system */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+ (API 33+) requires POST_NOTIFICATIONS permission for media controls in notification bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        try {
            ageSignalsCompliance = AgeSignalsCompliance(applicationContext)
            ageSignalsCompliance.requestAgeClassification(this) { classification ->
                ageClassification = classification
            }
        } catch (_: Exception) {
            // Safety fallback if Google Play Age Signals is unavailable
        }

        setContent {
            var mediaControllerState by remember { mutableStateOf<MediaController?>(null) }
            var currentMediaIndex by remember { mutableIntStateOf(0) }
            var isPlaying by remember { mutableStateOf(false) }

            val lifecycleObserver = remember {
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            val sessionToken = SessionToken(
                                this@MainActivity,
                                ComponentName(this@MainActivity, PlaybackService::class.java)
                            )
                            mediaControllerFuture = MediaController.Builder(this@MainActivity, sessionToken).buildAsync()
                            mediaControllerFuture?.addListener({
                                val controller = mediaControllerFuture?.get()
                                mediaController = controller
                                mediaControllerState = controller

                                controller?.let {
                                    isPlaying = it.isPlaying
                                    currentMediaIndex = it.currentMediaItemIndex
                                }
                            }, MoreExecutors.directExecutor())
                        }
                        Lifecycle.Event.ON_STOP -> {
                            mediaControllerFuture?.let {
                                MediaController.releaseFuture(it)
                                mediaControllerFuture = null
                            }
                            mediaController = null
                            mediaControllerState = null
                        }
                        else -> {}
                    }
                }
            }

            DisposableEffect(lifecycleObserver) {
                this@MainActivity.lifecycle.addObserver(lifecycleObserver)
                onDispose {
                    this@MainActivity.lifecycle.removeObserver(lifecycleObserver)
                }
            }

            DisposableEffect(mediaControllerState) {
                val controller = mediaControllerState
                if (controller == null) {
                    onDispose { }
                } else {
                    val listener = object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            currentMediaIndex = player.currentMediaItemIndex
                            isPlaying = player.isPlaying
                        }
                    }

                    // Initial sync with player state
                    currentMediaIndex = controller.currentMediaItemIndex
                    isPlaying = controller.isPlaying

                    controller.addListener(listener)
                    onDispose {
                        controller.removeListener(listener)
                    }
                }
            }

            ExorcismPrayerTheme {
                val activeController = mediaControllerState
                if (activeController != null) {
                    MediaScreen(
                        mediaController = activeController,
                        currentMediaIndex = currentMediaIndex,
                        onMediaIndexChange = { newIndex ->
                            activeController.seekToDefaultPosition(newIndex)
                        },
                        isPlaying = isPlaying,
                        onIsPlayingChange = { nowPlaying ->
                            if (nowPlaying) activeController.play() else activeController.pause()
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("준비 중...", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
