package com.krdondon.QuickPush.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdondon.QuickPush.model.GameMode
import com.krdondon.QuickPush.ui.theme.CoralAlert
import com.krdondon.QuickPush.ui.theme.GoldStar
import com.krdondon.QuickPush.ui.theme.PastelMint
import com.krdondon.QuickPush.ui.theme.PastelPinkContainer
import com.krdondon.QuickPush.ui.theme.PastelPinkPrimary
import com.krdondon.QuickPush.ui.theme.TextMuted
import com.krdondon.QuickPush.ui.theme.TextPrimary
import com.krdondon.QuickPush.ui.theme.TextSecondary

@Composable
fun ScoreStatusCard(
  gameMode: GameMode,
  score: Int,
  touches: Int,
  currentLevel: Int,
  levelProgress: Float,
  comboCount: Int,
  comboMultiplier: Float,
  timeRemainingSec: Int,
  freePlayElapsedSec: Long,
  patternRound: Int,
  isPatternShowing: Boolean,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 4.dp)
      .shadow(elevation = 8.dp, shape = RoundedCornerShape(22.dp), ambientColor = Color(0x20000000), spotColor = Color(0x20000000)),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = Color.White),
    elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .border(
          width = 1.5.dp,
          brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFFFEEF4))),
          shape = RoundedCornerShape(22.dp)
        )
        .padding(horizontal = 12.dp, vertical = 9.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      // Row 1: 점수, 누적 팝, 콤보 배지
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        StatusMetric(
          icon = Icons.Default.Star,
          iconTint = GoldStar,
          label = "점수",
          value = "$score"
        )

        StatusMetric(
          icon = Icons.Default.TouchApp,
          iconTint = PastelPinkPrimary,
          label = "누적 팝",
          value = "$touches"
        )

        // Combo Badge (단일 행 고정, 글자 짤림 완전 방지)
        AnimatedVisibility(
          visible = comboCount >= 2,
          enter = fadeIn() + scaleIn(),
          exit = fadeOut() + scaleOut()
        ) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(12.dp))
              .background(PastelPinkContainer)
              .border(1.5.dp, PastelPinkPrimary, RoundedCornerShape(12.dp))
              .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "x${comboMultiplier} COMBO",
              color = PastelPinkPrimary,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 11.sp,
              maxLines = 1,
              softWrap = false
            )
          }
        }
      }

      // Row 2: 모드별 상세 상태
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        when (gameMode) {
          GameMode.TIME_ATTACK -> {
            val isUrgent = timeRemainingSec <= 5
            StatusMetric(
              icon = Icons.Default.Timer,
              iconTint = if (isUrgent) CoralAlert else PastelMint,
              label = "남은 시간",
              value = "${timeRemainingSec}초",
              valueColor = if (isUrgent) CoralAlert else TextPrimary
            )
          }
          GameMode.FREE_PLAY -> {
            val mins = freePlayElapsedSec / 60
            val secs = freePlayElapsedSec % 60
            val formatted = String.format("%02d:%02d", mins, secs)
            StatusMetric(
              icon = Icons.Default.Timer,
              iconTint = PastelMint,
              label = "플레이 시간",
              value = formatted
            )
          }
          GameMode.LEVEL_CHALLENGE -> {
            StatusMetric(
              icon = Icons.Default.Bolt,
              iconTint = PastelPinkPrimary,
              label = "스테이지",
              value = "Stage $currentLevel"
            )
          }
          GameMode.PATTERN_FOLLOW -> {
            StatusMetric(
              icon = Icons.Default.Bolt,
              iconTint = PastelMint,
              label = if (isPatternShowing) "패턴 기억 중..." else "내 차례!",
              value = "라운드 $patternRound",
              valueColor = if (isPatternShowing) PastelPinkPrimary else PastelMint
            )
          }
        }
      }

      // If in Level Challenge mode, show Level Progress Bar
      if (gameMode == GameMode.LEVEL_CHALLENGE) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Lv.$currentLevel",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.width(6.dp))
          LinearProgressIndicator(
            progress = { levelProgress.coerceIn(0f, 1f) },
            modifier = Modifier
              .weight(1f)
              .height(7.dp)
              .clip(CircleShape),
            color = PastelPinkPrimary,
            trackColor = PastelPinkContainer
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Lv.${currentLevel + 1}",
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }
  }
}

@Composable
private fun StatusMetric(
  icon: ImageVector,
  iconTint: Color,
  label: String,
  value: String,
  valueColor: Color = TextPrimary
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp)
  ) {
    Box(
      modifier = Modifier
        .size(28.dp)
        .background(iconTint.copy(alpha = 0.15f), CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = iconTint,
        modifier = Modifier.size(15.dp)
      )
    }
    Column {
      Text(
        text = label,
        color = TextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1
      )
      Text(
        text = value,
        color = valueColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1
      )
    }
  }
}
