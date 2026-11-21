package com.krdondon.exorcismprayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.krdondon.exorcismprayer.ui.theme.ExorcismPrayerTheme

class MainActivity : ComponentActivity() {

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var bitmapLoader: LocalArtworkBitmapLoader? = null
    private lateinit var ageSignalsCompliance: AgeSignalsCompliance
    private var ageClassification: AgeClassification = AgeClassification.UNKNOWN

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled by system */ }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light system bar icons are dark so they remain legible over the light-green prayer background
        // in both system light and dark modes.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS once on initial launch for Android 13+ media controls.
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
            ) {
                prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
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

        val localBitmapLoader = LocalArtworkBitmapLoader(this)
        bitmapLoader = localBitmapLoader
        val cachedBitmapLoader = CacheBitmapLoader(localBitmapLoader)

        setContent {
            var mediaControllerState by remember { mutableStateOf<MediaController?>(null) }
            var currentMediaIndex by rememberSaveable { mutableIntStateOf(0) }
            var isPlaying by remember { mutableStateOf(false) }

            DisposableEffect(this@MainActivity) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            val sessionToken = SessionToken(
                                this@MainActivity,
                                ComponentName(this@MainActivity, PlaybackService::class.java)
                            )
                            val future = MediaController.Builder(this@MainActivity, sessionToken)
                                .setBitmapLoader(cachedBitmapLoader)
                                .buildAsync()
                            mediaControllerFuture = future
                            future.addListener({
                                if (future.isCancelled || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                    return@addListener
                                }
                                try {
                                    val controller = future.get()
                                    mediaControllerState = controller
                                    isPlaying = controller.isPlaying
                                    if (controller.mediaItemCount > 0) {
                                        currentMediaIndex = controller.currentMediaItemIndex
                                            .coerceIn(0, mediaList.lastIndex)
                                    }
                                } catch (_: Exception) {
                                    // Controller connection failed or was cancelled
                                }
                            }, ContextCompat.getMainExecutor(this@MainActivity))
                        }

                        Lifecycle.Event.ON_STOP -> {
                            mediaControllerFuture?.let {
                                MediaController.releaseFuture(it)
                                mediaControllerFuture = null
                            }
                            mediaControllerState = null
                        }

                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                }
            }

            DisposableEffect(mediaControllerState) {
                val controller = mediaControllerState
                if (controller == null) {
                    onDispose { }
                } else {
                    val listener = object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            if (player.mediaItemCount > 0) {
                                currentMediaIndex = player.currentMediaItemIndex
                                    .coerceIn(0, mediaList.lastIndex)
                            }
                            isPlaying = player.isPlaying
                        }
                    }

                    if (controller.mediaItemCount > 0) {
                        currentMediaIndex = controller.currentMediaItemIndex
                            .coerceIn(0, mediaList.lastIndex)
                    }
                    isPlaying = controller.isPlaying

                    controller.addListener(listener)
                    onDispose {
                        controller.removeListener(listener)
                    }
                }
            }

            ExorcismPrayerTheme {
                MediaScreen(
                    currentMediaIndex = currentMediaIndex,
                    isPlaying = isPlaying,
                    isControllerReady = mediaControllerState != null,
                    onPrevious = {
                        val controller = mediaControllerState ?: return@MediaScreen
                        if (currentMediaIndex > 0) {
                            controller.seekToDefaultPosition(currentMediaIndex - 1)
                        }
                    },
                    onTogglePlayPause = {
                        val controller = mediaControllerState ?: return@MediaScreen
                        if (controller.isPlaying) controller.pause() else controller.play()
                    },
                    onNext = {
                        val controller = mediaControllerState ?: return@MediaScreen
                        if (currentMediaIndex < mediaList.lastIndex) {
                            controller.seekToDefaultPosition(currentMediaIndex + 1)
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
            mediaControllerFuture = null
        }
        bitmapLoader?.shutdown()
        bitmapLoader = null
        super.onDestroy()
    }

    private companion object {
        const val PREFS_NAME = "exorcism_prayer_prefs"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "key_notification_permission_requested"
    }
}
