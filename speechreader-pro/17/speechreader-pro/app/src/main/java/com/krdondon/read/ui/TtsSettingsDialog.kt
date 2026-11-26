package com.krdondon.read.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.window.DialogProperties
import com.krdondon.read.service.TtsEngineType
import java.util.Locale

@Composable
fun TtsSettingsDialog(
    initialRate: Float,
    initialPitch: Float,
    initialEngine: TtsEngineType = TtsEngineType.SYSTEM,
    onDismiss: () -> Unit,
    onOpenLog: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onResetProgress: () -> Unit = {},
    onApply: (rate: Float, pitch: Float, engine: TtsEngineType) -> Unit
) {
    var rate by remember { mutableFloatStateOf(initialRate) }
    var pitch by remember { mutableFloatStateOf(initialPitch) }
    var engine by remember { mutableStateOf(initialEngine) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .heightIn(max = 620.dp),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 읽기 기록 & 백업 관리 섹션
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "읽기 기록 & 백업 관리",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "앱 업데이트 시에도 읽던 문장 위치와 책갈피를 복원할 수 있습니다. (단일 최신 파일 덮어쓰기 방식으로 저장소 용량을 차지하지 않습니다)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. [ 💾 기록 저장 ]
                        FilledTonalButton(
                            onClick = onExportBackup,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_backup_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("기록 저장", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }

                        // 2. [ 📥 기록 불러오기 ]
                        FilledTonalButton(
                            onClick = onImportBackup,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.1f)
                                .testTag("import_backup_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("기록 불러오기", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }

                        // 3. [ 🔄 기록 초기화 ]
                        OutlinedButton(
                            onClick = { showResetConfirmDialog = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reset_progress_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("기록 초기화", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 좌측: [ ≡ 로그 ] 버튼
                OutlinedButton(
                    onClick = onOpenLog,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("open_log_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Notes,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("로그", fontWeight = FontWeight.Medium)
                }

                // 우측: [ 취소 ] [ 적용 ] 버튼
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("cancel_tts_settings_button")
                    ) {
                        Text("취소", fontWeight = FontWeight.Medium)
                    }

                    Button(
                        onClick = {
                            onApply(rate, pitch, engine)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("apply_tts_settings_button")
                    ) {
                        Text("적용", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = null
    )

    // 기록 초기화 확인 팝업
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "읽기 기록 초기화",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "모든 문서의 마지막 읽은 위치와 책갈피를 처음(1번째 문장)으로 초기화하시겠습니까?\n\n※ 문서 본문 텍스트는 삭제되지 않으며 읽기 기록 위치만 초기화됩니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmDialog = false
                        onResetProgress()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("초기화")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetConfirmDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}
