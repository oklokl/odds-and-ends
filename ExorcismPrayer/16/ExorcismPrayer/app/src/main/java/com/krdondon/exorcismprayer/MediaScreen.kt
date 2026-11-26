package com.krdondon.exorcismprayer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaScreen(
    currentMediaIndex: Int,
    isPlaying: Boolean,
    isControllerReady: Boolean,
    currentVoice: PrayerVoice,
    onSelectVoice: (PrayerVoice) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current

    val sharedPreferences = remember(context) {
        context.getSharedPreferences("exorcism_prayer_prefs", Context.MODE_PRIVATE)
    }
    var isKeepScreenOn by remember(sharedPreferences) {
        mutableStateOf(sharedPreferences.getBoolean("key_keep_screen_on", false))
    }
    var showVoiceDialog by remember { mutableStateOf(false) }

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

    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false },
            title = {
                Text(text = "음성 선택")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup()
                        .verticalScroll(rememberScrollState())
                ) {
                    PrayerVoice.entries.forEach { voice ->
                        val isSelected = voice == currentVoice
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(
                                    selected = isSelected,
                                    onClick = {
                                        onSelectVoice(voice)
                                        showVoiceDialog = false
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null
                            )
                            Text(
                                text = voice.displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoiceDialog = false }) {
                    Text(text = "닫기")
                }
            }
        )
    }

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
                    // 짧게 누르면 다음 음성으로 변경, 길게 누르면 음성 목록 다이얼로그 표시
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(
                                    minWidth = ButtonDefaults.MinWidth,
                                    minHeight = 56.dp
                                )
                                .clip(ButtonDefaults.shape)
                                .combinedClickable(
                                    enabled = isControllerReady,
                                    onClick = { onSelectVoice(currentVoice.next()) },
                                    onLongClick = {
                                        hapticFeedback.performHapticFeedback(
                                            HapticFeedbackType.LongPress
                                        )
                                        showVoiceDialog = true
                                    },
                                    onLongClickLabel = "음성 목록 열기"
                                )
                                .semantics {
                                    role = Role.Button
                                    contentDescription =
                                        "음성 변경 (현재 ${currentVoice.displayName}, 길게 눌러 목록 선택)"
                                },
                            shape = ButtonDefaults.shape,
                            color = if (isControllerReady) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.55f)
                            },
                            contentColor = if (isControllerReady) {
                                Color.Black
                            } else {
                                Color.Black.copy(alpha = 0.38f)
                            }
                        ) {
                            Box(
                                modifier = Modifier.padding(ButtonDefaults.ContentPadding),
                                contentAlignment = Alignment.Center
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                                    Text(
                                        text = currentVoice.buttonLabel,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
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
                            enabled = isControllerReady && mediaList.size > 1,
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
                            enabled = isControllerReady && mediaList.size > 1,
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
            currentVoice = PrayerVoice.DEFAULT,
            onSelectVoice = {},
            onPrevious = {},
            onTogglePlayPause = {},
            onNext = {}
        )
    }
}
