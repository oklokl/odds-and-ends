package com.krdondon.quickpush.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdondon.quickpush.model.AnimationIntensity
import com.krdondon.quickpush.model.BubbleItem
import com.krdondon.quickpush.model.ConsoleColor
import com.krdondon.quickpush.model.FloatingScore
import com.krdondon.quickpush.model.GridDimension
import com.krdondon.quickpush.model.Particle
import kotlinx.coroutines.launch

@Composable
fun BunnyQuickPushConsole(
  bubbles: List<BubbleItem>,
  gridDimension: GridDimension,
  consoleColor: ConsoleColor,
  isSoundEnabled: Boolean,
  isGlowEnabled: Boolean,
  isWaveEnabled: Boolean,
  isBorderSparkleEnabled: Boolean,
  animationIntensity: AnimationIntensity,
  floatingScores: List<FloatingScore>,
  particles: List<Particle>,
  onBubbleClick: (id: Int, x: Float, y: Float) -> Unit,
  onModeButtonClick: () -> Unit,
  onPowerButtonClick: () -> Unit,
  onVolumeButtonClick: () -> Unit,
  onBackPushClick: () -> Unit,
  onGridAvailableSizeChanged: (widthDp: Float, heightDp: Float) -> Unit,
  modifier: Modifier = Modifier
) {
  val scope = rememberCoroutineScope()
  val snapAnim = remember { Animatable(1.0f) }

  val infiniteTransition = rememberInfiniteTransition(label = "bunnyCheek")
  val cheekGlow by infiniteTransition.animateFloat(
    initialValue = 0.75f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(1200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "cheekGlow"
  )

  BoxWithConstraints(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    val availableW = maxWidth.value
    val availableH = maxHeight.value

    // Notify ViewModel of available layout space for responsive grid calculation
    onGridAvailableSizeChanged(availableW, availableH)

    val isCompact = availableH < 440f

    val rows = gridDimension.rows
    val cols = gridDimension.cols
    val visibleCount = rows * cols
    val isAllCleared = bubbles.take(visibleCount).count { it.isLit } == 0
    val bubbleSize = gridDimension.bubbleSizeDp.dp
    val spacing = gridDimension.spacingDp.dp

    val density = LocalDensity.current
    val trayPaddingDp = if (isCompact) 8.dp else 12.dp
    val trayPaddingPx = with(density) { trayPaddingDp.toPx() }
    val bubbleSizePx = with(density) { bubbleSize.toPx() }
    val spacingPx = with(density) { spacing.toPx() }

    // Calculate console width that cleanly wraps the responsive bubbles
    val gridContentW = (cols * gridDimension.bubbleSizeDp + (cols - 1) * gridDimension.spacingDp + (trayPaddingDp.value * 2)).dp
    val consoleW = (gridContentW + (if (isCompact) 18.dp else 24.dp)).coerceIn(270.dp, (availableW - 8f).dp)

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.wrapContentSize()
    ) {
      // 1. Bunny Ears on Top of the Console
      BunnyEarsHeader(
        width = (gridContentW * 0.75f).coerceIn(180.dp, 340.dp),
        height = if (isCompact) 38.dp else 56.dp,
        bodyColor = consoleColor.bodyColor,
        innerEarColor = consoleColor.innerEarColor
      )

      // 2. Main Bunny Toy Housing with 3D Neumorphic Shadows and Border Highlight
      Card(
        modifier = Modifier
          .width(consoleW)
          .shadow(
            elevation = 18.dp,
            shape = RoundedCornerShape(topStart = 52.dp, topEnd = 52.dp, bottomStart = 40.dp, bottomEnd = 40.dp),
            ambientColor = Color(0x35000000),
            spotColor = Color(0x35000000)
          ),
        shape = RoundedCornerShape(topStart = 52.dp, topEnd = 52.dp, bottomStart = 40.dp, bottomEnd = 40.dp),
        colors = CardDefaults.cardColors(containerColor = consoleColor.bodyColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
      ) {
        Column(
          modifier = Modifier
            .wrapContentSize()
            .background(
              brush = Brush.verticalGradient(
                colors = listOf(
                  consoleColor.bodyColor,
                  consoleColor.bodyColor,
                  consoleColor.accentColor
                )
              )
            )
            .border(
              width = 2.5.dp,
              brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.65f), Color.Transparent, Color.Black.copy(alpha = 0.15f))
              ),
              shape = RoundedCornerShape(topStart = 52.dp, topEnd = 52.dp, bottomStart = 40.dp, bottomEnd = 40.dp)
            )
            .padding(horizontal = if (isCompact) 10.dp else 14.dp, vertical = if (isCompact) 6.dp else 10.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          // Bunny White Face Mask with Eyes, Nose, Mouth & Glowing Cheeks
          BunnyFacePlate(
            width = (gridContentW * 0.70f).coerceIn(200.dp, 340.dp),
            height = if (isCompact) 42.dp else 58.dp,
            cheekGlow = cheekGlow
          )

          Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))

          // 3 Mechanical Toy Control Buttons [ ☰ 모드 ] [ ⏻ 전원 ] [ 🔊 볼륨 ]
          ToyControlButtonsRow(
            isSoundEnabled = isSoundEnabled,
            buttonColor = consoleColor.accentColor,
            isCompact = isCompact,
            onModeClick = onModeButtonClick,
            onPowerClick = onPowerButtonClick,
            onVolumeClick = onVolumeButtonClick
          )

          Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 10.dp))

          // 3. Recessed Cream Silicone Tray holding the RESPONSIVE Bubbles Grid
          Box(
            modifier = Modifier
              .wrapContentSize()
              .clip(RoundedCornerShape(24.dp))
              .background(consoleColor.plateColor)
              .border(3.5.dp, Color(0xFFE2D6C6), RoundedCornerShape(24.dp))
              .shadow(elevation = 4.dp, shape = RoundedCornerShape(24.dp))
              .padding(horizontal = trayPaddingDp, vertical = trayPaddingDp),
            contentAlignment = Alignment.Center
          ) {
            // Responsive Dynamic Grid
            Column(
              verticalArrangement = Arrangement.spacedBy(spacing),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              for (r in 0 until rows) {
                Row(
                  horizontalArrangement = Arrangement.spacedBy(spacing),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  for (c in 0 until cols) {
                    val bubbleIndex = r * cols + c
                    val bubble = bubbles.getOrNull(bubbleIndex)
                    if (bubble != null) {
                      // Precise center calculation in tray coordinate space for sparkles & score
                      val centerTrayX = trayPaddingPx + c * (bubbleSizePx + spacingPx) + (bubbleSizePx / 2f)
                      val centerTrayY = trayPaddingPx + r * (bubbleSizePx + spacingPx) + (bubbleSizePx / 2f)

                      PushPopBubble(
                        bubble = bubble,
                        sizeDp = bubbleSize,
                        isGlowEnabled = isGlowEnabled,
                        isWaveEnabled = isWaveEnabled,
                        isBorderSparkleEnabled = isBorderSparkleEnabled,
                        animationIntensity = animationIntensity,
                        onBubbleClick = { id, _, _ ->
                          onBubbleClick(id, centerTrayX, centerTrayY)
                        }
                      )
                    } else {
                      Spacer(modifier = Modifier.size(bubbleSize))
                    }
                  }
                }
              }
            }

            // Floating Scores and Glitter Sparkle Particles Overlay
            FloatingTextAndParticleOverlay(
              floatingScores = floatingScores,
              particles = particles,
              modifier = Modifier.matchParentSize()
            )
          }

          Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 10.dp))

          // 4. Bottom Bunny Paws & Back Push Bar
          BunnyBottomPawsAndBackPush(
            width = (gridContentW * 0.90f).coerceIn(240.dp, 460.dp),
            accentColor = consoleColor.accentColor,
            snapScale = snapAnim.value,
            isCompact = isCompact,
            isAllCleared = isAllCleared,
            onBackPushClick = {
              scope.launch {
                snapAnim.animateTo(0.92f, tween(50, easing = LinearEasing))
                snapAnim.animateTo(1.0f, tween(140, easing = FastOutSlowInEasing))
              }
              onBackPushClick()
            }
          )
        }
      }
    }
  }
}

@Composable
private fun BunnyEarsHeader(
  width: Dp,
  height: Dp,
  bodyColor: Color,
  innerEarColor: Color
) {
  Box(
    modifier = Modifier
      .width(width)
      .height(height)
      .offset(y = if (height < 45.dp) 6.dp else 10.dp),
    contentAlignment = Alignment.BottomCenter
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height

      // Left Ear
      val leftEarCenter = Offset(w * 0.32f, h * 0.45f)
      val leftEarPath = Path().apply {
        moveTo(leftEarCenter.x - 18f, h)
        cubicTo(
          leftEarCenter.x - 26f, 0f,
          leftEarCenter.x + 26f, 0f,
          leftEarCenter.x + 18f, h
        )
        close()
      }
      drawPath(leftEarPath, color = bodyColor)

      // Left Inner Ear Cushion
      val leftInnerPath = Path().apply {
        moveTo(leftEarCenter.x - 9f, h * 0.9f)
        cubicTo(
          leftEarCenter.x - 13f, h * 0.18f,
          leftEarCenter.x + 13f, h * 0.18f,
          leftEarCenter.x + 9f, h * 0.9f
        )
        close()
      }
      drawPath(leftInnerPath, color = innerEarColor)

      // Right Ear (perky tilt)
      val rightEarCenter = Offset(w * 0.68f, h * 0.45f)
      val rightEarPath = Path().apply {
        moveTo(rightEarCenter.x - 18f, h)
        cubicTo(
          rightEarCenter.x - 22f, -4f,
          rightEarCenter.x + 32f, 4f,
          rightEarCenter.x + 18f, h
        )
        close()
      }
      drawPath(rightEarPath, color = bodyColor)

      // Right Inner Ear Cushion
      val rightInnerPath = Path().apply {
        moveTo(rightEarCenter.x - 9f, h * 0.9f)
        cubicTo(
          rightEarCenter.x - 11f, h * 0.15f,
          rightEarCenter.x + 15f, h * 0.22f,
          rightEarCenter.x + 9f, h * 0.9f
        )
        close()
      }
      drawPath(rightInnerPath, color = innerEarColor)
    }
  }
}

@Composable
private fun BunnyFacePlate(
  width: Dp,
  height: Dp,
  cheekGlow: Float
) {
  Box(
    modifier = Modifier
      .width(width)
      .height(height)
      .clip(RoundedCornerShape(height / 2))
      .background(Color.White)
      .border(2.dp, Color(0xFFFFDDE5), RoundedCornerShape(height / 2)),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      val midX = w / 2f
      val midY = h / 2f

      // 1. Rosy Blushing Cheeks
      val leftCheekCenter = Offset(w * 0.20f, midY + 2f)
      val rightCheekCenter = Offset(w * 0.80f, midY + 2f)
      val cheekRadius = (h * 0.26f) * cheekGlow

      val leftCheekBrush = Brush.radialGradient(
        colors = listOf(Color(0xFFFF3B6B).copy(alpha = 0.85f * cheekGlow), Color(0xFFFF8DA1).copy(alpha = 0.35f), Color.Transparent),
        center = leftCheekCenter,
        radius = cheekRadius * 1.5f
      )
      drawCircle(brush = leftCheekBrush, radius = cheekRadius, center = leftCheekCenter)

      val rightCheekBrush = Brush.radialGradient(
        colors = listOf(Color(0xFFFF3B6B).copy(alpha = 0.85f * cheekGlow), Color(0xFFFF8DA1).copy(alpha = 0.35f), Color.Transparent),
        center = rightCheekCenter,
        radius = cheekRadius * 1.5f
      )
      drawCircle(brush = rightCheekBrush, radius = cheekRadius, center = rightCheekCenter)

      // 2. Glossy Anime Eyes
      val eyeY = midY - 3f
      val eyeRadius = h * 0.18f

      // Left Eye
      val leftEyeCenter = Offset(midX - (w * 0.11f), eyeY)
      drawCircle(color = Color(0xFF2B1B17), radius = eyeRadius, center = leftEyeCenter)
      drawCircle(color = Color.White, radius = eyeRadius * 0.35f, center = leftEyeCenter - Offset(eyeRadius * 0.25f, eyeRadius * 0.25f))
      drawCircle(color = Color.White, radius = eyeRadius * 0.18f, center = leftEyeCenter + Offset(eyeRadius * 0.25f, eyeRadius * 0.25f))

      // Right Eye
      val rightEyeCenter = Offset(midX + (w * 0.11f), eyeY)
      drawCircle(color = Color(0xFF2B1B17), radius = eyeRadius, center = rightEyeCenter)
      drawCircle(color = Color.White, radius = eyeRadius * 0.35f, center = rightEyeCenter - Offset(eyeRadius * 0.25f, eyeRadius * 0.25f))
      drawCircle(color = Color.White, radius = eyeRadius * 0.18f, center = rightEyeCenter + Offset(eyeRadius * 0.25f, eyeRadius * 0.25f))

      // 3. Cute Brown Nose & Little Open Mouth
      val noseCenter = Offset(midX, midY + 2f)
      drawCircle(color = Color(0xFF5D4037), radius = eyeRadius * 0.32f, center = noseCenter)

      drawCircle(
        color = Color(0xFF5D4037),
        radius = eyeRadius * 0.36f,
        center = Offset(midX, midY + (h * 0.18f)),
        style = Stroke(width = 1.8f)
      )
    }
  }
}

@Composable
private fun ToyControlButtonsRow(
  isSoundEnabled: Boolean,
  buttonColor: Color,
  isCompact: Boolean,
  onModeClick: () -> Unit,
  onPowerClick: () -> Unit,
  onVolumeClick: () -> Unit
) {
  val btnSize = if (isCompact) 26.dp else 34.dp
  val iconSize = if (isCompact) 13.dp else 16.dp
  val fontSize = if (isCompact) 8.sp else 9.sp

  Row(
    horizontalArrangement = Arrangement.spacedBy(if (isCompact) 10.dp else 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    ToyMechanicalButton(
      icon = { Icon(Icons.Default.List, contentDescription = "모드 선택", tint = Color.White, modifier = Modifier.size(iconSize)) },
      label = "MODE",
      size = btnSize,
      fontSize = fontSize,
      buttonColor = buttonColor,
      onClick = onModeClick,
      testTag = "toy_mode_button"
    )

    ToyMechanicalButton(
      icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = "전원 / 리셋", tint = Color.White, modifier = Modifier.size(iconSize)) },
      label = "POWER",
      size = btnSize,
      fontSize = fontSize,
      buttonColor = buttonColor,
      onClick = onPowerClick,
      testTag = "toy_power_button"
    )

    ToyMechanicalButton(
      icon = {
        Icon(
          if (isSoundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
          contentDescription = "볼륨 조절",
          tint = Color.White,
          modifier = Modifier.size(iconSize)
        )
      },
      label = "VOL",
      size = btnSize,
      fontSize = fontSize,
      buttonColor = buttonColor,
      onClick = onVolumeClick,
      testTag = "toy_volume_button"
    )
  }
}

@Composable
private fun ToyMechanicalButton(
  icon: @Composable () -> Unit,
  label: String,
  size: Dp,
  fontSize: androidx.compose.ui.unit.TextUnit,
  buttonColor: Color,
  onClick: () -> Unit,
  testTag: String
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .testTag(testTag)
      .clickable(onClick = onClick)
  ) {
    Box(
      modifier = Modifier
        .size(size)
        .shadow(4.dp, RoundedCornerShape(10.dp))
        .clip(RoundedCornerShape(10.dp))
        .background(buttonColor)
        .border(1.5.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(10.dp)),
      contentAlignment = Alignment.Center
    ) {
      icon()
    }
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = label,
      fontSize = fontSize,
      fontWeight = FontWeight.Bold,
      color = Color.White.copy(alpha = 0.9f)
    )
  }
}

@Composable
private fun BunnyBottomPawsAndBackPush(
  width: Dp,
  accentColor: Color,
  snapScale: Float,
  isCompact: Boolean,
  isAllCleared: Boolean,
  onBackPushClick: () -> Unit
) {
  val barHeight = if (isCompact) 32.dp else 42.dp
  val pawSize = if (isCompact) 28.dp else 34.dp
  val fontSize = if (isCompact) 11.sp else 13.sp

  Row(
    modifier = Modifier.width(width),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    BunnyPawPad(accentColor, pawSize)

    Card(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 6.dp)
        .height(barHeight)
        .testTag("back_push_button")
        .clickable(onClick = onBackPushClick)
        .shadow(6.dp, RoundedCornerShape(20.dp)),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = if (isAllCleared) Color(0xFF00E676) else Color.White)
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            brush = Brush.verticalGradient(
              colors = if (isAllCleared) listOf(Color(0xFF00E676), Color(0xFF00C853)) else listOf(Color.White, Color(0xFFFFF0F5))
            )
          )
          .border(2.dp, if (isAllCleared) Color.White else accentColor, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Text(
            text = if (isAllCleared) "⚡ 다음 단계로 돌파! (클리어)" else "⚡ 뒷면 푸시 (버블 리셋)",
            color = if (isAllCleared) Color.White else accentColor,
            fontWeight = FontWeight.Black,
            fontSize = fontSize
          )
        }
      }
    }

    BunnyPawPad(accentColor, pawSize)
  }
}

@Composable
private fun BunnyPawPad(color: Color, sizeDp: Dp) {
  Box(
    modifier = Modifier
      .size(sizeDp)
      .clip(CircleShape)
      .background(Color.White.copy(alpha = 0.85f)),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.size(sizeDp * 0.65f)) {
      val center = Offset(size.width / 2f, size.height / 2f + 1f)
      drawCircle(color = color, radius = size.width * 0.22f, center = center)
      drawCircle(color = color, radius = size.width * 0.09f, center = center + Offset(-size.width * 0.25f, -size.width * 0.25f))
      drawCircle(color = color, radius = size.width * 0.11f, center = center + Offset(0f, -size.width * 0.32f))
      drawCircle(color = color, radius = size.width * 0.09f, center = center + Offset(size.width * 0.25f, -size.width * 0.25f))
    }
  }
}
