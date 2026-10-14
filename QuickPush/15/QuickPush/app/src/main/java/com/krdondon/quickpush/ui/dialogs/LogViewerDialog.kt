package com.krdondon.quickpush.ui.dialogs

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.krdondon.quickpush.ui.theme.CoralAlert
import com.krdondon.quickpush.ui.theme.PastelPinkContainer
import com.krdondon.quickpush.ui.theme.PastelPinkPrimary
import com.krdondon.quickpush.ui.theme.TextMuted
import com.krdondon.quickpush.ui.theme.TextPrimary
import com.krdondon.quickpush.ui.theme.TextSecondary
import com.krdondon.quickpush.util.AppLogger

@Composable
fun LogViewerDialog(
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current
  val logs by AppLogger.logsStateFlow.collectAsState()

  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.88f)
        .padding(6.dp),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp)
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = "진단 로그 뷰어 (Log Viewer)",
              fontSize = 17.sp,
              fontWeight = FontWeight.Bold,
              color = TextPrimary
            )
            Text(
              text = "오디오 및 시스템 동작 분석용",
              fontSize = 11.sp,
              color = TextMuted
            )
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "닫기", tint = TextSecondary)
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Horizontal Scrollable Action Toolbar (저장 / 복사 / 공유 / 초기화)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // 1. Export to Downloads
          OutlinedButton(
            onClick = {
              AppLogger.exportLogToDownloads(context) { _, message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
              }
            },
            colors = ButtonDefaults.outlinedButtonColors(containerColor = PastelPinkContainer)
          ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = PastelPinkPrimary)
            Spacer(modifier = Modifier.width(4.dp))
            Text("다운로드 저장", fontSize = 11.sp, color = PastelPinkPrimary, fontWeight = FontWeight.Bold)
          }

          // 2. Copy to Clipboard
          OutlinedButton(
            onClick = {
              val text = AppLogger.getLogsFormattedText()
              clipboardManager.setText(AnnotatedString(text))
              Toast.makeText(context, "로그가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
            Spacer(modifier = Modifier.width(4.dp))
            Text("로그 복사", fontSize = 11.sp, color = TextPrimary)
          }

          // 3. Share
          OutlinedButton(
            onClick = {
              val shareIntent = AppLogger.createShareIntent()
              context.startActivity(shareIntent)
            }
          ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
            Spacer(modifier = Modifier.width(4.dp))
            Text("공유하기", fontSize = 11.sp, color = TextPrimary)
          }

          // 4. Clear
          OutlinedButton(
            onClick = {
              AppLogger.clearLogs(context)
              Toast.makeText(context, "로그가 초기화되었습니다.", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralAlert)
          ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = CoralAlert)
            Spacer(modifier = Modifier.width(4.dp))
            Text("초기화", fontSize = 11.sp, color = CoralAlert)
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Log Entries Display Box
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E1E24))
            .padding(10.dp)
        ) {
          if (logs.isEmpty()) {
            Text(
              text = "기록된 로그가 없습니다.",
              color = Color.LightGray,
              fontSize = 12.sp,
              modifier = Modifier.align(Alignment.Center)
            )
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              items(logs) { line ->
                val textColor = when {
                  line.contains("[ERROR]") -> Color(0xFFFF5252)
                  line.contains("[WARN]") -> Color(0xFFFFD740)
                  line.contains("Status=FAILURE") -> Color(0xFFFF5252)
                  line.contains("Status=SUCCESS") -> Color(0xFF69F0AE)
                  line.contains("[AUDIO]") -> Color(0xFF40C4FF)
                  else -> Color(0xFFE0E0E0)
                }
                Text(
                  text = line,
                  color = textColor,
                  fontSize = 10.5.sp,
                  fontFamily = FontFamily.Monospace,
                  lineHeight = 14.sp
                )
              }
            }
          }
        }
      }
    }
  }
}
