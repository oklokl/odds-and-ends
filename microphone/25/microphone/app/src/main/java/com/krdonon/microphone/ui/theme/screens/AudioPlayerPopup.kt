package com.krdonon.microphone.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.krdonon.microphone.service.PlaybackService
import com.krdonon.microphone.service.PlaybackStateManager

@Composable
fun AudioPlayerPopup() {
    val context = LocalContext.current
    val currentRecording by PlaybackStateManager.currentRecording.collectAsState()
    val isPlaying by PlaybackStateManager.isPlaying.collectAsState()
    val currentPosition by PlaybackStateManager.currentPosition.collectAsState()
    val duration by PlaybackStateManager.duration.collectAsState()

    val repeatA by PlaybackStateManager.repeatA.collectAsState()
    val repeatB by PlaybackStateManager.repeatB.collectAsState()
    val isRepeatActive by PlaybackStateManager.isRepeatActive.collectAsState()

    // 사용자가 슬라이더를 드래그 중일 때 임시 위치
    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    val recording = currentRecording ?: return
    val maxDuration = (if (duration > 0) duration else recording.duration).coerceAtLeast(1L)
    val sliderValue = if (isDraggingSlider) {
        dragPosition
    } else {
        (currentPosition.toFloat() / maxDuration.toFloat()).coerceIn(0f, 1f)
    }

    Dialog(
        onDismissRequest = {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_STOP
            }
            context.startService(intent)
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 1행: [재생/일시정지 버튼] - [파일명] - [닫기 버튼]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 멈춤 / 재생 토글 버튼
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                val intent = Intent(context, PlaybackService::class.java).apply {
                                    action = PlaybackService.ACTION_PAUSE
                                }
                                context.startService(intent)
                            } else {
                                val intent = Intent(context, PlaybackService::class.java).apply {
                                    action = PlaybackService.ACTION_RESUME
                                }
                                context.startService(intent)
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "일시정지" else "재생",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    // 파일 이름
                    Text(
                        text = recording.fileName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // 닫기 버튼 (정지)
                    IconButton(
                        onClick = {
                            val intent = Intent(context, PlaybackService::class.java).apply {
                                action = PlaybackService.ACTION_STOP
                            }
                            context.startService(intent)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "닫기",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2행: 시간 표시 (현재 시간 / 전체 시간)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val displayedPos = if (isDraggingSlider) {
                        (dragPosition * maxDuration).toLong()
                    } else {
                        currentPosition
                    }
                    Text(
                        text = formatPlaybackTime(displayedPos),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatPlaybackTime(maxDuration),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }

                // 3행: 시크바 (Slider)
                Slider(
                    value = sliderValue,
                    onValueChange = { newVal ->
                        isDraggingSlider = true
                        dragPosition = newVal
                    },
                    onValueChangeFinished = {
                        val seekTargetMs = (dragPosition * maxDuration).toInt()
                        val intent = Intent(context, PlaybackService::class.java).apply {
                            action = PlaybackService.ACTION_SEEK
                            putExtra(PlaybackService.EXTRA_SEEK_POSITION, seekTargetMs)
                        }
                        context.startService(intent)
                        isDraggingSlider = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 4행: 구간 반복 (A-B Repeat) 기능 영역
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRepeatActive) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        // 상태 안내 텍스트
                        val repeatText = when {
                            isRepeatActive && repeatA != null && repeatB != null -> {
                                "🔁 구간 반복 중: ${formatPlaybackTime(repeatA!!)} ~ ${formatPlaybackTime(repeatB!!)}"
                            }
                            repeatA != null -> {
                                "구간 시작(A): ${formatPlaybackTime(repeatA!!)} (원하는 끝 지점에서 B를 설정하세요)"
                            }
                            else -> {
                                "구간 반복: 원하는 지점에서 A/B를 설정하세요"
                            }
                        }
                        Text(
                            text = repeatText,
                            fontSize = 13.sp,
                            fontWeight = if (isRepeatActive) FontWeight.Bold else FontWeight.Medium,
                            color = if (isRepeatActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // 구간 반복 설정 버튼들
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // [A점 설정]
                            FilledTonalButton(
                                onClick = {
                                    PlaybackStateManager.setRepeatA(currentPosition)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (repeatA != null && !isRepeatActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (repeatA != null && !isRepeatActive) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            ) {
                                Text(
                                    text = if (repeatA != null) "A: ${formatPlaybackTime(repeatA!!)}" else "A 설정",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // [B점 설정]
                            FilledTonalButton(
                                onClick = {
                                    PlaybackStateManager.setRepeatB(currentPosition)
                                },
                                enabled = repeatA != null,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isRepeatActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (isRepeatActive) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            ) {
                                Text(
                                    text = if (repeatB != null) "B: ${formatPlaybackTime(repeatB!!)}" else "B 설정",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // [해제]
                            OutlinedButton(
                                onClick = {
                                    PlaybackStateManager.clearRepeat()
                                },
                                enabled = repeatA != null || isRepeatActive,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Text("해제", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds)
}
