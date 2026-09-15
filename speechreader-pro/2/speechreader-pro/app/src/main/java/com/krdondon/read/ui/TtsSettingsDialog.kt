package com.krdondon.read.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun TtsSettingsDialog(
    initialRate: Float,
    initialPitch: Float,
    onDismiss: () -> Unit,
    onApply: (rate: Float, pitch: Float) -> Unit
) {
    var rate by remember { mutableFloatStateOf(initialRate) }
    var pitch by remember { mutableFloatStateOf(initialPitch) }

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
            Text(text = "TTS 음성 설정", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
                    onApply(rate, pitch)
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
