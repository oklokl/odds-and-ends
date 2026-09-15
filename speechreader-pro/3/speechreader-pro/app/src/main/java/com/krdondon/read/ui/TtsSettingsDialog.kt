package com.krdondon.read.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krdondon.read.service.TtsEngineType
import java.util.Locale

@Composable
fun TtsSettingsDialog(
    initialRate: Float,
    initialPitch: Float,
    initialEngine: TtsEngineType = TtsEngineType.SYSTEM,
    onDismiss: () -> Unit,
    onApply: (rate: Float, pitch: Float, engine: TtsEngineType) -> Unit
) {
    var rate by remember { mutableFloatStateOf(initialRate) }
    var pitch by remember { mutableFloatStateOf(initialPitch) }
    var engine by remember { mutableStateOf(initialEngine) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TTS 음성 설정",
                    style = MaterialTheme.typography.titleLarge
                )

                // 단일 버튼으로 구글 <-> 시스템 엔진 토글
                FilledTonalButton(
                    onClick = {
                        engine = if (engine == TtsEngineType.SYSTEM) TtsEngineType.GOOGLE else TtsEngineType.SYSTEM
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("toggle_tts_engine_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (engine == TtsEngineType.GOOGLE) "구글" else "시스템",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 선택된 TTS 엔진 상세 설명 카드
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsVoice,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "선택된 엔진: ${if (engine == TtsEngineType.GOOGLE) "구글 음성 서비스 (Google TTS)" else "시스템 기본 (삼성 TTS 등)"}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Speech Rate
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("읽기 속도", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = String.format(Locale.US, "%.1fx", rate),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = rate,
                        onValueChange = { rate = (Math.round(it * 10f) / 10f) },
                        valueRange = 0.5f..2.5f,
                        steps = 19,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rate_slider")
                    )
                }

                // Speech Pitch
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("음높이 (톤)", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = String.format(Locale.US, "%.1fx", pitch),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = pitch,
                        onValueChange = { pitch = (Math.round(it * 10f) / 10f) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pitch_slider")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(rate, pitch, engine)
                    onDismiss()
                },
                modifier = Modifier.testTag("apply_tts_settings_button")
            ) {
                Text("적용")
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_tts_settings_button")
            ) {
                Text("취소")
            }
        }
    )
}
