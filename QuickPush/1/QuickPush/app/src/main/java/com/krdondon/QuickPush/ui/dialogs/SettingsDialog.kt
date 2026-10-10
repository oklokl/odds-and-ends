package com.krdondon.QuickPush.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.krdondon.QuickPush.model.AnimationIntensity
import com.krdondon.QuickPush.model.BubbleTheme
import com.krdondon.QuickPush.model.ConsoleColor
import com.krdondon.QuickPush.model.SoundStyle
import com.krdondon.QuickPush.ui.PushPopUiState
import com.krdondon.QuickPush.ui.theme.CoralAlert
import com.krdondon.QuickPush.ui.theme.PastelPinkContainer
import com.krdondon.QuickPush.ui.theme.PastelPinkPrimary
import com.krdondon.QuickPush.ui.theme.TextMuted
import com.krdondon.QuickPush.ui.theme.TextPrimary
import com.krdondon.QuickPush.ui.theme.TextSecondary

@Composable
fun SettingsDialog(
  uiState: PushPopUiState,
  onDismiss: () -> Unit,
  onToggleSound: (Boolean) -> Unit,
  onSetSoundStyle: (SoundStyle) -> Unit,
  onToggleHaptic: (Boolean) -> Unit,
  onToggleGlow: (Boolean) -> Unit,
  onToggleWave: (Boolean) -> Unit,
  onToggleSparkle: (Boolean) -> Unit,
  onSetAnimIntensity: (AnimationIntensity) -> Unit,
  onSetBubbleTheme: (BubbleTheme) -> Unit,
  onSetConsoleColor: (ConsoleColor) -> Unit,
  onToggleSiliconeMode: (Boolean) -> Unit,
  onRequestResetData: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
        .testTag("settings_dialog"),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp)
          .verticalScroll(rememberScrollState())
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "게임 설정",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
          )
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "닫기", tint = TextSecondary)
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Sound Settings
        SectionHeader("사운드 & 촉각")
        SettingSwitchItem(
          title = "효과음",
          subtitle = "버블 팝 소리를 재생합니다",
          checked = uiState.isSoundEnabled,
          onCheckedChange = onToggleSound
        )

        if (uiState.isSoundEnabled) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            SoundStyle.values().forEach { style ->
              val isSelected = uiState.soundStyle == style
              OutlinedButton(
                onClick = { onSetSoundStyle(style) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                  containerColor = if (isSelected) PastelPinkContainer else Color.Transparent
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                  brush = androidx.compose.ui.graphics.SolidColor(
                    if (isSelected) PastelPinkPrimary else Color(0xFFE0E0E0)
                  )
                )
              ) {
                Text(
                  text = style.title,
                  color = if (isSelected) PastelPinkPrimary else TextSecondary,
                  fontSize = 12.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
              }
            }
          }
        }

        SettingSwitchItem(
          title = "햅틱 진동",
          subtitle = "버튼을 누를 때 미세한 진동을 제공합니다",
          checked = uiState.isHapticEnabled,
          onCheckedChange = onToggleHaptic
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Visual Effects
        SectionHeader("시각 효과")
        SettingSwitchItem(
          title = "발광 효과",
          subtitle = "터치 시 버튼 내부에서 부드러운 빛이 퍼집니다",
          checked = uiState.isGlowEnabled,
          onCheckedChange = onToggleGlow
        )
        SettingSwitchItem(
          title = "원형 파동 효과",
          subtitle = "터치 지점에서 바깥으로 링 파동이 퍼집니다",
          checked = uiState.isWaveEnabled,
          onCheckedChange = onToggleWave
        )
        SettingSwitchItem(
          title = "테두리 반짝임",
          subtitle = "외곽선을 따라 반짝이는 빛이 지나갑니다",
          checked = uiState.isBorderSparkleEnabled,
          onCheckedChange = onToggleSparkle
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Animation Intensity
        SectionHeader("애니메이션 강도")
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          AnimationIntensity.values().forEach { intensity ->
            val isSelected = uiState.animationIntensity == intensity
            OutlinedButton(
              onClick = { onSetAnimIntensity(intensity) },
              modifier = Modifier.weight(1f),
              colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isSelected) PastelPinkContainer else Color.Transparent
              ),
              border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                  if (isSelected) PastelPinkPrimary else Color(0xFFE0E0E0)
                )
              )
            ) {
              Text(
                text = intensity.title,
                color = if (isSelected) PastelPinkPrimary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bunny Console Color Skin (Matching Reference Image)
        SectionHeader("게임기 캐릭터 스킨")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ConsoleColor.entries.forEach { cColor ->
            val isSelected = uiState.consoleColor == cColor
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (isSelected) PastelPinkContainer else Color(0xFFFAFAFA))
                .border(
                  width = if (isSelected) 2.dp else 1.dp,
                  color = if (isSelected) PastelPinkPrimary else Color(0xFFEEEEEE),
                  shape = RoundedCornerShape(16.dp)
                )
                .clickable { onSetConsoleColor(cColor) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = cColor.title,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) PastelPinkPrimary else TextPrimary
              )
              Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                  modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(cColor.bodyColor)
                    .border(1.dp, Color.White, CircleShape)
                )
                Box(
                  modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(cColor.innerEarColor)
                    .border(1.dp, Color.White, CircleShape)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Theme
        SectionHeader("버블 색상 테마")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          BubbleTheme.values().forEach { theme ->
            val isSelected = uiState.theme == theme
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (isSelected) PastelPinkContainer else Color(0xFFFAFAFA))
                .border(
                  width = if (isSelected) 2.dp else 1.dp,
                  color = if (isSelected) PastelPinkPrimary else Color(0xFFEEEEEE),
                  shape = RoundedCornerShape(16.dp)
                )
                .clickable { onSetBubbleTheme(theme) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = theme.title,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) PastelPinkPrimary else TextPrimary
              )
              Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                theme.colors.take(4).forEach { col ->
                  Box(
                    modifier = Modifier
                      .size(18.dp)
                      .clip(CircleShape)
                      .background(col)
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Toggle Mode
        SectionHeader("플레이 모드 설정")
        SettingSwitchItem(
          title = "토글 고정 모드",
          subtitle = "버블을 누르면 눌린 상태로 유지되고 다시 누르면 복귀합니다",
          checked = uiState.isToggleMode,
          onCheckedChange = onToggleSiliconeMode
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Reset Data
        OutlinedButton(
          onClick = onRequestResetData,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralAlert),
          border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(CoralAlert)
          )
        ) {
          Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("게임 기록 초기화", fontSize = 13.sp)
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String) {
  Text(
    text = title,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold,
    color = TextSecondary,
    modifier = Modifier.padding(vertical = 4.dp)
  )
}

@Composable
private fun SettingSwitchItem(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
      Text(text = subtitle, fontSize = 11.sp, color = TextMuted)
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = PastelPinkPrimary
      )
    )
  }
}
