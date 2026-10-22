package com.krdondon.quickpush.ui.dialogs

import android.content.res.Configuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import com.krdondon.quickpush.R
import com.krdondon.quickpush.model.GameMode
import com.krdondon.quickpush.ui.PushPopUiState
import com.krdondon.quickpush.ui.theme.CoralAlert
import com.krdondon.quickpush.ui.theme.GoldStar
import com.krdondon.quickpush.ui.theme.PastelMint
import com.krdondon.quickpush.ui.theme.PastelPinkContainer
import com.krdondon.quickpush.ui.theme.PastelPinkPrimary
import com.krdondon.quickpush.ui.theme.TextMuted
import com.krdondon.quickpush.ui.theme.TextPrimary
import com.krdondon.quickpush.ui.theme.TextSecondary

@Composable
fun LevelUpDialog(
  currentLevel: Int,
  nextGoal: Int,
  onDismiss: () -> Unit
) {
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val scrollState = rememberScrollState()

  LaunchedEffect(isLandscape) {
    if (isLandscape) {
      delay(250)
      scrollState.animateScrollTo(60, tween(320, easing = FastOutSlowInEasing))
      delay(120)
      scrollState.animateScrollTo(0, tween(320, easing = FastOutSlowInEasing))
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth(if (isLandscape) 0.88f else 1f)
        .padding(horizontal = if (isLandscape) 10.dp else 16.dp, vertical = if (isLandscape) 4.dp else 16.dp)
        .heightIn(max = if (isLandscape) 330.dp else 650.dp)
        .testTag("level_up_dialog"),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(8.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
          .padding(horizontal = 20.dp, vertical = if (isLandscape) 12.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Mascot celebrate icon
        Box(
          modifier = Modifier
            .size(if (isLandscape) 50.dp else 80.dp)
            .clip(CircleShape)
            .background(PastelPinkContainer),
          contentAlignment = Alignment.Center
        ) {
          Image(
            painter = painterResource(id = R.drawable.toy_mascot),
            contentDescription = null,
            modifier = Modifier.size(if (isLandscape) 42.dp else 70.dp),
            contentScale = ContentScale.Crop
          )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 14.dp))

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(Icons.Default.Celebration, contentDescription = null, tint = GoldStar)
          Text(
            text = "LEVEL UP!",
            fontSize = if (isLandscape) 20.sp else 24.sp,
            fontWeight = FontWeight.Black,
            color = PastelPinkPrimary
          )
          Icon(Icons.Default.Celebration, contentDescription = null, tint = GoldStar)
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 2.dp else 6.dp))

        Text(
          text = "스테이지 $currentLevel 돌파 완료!",
          fontSize = if (isLandscape) 15.sp else 18.sp,
          fontWeight = FontWeight.Bold,
          color = TextPrimary
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 6.dp))

        Text(
          text = "다음 스테이지 목표: ${nextGoal}개 버블 팝!",
          fontSize = 13.sp,
          color = TextSecondary,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 10.dp else 20.dp))

        Button(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary),
          contentPadding = PaddingValues(vertical = if (isLandscape) 8.dp else 12.dp)
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

  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val scrollState = rememberScrollState()

  // 게임 오버 연타로 인한 실수 눌림 방지 카운트다운 (4 -> 3 -> 2 -> 1 -> 0)
  var countdownLeft by remember { androidx.compose.runtime.mutableIntStateOf(4) }
  LaunchedEffect(Unit) {
    while (countdownLeft > 0) {
      delay(1000L)
      countdownLeft--
    }
  }
  val isClickable = countdownLeft == 0

  // 가로 모드 진입 시 스크롤 가능함을 시각적으로 알려주는 미세 바운스(들썩임) 애니메이션
  LaunchedEffect(isLandscape) {
    if (isLandscape) {
      delay(250)
      scrollState.animateScrollTo(60, tween(320, easing = FastOutSlowInEasing))
      delay(120)
      scrollState.animateScrollTo(0, tween(320, easing = FastOutSlowInEasing))
    }
  }

  Dialog(
    onDismissRequest = { if (isClickable) onDismiss() },
    properties = DialogProperties(
      dismissOnBackPress = isClickable,
      dismissOnClickOutside = isClickable,
      usePlatformDefaultWidth = true
    )
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(if (isLandscape) 0.88f else 1f)
        .padding(horizontal = if (isLandscape) 10.dp else 16.dp, vertical = if (isLandscape) 4.dp else 16.dp)
        .heightIn(max = if (isLandscape) 330.dp else 650.dp)
        .testTag("time_attack_result_dialog"),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(6.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
          .padding(horizontal = 20.dp, vertical = if (isLandscape) 12.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Icon(
          imageVector = Icons.Default.Timer,
          contentDescription = null,
          tint = PastelPinkPrimary,
          modifier = Modifier.size(if (isLandscape) 34.dp else 52.dp)
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

        Text(
          text = "시간 종료!",
          fontSize = if (isLandscape) 18.sp else 22.sp,
          fontWeight = FontWeight.Black,
          color = TextPrimary
        )

        if (isNewHigh) {
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = "★ 새로운 최고 기록! ★",
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            color = CoralAlert
          )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 10.dp))

        // 실수 클릭 방지 카운트다운 배지 (4초)
        if (!isClickable) {
          androidx.compose.material3.Surface(
            shape = RoundedCornerShape(14.dp),
            color = CoralAlert.copy(alpha = 0.12f),
            modifier = Modifier.padding(bottom = 6.dp)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                text = "$countdownLeft",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = CoralAlert
              )
              Text(
                text = "실수 눌림 방지 대기 중...",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CoralAlert
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

        // Result Stats Card
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = PastelPinkContainer)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = if (isLandscape) 8.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 5.dp else 10.dp)
          ) {
            ResultRow(label = "최종 점수", value = "${uiState.score}점")
            ResultRow(label = "누적 버블 팝", value = "${uiState.sessionTouches}회")
            ResultRow(label = "초당 속도", value = "${popsPerSec}회/초")
            ResultRow(label = "${duration}초 최고 점수", value = "${bestScore}점")
          }
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 10.dp else 18.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            enabled = isClickable,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = if (isLandscape) 8.dp else 12.dp)
          ) {
            Text(
              text = if (isClickable) "닫기" else "닫기 ($countdownLeft)",
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
              color = if (isClickable) TextSecondary else TextMuted,
              maxLines = 1,
              softWrap = false
            )
          }

          Button(
            onClick = onRetry,
            enabled = isClickable,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = if (isLandscape) 8.dp else 12.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = null,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = if (isClickable) "다시 도전" else "다시 도전 ($countdownLeft)",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              softWrap = false
            )
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
  onDismissToFreePlay: () -> Unit
) {
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val scrollState = rememberScrollState()

  LaunchedEffect(isLandscape) {
    if (isLandscape) {
      delay(250)
      scrollState.animateScrollTo(60, tween(320, easing = FastOutSlowInEasing))
      delay(120)
      scrollState.animateScrollTo(0, tween(320, easing = FastOutSlowInEasing))
    }
  }

  Dialog(
    onDismissRequest = onDismissToFreePlay,
    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = true)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(if (isLandscape) 0.88f else 1f)
        .padding(horizontal = if (isLandscape) 10.dp else 16.dp, vertical = if (isLandscape) 4.dp else 16.dp)
        .heightIn(max = if (isLandscape) 330.dp else 650.dp)
        .testTag("pause_dialog"),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(6.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
          .padding(horizontal = 20.dp, vertical = if (isLandscape) 14.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "게임 일시정지",
          fontSize = if (isLandscape) 18.sp else 20.sp,
          fontWeight = FontWeight.Bold,
          color = TextPrimary
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 20.dp))

        Button(
          onClick = onResume,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary),
          contentPadding = PaddingValues(vertical = if (isLandscape) 8.dp else 12.dp)
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null)
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "계속하기",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
          )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 10.dp))

        OutlinedButton(
          onClick = onReset,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          contentPadding = PaddingValues(vertical = if (isLandscape) 8.dp else 12.dp)
        ) {
          Icon(Icons.Default.Refresh, contentDescription = null)
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "처음부터 다시하기",
            fontSize = 14.sp,
            color = TextPrimary,
            maxLines = 1,
            softWrap = false
          )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 10.dp))

        OutlinedButton(
          onClick = onDismissToFreePlay,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          contentPadding = PaddingValues(vertical = if (isLandscape) 8.dp else 12.dp)
        ) {
          Text(
            text = "닫기",
            fontSize = 14.sp,
            color = TextSecondary,
            maxLines = 1,
            softWrap = false
          )
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
          .padding(horizontal = 20.dp, vertical = 18.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Text(
          text = "게임 모드 선택",
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          color = TextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        GameMode.values().forEach { mode ->
          val isSelected = currentMode == mode
          Card(
            onClick = {
              onSelectMode(mode)
              onDismiss()
            },
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 3.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
              containerColor = if (isSelected) PastelPinkContainer else Color(0xFFFAFAFA)
            ),
            border = if (isSelected) BorderStroke(2.dp, PastelPinkPrimary) else null
          ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
              Text(
                text = mode.title,
                fontSize = 15.sp,
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
          Spacer(modifier = Modifier.height(10.dp))
          Text(text = "타임 어택 시간 선택", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            listOf(30, 60, 120).forEach { dur ->
              val isDurSelected = timeDuration == dur
              OutlinedButton(
                onClick = { onSelectDuration(dur) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                  width = if (isDurSelected) 1.5.dp else 1.dp,
                  color = if (isDurSelected) PastelPinkPrimary else Color(0xFFE2D8DF)
                ),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                  containerColor = if (isDurSelected) PastelPinkContainer else Color.Transparent
                )
              ) {
                Text(
                  text = "${dur}초",
                  fontSize = 13.sp,
                  color = if (isDurSelected) PastelPinkPrimary else TextSecondary,
                  fontWeight = if (isDurSelected) FontWeight.Bold else FontWeight.Medium,
                  maxLines = 1,
                  softWrap = false
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
  score: Int = 0,
  round: Int = 1,
  onRetry: () -> Unit,
  onDismiss: () -> Unit
) {
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val scrollState = rememberScrollState()

  // 게임 오버 연타로 인한 실수 눌림 방지 카운트다운 (4 -> 3 -> 2 -> 1 -> 0)
  var countdownLeft by remember { mutableIntStateOf(4) }
  LaunchedEffect(Unit) {
    while (countdownLeft > 0) {
      delay(1000L)
      countdownLeft--
    }
  }
  val isClickable = countdownLeft == 0

  LaunchedEffect(isLandscape) {
    if (isLandscape) {
      delay(250)
      scrollState.animateScrollTo(60, tween(320, easing = FastOutSlowInEasing))
      delay(120)
      scrollState.animateScrollTo(0, tween(320, easing = FastOutSlowInEasing))
    }
  }

  Dialog(
    onDismissRequest = { if (isClickable) onDismiss() },
    properties = DialogProperties(
      dismissOnBackPress = isClickable,
      dismissOnClickOutside = isClickable,
      usePlatformDefaultWidth = true
    )
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(if (isLandscape) 0.88f else 1f)
        .padding(horizontal = if (isLandscape) 10.dp else 16.dp, vertical = if (isLandscape) 4.dp else 16.dp)
        .heightIn(max = if (isLandscape) 330.dp else 650.dp)
        .testTag("pattern_fail_dialog"),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(6.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
          .padding(horizontal = 20.dp, vertical = if (isLandscape) 12.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "아쉬워요!",
          fontSize = if (isLandscape) 18.sp else 20.sp,
          fontWeight = FontWeight.Bold,
          color = CoralAlert
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 2.dp else 6.dp))

        Text(
          text = "패턴이 틀렸어요! 다시 도전할까요?",
          fontSize = if (isLandscape) 13.sp else 14.sp,
          color = TextSecondary,
          textAlign = TextAlign.Center,
          maxLines = 1,
          softWrap = false
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 10.dp))

        // 실수 클릭 방지 카운트다운 배지 (4초)
        if (!isClickable) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = CoralAlert.copy(alpha = 0.12f),
            modifier = Modifier.padding(bottom = 6.dp)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                text = "$countdownLeft",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = CoralAlert
              )
              Text(
                text = "실수 눌림 방지 대기 중...",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CoralAlert
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

        // 기억력 모드 달성 점수 및 라운드 표시 카드
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = PastelPinkContainer)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = if (isLandscape) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(text = "현재 점수", fontSize = 13.sp, color = TextSecondary)
              Text(text = "${score}점", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PastelPinkPrimary)
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(text = "도전 스테이지", fontSize = 12.sp, color = TextSecondary)
              Text(text = "Stage $round", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            }
          }
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 10.dp else 18.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            enabled = isClickable,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = if (isLandscape) 8.dp else 12.dp)
          ) {
            Text(
              text = if (isClickable) "닫기" else "닫기 ($countdownLeft)",
              color = if (isClickable) TextSecondary else TextMuted,
              maxLines = 1,
              softWrap = false
            )
          }

          Button(
            onClick = onRetry,
            enabled = isClickable,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
          ) {
            Text(
              text = if (isClickable) "재도전" else "재도전 ($countdownLeft)",
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              softWrap = false
            )
          }
        }
      }
    }
  }
}

@Composable
fun DifficultySelectorDialog(
  currentDifficulty: com.krdondon.quickpush.model.GameDifficulty,
  onSelectDifficulty: (com.krdondon.quickpush.model.GameDifficulty) -> Unit,
  onDismiss: () -> Unit
) {
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val scrollState = rememberScrollState()

  LaunchedEffect(isLandscape) {
    if (isLandscape) {
      delay(250)
      scrollState.animateScrollTo(60, tween(320, easing = FastOutSlowInEasing))
      delay(120)
      scrollState.animateScrollTo(0, tween(320, easing = FastOutSlowInEasing))
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = true)
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(if (isLandscape) 0.88f else 1f)
        .padding(horizontal = if (isLandscape) 10.dp else 16.dp, vertical = if (isLandscape) 4.dp else 16.dp)
        .heightIn(max = if (isLandscape) 330.dp else 650.dp)
        .testTag("difficulty_dialog"),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(6.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
          .padding(horizontal = 20.dp, vertical = if (isLandscape) 14.dp else 20.dp)
      ) {
        Text(
          text = "난이도 설정",
          fontSize = if (isLandscape) 18.sp else 20.sp,
          fontWeight = FontWeight.Bold,
          color = TextPrimary
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 8.dp else 14.dp))

        com.krdondon.quickpush.model.GameDifficulty.values().forEach { diff ->
          val isSelected = currentDifficulty == diff
          Card(
            onClick = {
              onSelectDifficulty(diff)
              onDismiss()
            },
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = if (isLandscape) 3.dp else 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
              containerColor = if (isSelected) PastelPinkContainer else Color(0xFFFAFAFA)
            ),
            border = if (isSelected) BorderStroke(2.dp, PastelPinkPrimary) else BorderStroke(1.dp, Color(0xFFEEEEEE))
          ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = if (isLandscape) 6.dp else 10.dp)) {
              Text(
                text = diff.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) PastelPinkPrimary else TextPrimary
              )
              Text(
                text = diff.description,
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                softWrap = false
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 8.dp else 14.dp))

        Button(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary),
          contentPadding = PaddingValues(vertical = if (isLandscape) 8.dp else 12.dp)
        ) {
          Text("닫기", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

@Composable
fun StageTimeoutDialog(
  currentStage: Int,
  score: Int = 0,
  touches: Int = 0,
  onRetryPrevious: () -> Unit,
  onRestartFirst: () -> Unit,
  onDismissToFreePlay: () -> Unit
) {
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val scrollState = rememberScrollState()
  val prevStage = (currentStage - 1).coerceAtLeast(1)

  // 게임 오버 연타로 인한 실수 눌림 방지 카운트다운 (4 -> 3 -> 2 -> 1 -> 0)
  var countdownLeft by remember { mutableIntStateOf(4) }
  LaunchedEffect(Unit) {
    while (countdownLeft > 0) {
      delay(1000L)
      countdownLeft--
    }
  }
  val isClickable = countdownLeft == 0

  // 가로 모드 시 스크롤 가능함을 시각적으로 알려주는 미세 바운스(들썩임) 애니메이션
  LaunchedEffect(isLandscape) {
    if (isLandscape) {
      delay(250)
      scrollState.animateScrollTo(60, tween(320, easing = FastOutSlowInEasing))
      delay(120)
      scrollState.animateScrollTo(0, tween(320, easing = FastOutSlowInEasing))
    }
  }

  Dialog(
    onDismissRequest = { if (isClickable) onDismissToFreePlay() },
    properties = DialogProperties(
      dismissOnBackPress = isClickable,
      dismissOnClickOutside = isClickable,
      usePlatformDefaultWidth = true
    )
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth(if (isLandscape) 0.88f else 1f)
        .padding(horizontal = if (isLandscape) 10.dp else 16.dp, vertical = if (isLandscape) 4.dp else 16.dp)
        .heightIn(max = if (isLandscape) 330.dp else 650.dp)
        .testTag("stage_timeout_dialog"),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      elevation = CardDefaults.cardElevation(6.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(scrollState)
          .padding(horizontal = 20.dp, vertical = if (isLandscape) 12.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "GAME OVER",
          fontSize = if (isLandscape) 20.sp else 24.sp,
          fontWeight = FontWeight.Black,
          color = CoralAlert
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 2.dp else 6.dp))

        Text(
          text = "시간이 초과되었어요!",
          fontSize = if (isLandscape) 14.sp else 16.sp,
          fontWeight = FontWeight.Bold,
          color = TextPrimary
        )

        Text(
          text = "현재 도전: Stage $currentStage",
          fontSize = 12.sp,
          color = TextSecondary
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 10.dp))

        // 실수 클릭 방지 카운트다운 배지 (4초)
        if (!isClickable) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = CoralAlert.copy(alpha = 0.12f),
            modifier = Modifier.padding(bottom = 6.dp)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                text = "$countdownLeft",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = CoralAlert
              )
              Text(
                text = "실수 눌림 방지 대기 중...",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CoralAlert
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

        // 알맞게 최종 점수 및 누적 팝 출력 카드
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = PastelPinkContainer)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = if (isLandscape) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(text = "현재 점수", fontSize = 13.sp, color = TextSecondary)
              Text(text = "${score}점", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PastelPinkPrimary)
            }
            if (touches > 0) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(text = "누적 팝 횟수", fontSize = 12.sp, color = TextSecondary)
                Text(text = "${touches}회", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 8.dp else 14.dp))

        Button(
          onClick = onRetryPrevious,
          enabled = isClickable,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          colors = ButtonDefaults.buttonColors(containerColor = PastelPinkPrimary),
          contentPadding = PaddingValues(vertical = if (isLandscape) 8.dp else 12.dp)
        ) {
          Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (isClickable) {
              if (currentStage > 1) "재도전 (Stage ${prevStage}부터)" else "재도전 (Stage 1부터)"
            } else {
              "재도전 ($countdownLeft)"
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
          )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 10.dp))

        OutlinedButton(
          onClick = onRestartFirst,
          enabled = isClickable,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          contentPadding = PaddingValues(vertical = if (isLandscape) 8.dp else 12.dp)
        ) {
          Text(
            text = if (isClickable) "처음부터 (Stage 1)" else "처음부터 ($countdownLeft)",
            fontSize = 14.sp,
            color = if (isClickable) TextPrimary else TextMuted,
            maxLines = 1,
            softWrap = false
          )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 10.dp))

        OutlinedButton(
          onClick = onDismissToFreePlay,
          enabled = isClickable,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          contentPadding = PaddingValues(vertical = if (isLandscape) 8.dp else 12.dp)
        ) {
          Text(
            text = if (isClickable) "닫기 (자율 모드로)" else "닫기 ($countdownLeft)",
            fontSize = 14.sp,
            color = if (isClickable) TextSecondary else TextMuted,
            maxLines = 1,
            softWrap = false
          )
        }
      }
    }
  }
}
