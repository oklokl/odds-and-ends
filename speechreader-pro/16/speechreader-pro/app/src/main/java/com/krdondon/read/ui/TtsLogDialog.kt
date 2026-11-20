package com.krdondon.read.ui

import android.graphics.Typeface
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import com.krdondon.read.util.TtsLogManager

@Composable
fun TtsLogDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logText by TtsLogManager.logTextFlow.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .heightIn(max = 560.dp),
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Notes,
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
                    text = "TTS 진단 로그",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // 로그 지우기 아이콘
                IconButton(
                    onClick = {
                        TtsLogManager.clearAllLogs(context)
                        Toast.makeText(context, "로그가 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "로그 비우기",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "삼성 갤럭시 및 시스템 TTS 연동 진단 로그입니다. 문제 분석을 위해 복사/저장/공유할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 액션 버튼 모음: [복사] [다운로드 저장] [공유]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 1. 복사 버튼
                    FilledTonalButton(
                        onClick = {
                            val success = TtsLogManager.copyToClipboard(context)
                            val msg = if (success) "로그가 클립보드에 복사되었습니다." else "복사 실패"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).testTag("copy_log_button")
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("복사", fontSize = 12.sp)
                    }

                    // 2. 다운로드 폴더 저장 버튼
                    FilledTonalButton(
                        onClick = {
                            val (_, message) = TtsLogManager.saveLogToDownloads(context)
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).testTag("save_log_button")
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("저장", fontSize = 12.sp)
                    }

                    // 3. 외부 앱(Gmail 등) 공유 버튼
                    FilledTonalButton(
                        onClick = {
                            TtsLogManager.shareLog(context)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).testTag("share_log_button")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("공유", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 로그 텍스트 출력 박스 (가로 모드/세로 모드 회전 시에도 튕김 없는 안정적인 네이티브 텍스트 뷰)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 260.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                setTextIsSelectable(true)
                                typeface = Typeface.MONOSPACE
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                setTextColor(android.graphics.Color.parseColor("#1C1B1F"))
                                setHorizontallyScrolling(true)
                                isVerticalScrollBarEnabled = true
                                isHorizontalScrollBarEnabled = true
                                movementMethod = ScrollingMovementMethod.getInstance()
                                setPadding(24, 20, 24, 20)
                            }
                        },
                        update = { view ->
                            val current = view.text.toString()
                            val target = logText.ifBlank { "기록된 로그가 없습니다." }
                            if (current != target) {
                                view.text = target
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 260.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_log_dialog_button")
            ) {
                Text("닫기")
            }
        }
    )
}
