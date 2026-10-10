package com.krdondon.quickpush.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdondon.quickpush.model.GameMode
import com.krdondon.quickpush.ui.theme.PastelPinkContainer
import com.krdondon.quickpush.ui.theme.PastelPinkPrimary
import com.krdondon.quickpush.ui.theme.TextPrimary

@Composable
fun GameTopBar(
  currentMode: GameMode,
  isSoundEnabled: Boolean,
  onToggleSound: () -> Unit,
  onOpenModeSelector: () -> Unit,
  onPause: () -> Unit,
  onReset: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 6.dp)
      .shadow(4.dp, RoundedCornerShape(20.dp)),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Left: App Name & Mode Badge
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Box(
          modifier = Modifier
            .background(PastelPinkPrimary, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "푸시팝",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
          )
        }

        Text(
          text = currentMode.title,
          color = TextPrimary,
          fontWeight = FontWeight.SemiBold,
          fontSize = 15.sp
        )
      }

      // Right: Control Buttons
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Sound toggle
        FilledTonalIconButton(
          onClick = onToggleSound,
          modifier = Modifier
            .size(40.dp)
            .testTag("sound_toggle_button"),
          colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = PastelPinkContainer
          )
        ) {
          Icon(
            imageVector = if (isSoundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
            contentDescription = if (isSoundEnabled) "소리 끄기" else "소리 켜기",
            tint = PastelPinkPrimary,
            modifier = Modifier.size(20.dp)
          )
        }

        // Mode selector
        FilledTonalIconButton(
          onClick = onOpenModeSelector,
          modifier = Modifier
            .size(40.dp)
            .testTag("mode_selector_button"),
          colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = PastelPinkContainer
          )
        ) {
          Icon(
            imageVector = Icons.Default.Category,
            contentDescription = "모드 변경",
            tint = PastelPinkPrimary,
            modifier = Modifier.size(20.dp)
          )
        }

        // Restart / Reset
        FilledTonalIconButton(
          onClick = onReset,
          modifier = Modifier
            .size(40.dp)
            .testTag("reset_button"),
          colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = PastelPinkContainer
          )
        ) {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "초기화",
            tint = PastelPinkPrimary,
            modifier = Modifier.size(20.dp)
          )
        }

        // Pause
        FilledTonalIconButton(
          onClick = onPause,
          modifier = Modifier
            .size(40.dp)
            .testTag("pause_button"),
          colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = PastelPinkContainer
          )
        ) {
          Icon(
            imageVector = Icons.Default.Pause,
            contentDescription = "일시정지",
            tint = PastelPinkPrimary,
            modifier = Modifier.size(20.dp)
          )
        }

        // Settings
        FilledTonalIconButton(
          onClick = onOpenSettings,
          modifier = Modifier
            .size(40.dp)
            .testTag("settings_button"),
          colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = PastelPinkContainer
          )
        ) {
          Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "설정",
            tint = PastelPinkPrimary,
            modifier = Modifier.size(20.dp)
          )
        }
      }
    }
  }
}
