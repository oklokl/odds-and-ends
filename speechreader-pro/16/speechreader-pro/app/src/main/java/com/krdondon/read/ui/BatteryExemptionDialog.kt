package com.krdondon.read.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun BatteryExemptionDialog(
    isIgnored: Boolean,
    onDismiss: () -> Unit,
    onRequestExemption: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                imageVector = if (isIgnored) Icons.Default.CheckCircle else Icons.Default.BatteryChargingFull,
                contentDescription = null,
                tint = if (isIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
            )
        },
        title = {
            Text(
                text = if (isIgnored) "배터리 제한 해제됨" else "백그라운드 배터리 제한 해제",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isIgnored) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "현재 배터리 제한이 해제되어 있어 화면이 꺼져도 안정적으로 백그라운드에서 텍스트를 읽을 수 있습니다.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    Text(
                        text = "안드로이드 시스템의 배터리 절전 정책으로 인해 다른 앱으로 전환하거나 화면을 껐을 때 TTS 재생이 멈출 수 있습니다.\n\n원활한 백그라운드 재생을 위해 배터리 사용량 제한을 '제한 없음'으로 설정하는 것을 권장합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "💡 설정 안내",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "확인 버튼을 누르고 나타나는 팝업에서 '허용' 또는 배터리 설정에서 '제한 없음'을 선택해주세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isIgnored) {
                Button(
                    onClick = {
                        onRequestExemption()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("request_battery_button")
                ) {
                    Text("제한 해제 설정하기")
                }
            } else {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("battery_dialog_ok_button")
                ) {
                    Text("확인")
                }
            }
        },
        dismissButton = {
            if (!isIgnored) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("battery_dialog_cancel_button")
                ) {
                    Text("닫기")
                }
            }
        }
    )
}
