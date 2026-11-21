package com.krdondon.exorcismprayer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.krdondon.exorcismprayer.ui.theme.ExorcismPrayerTheme
import com.krdondon.exorcismprayer.ui.theme.PrayerBackground

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun MediaScreen(
    currentMediaIndex: Int,
    isPlaying: Boolean,
    isControllerReady: Boolean,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current

    val sharedPreferences = remember(context) {
        context.getSharedPreferences("exorcism_prayer_prefs", Context.MODE_PRIVATE)
    }
    var isKeepScreenOn by remember(sharedPreferences) {
        mutableStateOf(sharedPreferences.getBoolean("key_keep_screen_on", false))
    }

    val activity = remember(context) { context.findActivity() }
    DisposableEffect(isKeepScreenOn, activity) {
        val window = activity?.window
        if (isKeepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(currentMediaIndex) {
        scrollState.scrollTo(0)
    }

    val currentItem = mediaList.getOrNull(currentMediaIndex)
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = Color.White,
        contentColor = Color.Black,
        disabledContainerColor = Color.White.copy(alpha = 0.55f),
        disabledContentColor = Color.Black.copy(alpha = 0.38f)
    )

    Scaffold(
        containerColor = PrayerBackground,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, shape = RoundedCornerShape(12.dp))
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentItem?.title.orEmpty(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White, shape = RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = currentItem?.description.orEmpty(),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    color = Color.Black,
                    fontSize = 16.sp,
                    lineHeight = 26.sp,
                    textAlign = TextAlign.Start
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // "다음" 버튼 바로 위 빈 공간에 배치되는 "유지 / 해제" 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            val newState = !isKeepScreenOn
                            isKeepScreenOn = newState
                            sharedPreferences.edit {
                                putBoolean("key_keep_screen_on", newState)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 56.dp)
                            .semantics {
                                contentDescription = if (isKeepScreenOn) {
                                    "화면 꺼짐 방지 해제"
                                } else {
                                    "화면 꺼짐 방지 유지"
                                }
                            },
                        colors = buttonColors
                    ) {
                        Text(
                            text = if (isKeepScreenOn) "해제" else "유지",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onPrevious,
                        enabled = isControllerReady && currentMediaIndex > 0,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 56.dp),
                        colors = buttonColors
                    ) {
                        Text(
                            text = "이전",
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Button(
                        onClick = onTogglePlayPause,
                        enabled = isControllerReady,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 56.dp),
                        colors = buttonColors
                    ) {
                        Text(
                            text = if (isPlaying) "정지" else "재생",
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Button(
                        onClick = onNext,
                        enabled = isControllerReady && currentMediaIndex < mediaList.lastIndex,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 56.dp),
                        colors = buttonColors
                    ) {
                        Text(
                            text = "다음",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            }
        }
    }
}

@Preview(name = "Prayer Player Screen", showSystemUi = true)
@Composable
private fun MediaScreenPreview() {
    ExorcismPrayerTheme {
        MediaScreen(
            currentMediaIndex = 2,
            isPlaying = false,
            isControllerReady = true,
            onPrevious = {},
            onTogglePlayPause = {},
            onNext = {}
        )
    }
}
