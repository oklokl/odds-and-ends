package com.krdondon.QuickPush.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.krdondon.QuickPush.R
import com.krdondon.QuickPush.model.GameMode
import com.krdondon.QuickPush.ui.PushPopUiState
import com.krdondon.QuickPush.ui.theme.CoralAlert
import com.krdondon.QuickPush.ui.theme.GoldStar
import com.krdondon.QuickPush.ui.theme.PastelMint
import com.krdondon.QuickPush.ui.theme.PastelPinkContainer
import com.krdondon.QuickPush.ui.theme.PastelPinkPrimary
import com.krdondon.QuickPush.ui.theme.TextMuted
import com.krdondon.QuickPush.ui.theme.TextPrimary
import com.krdondon.QuickPush.ui.theme.TextSecondary

@Composable
fun LevelUpDialog(
  currentLevel: Int,
  nextGoal: Int,
  onDismiss: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
        .testTag("level_up_dialog"),
      shape = RoundedCornerShape(32.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(12.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Mascot celebrate icon
        Box(
          modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(PastelPinkContainer),
          contentAlignment = Alignment.Center
        ) {
          Image(
            painter = painterResource(id = R.drawable.toy_mascot),
            contentDescription = null,
            modifier = Modifier.size(70.dp),
            contentScale = ContentScale.Crop
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(Icons.Default.Celebration, contentDescription = null, tint = GoldStar)
          Text(
            text = "LEVEL UP!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = PastelPinkPrimary
          )
          Icon(Icons.Default.Celebration, contentDescription = null, tint = GoldStar)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
          text = "스테이지 $currentLevel 돌파 완료!",
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          color = TextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
          text = "다음 스테이지 목표: ${nextGoal}개 버블 팝!",
          fontSize = 14.sp,
          color = TextSecondary,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary)
        ) {
          Text("계속하기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

@Composable
fun TimeAttackResultDialog(
  uiState: PushPopUiState,
  onRetry: () -> Unit,
  onDismiss: () -> Unit
) {
  val duration = uiState.timeAttackDuration
  val bestScore = when (duration) {
    30 -> uiState.bestTimeAttack30
    60 -> uiState.bestTimeAttack60
    else -> uiState.bestTimeAttack120
  }
  val isNewHigh = uiState.score >= bestScore && uiState.score > 0
  val popsPerSec = if (duration > 0) String.format("%.1f", uiState.sessionTouches.toFloat() / duration) else "0"

  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
        .testTag("time_attack_result_dialog"),
      shape = RoundedCornerShape(32.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(12.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Icon(
          imageVector = Icons.Default.Timer,
          contentDescription = null,
          tint = PastelPinkPrimary,
          modifier = Modifier.size(54.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "시간 종료!",
          fontSize = 22.sp,
          fontWeight = FontWeight.Black,
          color = TextPrimary
        )

        if (isNewHigh) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "★ 새로운 최고 기록! ★",
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            color = CoralAlert
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Result Stats Card
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = PastelPinkContainer)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            ResultRow(label = "최종 점수", value = "${uiState.score}점")
            ResultRow(label = "누적 버블 팝", value = "${uiState.sessionTouches}회")
            ResultRow(label = "초당 속도", value = "${popsPerSec}회/초")
            ResultRow(label = "${duration}초 최고 점수", value = "${bestScore}점")
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp)
          ) {
            Text("닫기", color = TextSecondary)
          }

          Button(
            onClick = onRetry,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary)
          ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("다시 도전", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Composable
private fun ResultRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(text = label, fontSize = 14.sp, color = TextSecondary)
    Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
  }
}

@Composable
fun PauseDialog(
  onResume: () -> Unit,
  onReset: () -> Unit,
  onOpenSettings: () -> Unit
) {
  Dialog(onDismissRequest = onResume) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
        .testTag("pause_dialog"),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(10.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "게임 일시정지",
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          color = TextPrimary
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
          onClick = onResume,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary)
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null)
          Spacer(modifier = Modifier.width(6.dp))
          Text("계속하기", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
          onClick = onReset,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp)
        ) {
          Icon(Icons.Default.Refresh, contentDescription = null)
          Spacer(modifier = Modifier.width(6.dp))
          Text("처음부터 다시하기", fontSize = 14.sp, color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
          onClick = onOpenSettings,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp)
        ) {
          Text("설정 열기", fontSize = 14.sp, color = TextSecondary)
        }
      }
    }
  }
}

@Composable
fun ModeSelectorDialog(
  currentMode: GameMode,
  timeDuration: Int,
  onSelectMode: (GameMode) -> Unit,
  onSelectDuration: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
        .testTag("mode_selector_dialog"),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(10.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp)
      ) {
        Text(
          text = "게임 모드 선택",
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          color = TextPrimary
        )

        Spacer(modifier = Modifier.height(14.dp))

        GameMode.values().forEach { mode ->
          val isSelected = currentMode == mode
          Card(
            onClick = {
              onSelectMode(mode)
              onDismiss()
            },
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
              containerColor = if (isSelected) PastelPinkContainer else Color(0xFFFAFAFA)
            ),
            border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, PastelPinkPrimary) else null
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(
                text = mode.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) PastelPinkPrimary else TextPrimary
              )
              Text(
                text = mode.description,
                fontSize = 12.sp,
                color = TextSecondary
              )
            }
          }
        }

        // Sub-options for Time Attack duration
        if (currentMode == GameMode.TIME_ATTACK) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(text = "타임 어택 시간 선택", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf(30, 60, 120).forEach { dur ->
              val isDurSelected = timeDuration == dur
              OutlinedButton(
                onClick = { onSelectDuration(dur) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                  containerColor = if (isDurSelected) PastelPinkContainer else Color.Transparent
                )
              ) {
                Text(
                  text = "${dur}초",
                  fontSize = 12.sp,
                  color = if (isDurSelected) PastelPinkPrimary else TextSecondary,
                  fontWeight = if (isDurSelected) FontWeight.Bold else FontWeight.Normal
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary)
        ) {
          Text("닫기")
        }
      }
    }
  }
}

@Composable
fun ConfirmResetDialog(
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(10.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "기록 초기화",
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          color = CoralAlert
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = "최고 점수와 누적 팝 기록이 모두 삭제됩니다. 정말 초기화하시겠습니까?",
          fontSize = 14.sp,
          color = TextSecondary,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp)
          ) {
            Text("취소", color = TextSecondary)
          }

          Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CoralAlert)
          ) {
            Text("초기화", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Composable
fun PatternFailDialog(
  onRetry: () -> Unit,
  onDismiss: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(10.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "아쉬워요!",
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          color = CoralAlert
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "패턴 순서가 틀렸어요. 다시 시도해 볼까요?",
          fontSize = 14.sp,
          color = TextSecondary,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp)
          ) {
            Text("닫기", color = TextSecondary)
          }

          Button(
            onClick = onRetry,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary)
          ) {
            Text("재도전", fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}
