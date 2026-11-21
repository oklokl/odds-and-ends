package com.krdondon.quickpush.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.krdondon.quickpush.R
import com.krdondon.quickpush.model.AnimationIntensity
import com.krdondon.quickpush.model.BubbleItem
import kotlinx.coroutines.launch

@Composable
fun PushPopBubble(
  bubble: BubbleItem,
  sizeDp: Dp,
  isGlowEnabled: Boolean,
  isWaveEnabled: Boolean,
  isBorderSparkleEnabled: Boolean,
  animationIntensity: AnimationIntensity,
  ledBreathProvider: () -> Float = { 1.0f },
  redBreathingProvider: () -> Float = { 0f },
  modifier: Modifier = Modifier,
  onBubbleClick: (id: Int, x: Float, y: Float) -> Unit
) {
  val scope = rememberCoroutineScope()
  var isPressing by remember { mutableStateOf(false) }

  val scaleAnim = remember { Animatable(1.0f) }
  val waveProgress = remember { Animatable(0f) }
  val glowAlpha = remember { Animatable(0f) }
  val sparkleAnim = remember { Animatable(0f) }

  val isSparkling by remember {
    derivedStateOf { sparkleAnim.value > 0f && sparkleAnim.value < 1f }
  }

  LaunchedEffect(bubble.isHighlighted || bubble.isLit, isGlowEnabled) {
    if (!isGlowEnabled) {
      glowAlpha.snapTo(0f)
    } else if (bubble.isHighlighted || bubble.isLit) {
      glowAlpha.snapTo(1f)
    } else {
      glowAlpha.animateTo(0f, tween(200))
    }
  }

  val pressDepthFactor = when (animationIntensity) {
    AnimationIntensity.SOFT -> 0.93f
    AnimationIntensity.NORMAL -> 0.86f
    AnimationIntensity.DYNAMIC -> 0.80f
  }

  val isDown = bubble.isPopped || isPressing
  val isLit = bubble.isLit || bubble.isHighlighted
  val ledColor = if (bubble.isHighlighted) Color(0xFFFFEB3B) else bubble.ledColor
  val vividColor = bubble.baseColor

  Box(
    modifier = modifier
      .size(sizeDp)
      .testTag("bubble_${bubble.id}")
      .semantics {
        contentDescription = "푸시팝 버블 ${bubble.id + 1}번"
      }
      .pointerInput(bubble.id, isGlowEnabled, isWaveEnabled, pressDepthFactor) {
        detectTapGestures(
          onPress = {
            isPressing = true
            scope.launch {
              scaleAnim.animateTo(pressDepthFactor, tween(40, easing = LinearEasing))
            }
            if (isGlowEnabled) {
              scope.launch {
                glowAlpha.snapTo(1f)
                glowAlpha.animateTo(0f, tween(320))
              }
            }
            if (isWaveEnabled) {
              scope.launch {
                waveProgress.snapTo(0f)
                waveProgress.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
              }
            }
            scope.launch {
              sparkleAnim.snapTo(0f)
              sparkleAnim.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
            }

            tryAwaitRelease()

            isPressing = false
            scope.launch {
              scaleAnim.animateTo(1.0f, spring(dampingRatio = 0.50f, stiffness = 450f))
            }
          },
          onTap = { offset ->
            onBubbleClick(bubble.id, offset.x, offset.y)
          }
        )
      },
    contentAlignment = Alignment.Center
  ) {
    // drawWithCache로 정적 브러시/경로/좌표를 크기·색상 변경 시에만 1회 계산 (매 프레임 할당 0)
    Spacer(
      modifier = Modifier
        .size(sizeDp)
        .drawWithCache {
          val w = size.width
          val h = size.height
          val radius = (w.coerceAtMost(h) / 2f) * 0.85f
          val center = Offset(w / 2f, h / 2f)
          val baseRingRadius = radius * 1.02f

          val socketBrush = Brush.radialGradient(
            colors = listOf(
              Color.White.copy(alpha = 0.8f),
              Color(0xFFE8DCD0),
              Color(0xFF8D7B68)
            ),
            center = center - Offset(radius * 0.2f, radius * 0.2f),
            radius = radius * 1.2f
          )

          val redGradientBrush = Brush.linearGradient(
            colors = RedGradientColors,
            start = Offset(center.x - baseRingRadius, center.y - baseRingRadius),
            end = Offset(center.x + baseRingRadius, center.y + baseRingRadius)
          )

          val bubbleClipPath = Path()
          val carrotPath = Path()
          val darkVivid = vividColor.copy(
            red = (vividColor.red * 0.55f).coerceIn(0f, 1f),
            green = (vividColor.green * 0.55f).coerceIn(0f, 1f),
            blue = (vividColor.blue * 0.55f).coerceIn(0f, 1f)
          )
          val rimShadowBrush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0x45000000)),
            center = center,
            radius = radius
          )

          onDrawBehind {
            val effectiveScale = scaleAnim.value
            val scaledRadius = radius * effectiveScale

            // 1. External 3D Socket Rim
            drawCircle(
              brush = socketBrush,
              radius = radius * 1.08f,
              center = center
            )

            // Socket Drop Shadow
            drawCircle(
              color = Color(0x40000000),
              radius = radius * 1.02f,
              center = center + Offset(0f, 3f)
            )

            // 2. The Silicone 3D Bubble Dome
            bubbleClipPath.reset()
            bubbleClipPath.addOval(Rect(center, scaledRadius))

            clipPath(bubbleClipPath) {
              if (!isDown) {
                val vividDomeBrush = Brush.radialGradient(
                  colors = listOf(
                    Color.White.copy(alpha = if (isLit) 0.92f else 0.85f),
                    vividColor,
                    vividColor.copy(
                      red = (vividColor.red * 0.65f).coerceIn(0f, 1f),
                      green = (vividColor.green * 0.65f).coerceIn(0f, 1f),
                      blue = (vividColor.blue * 0.65f).coerceIn(0f, 1f)
                    )
                  ),
                  center = center - Offset(scaledRadius * 0.35f, scaledRadius * 0.35f),
                  radius = scaledRadius * 1.25f
                )
                drawCircle(brush = vividDomeBrush, radius = scaledRadius, center = center)

                if (isLit) {
                  val ledBreath = ledBreathProvider()
                  drawCircle(
                    color = Color.White.copy(alpha = 0.25f * ledBreath),
                    radius = scaledRadius * 0.42f,
                    center = center
                  )
                }

                // Intense specular gloss highlight
                val specularCenter = center - Offset(scaledRadius * 0.36f, scaledRadius * 0.36f)
                drawCircle(
                  color = Color.White.copy(alpha = 0.85f),
                  radius = scaledRadius * 0.28f,
                  center = specularCenter
                )
                drawCircle(
                  color = Color.White,
                  radius = scaledRadius * 0.12f,
                  center = specularCenter - Offset(2f, 2f)
                )

                // Bottom ambient bounce light
                drawCircle(
                  color = Color.White.copy(alpha = 0.32f),
                  radius = scaledRadius * 0.32f,
                  center = center + Offset(scaledRadius * 0.28f, scaledRadius * 0.3f)
                )
              } else {
                if (isLit) {
                  val litConcaveBrush = Brush.radialGradient(
                    colors = listOf(
                      ledColor.copy(alpha = 0.75f),
                      ledColor.copy(alpha = 0.45f),
                      Color(0x40000000)
                    ),
                    center = center,
                    radius = scaledRadius
                  )
                  drawCircle(brush = litConcaveBrush, radius = scaledRadius, center = center)
                } else {
                  val concaveBrush = Brush.radialGradient(
                    colors = listOf(
                      darkVivid,
                      vividColor.copy(alpha = 0.9f),
                      Color.White.copy(alpha = 0.4f)
                    ),
                    center = center + Offset(scaledRadius * 0.25f, scaledRadius * 0.25f),
                    radius = scaledRadius * 1.15f
                  )
                  drawCircle(brush = concaveBrush, radius = scaledRadius, center = center)

                  drawCircle(brush = rimShadowBrush, radius = scaledRadius, center = center)
                  drawCircle(
                    color = Color(0x25000000),
                    radius = scaledRadius * 0.45f,
                    center = center
                  )
                }
              }

              // 발광 효과 설정 반영 (터치 및 점등 시 내부 하이라이트 글로우)
              val currentGlow = glowAlpha.value
              if (isGlowEnabled && currentGlow > 0.01f) {
                drawCircle(
                  color = Color.White.copy(alpha = 0.28f * currentGlow),
                  radius = scaledRadius * 0.78f,
                  center = center
                )
              }

              // 3. Embossed Cute Carrot Icon
              if (bubble.hasCarrotIcon) {
                val iconColor = if (isDown) {
                  Color.White.copy(alpha = 0.5f)
                } else {
                  Color.White.copy(alpha = 0.88f)
                }

                val cSize = scaledRadius * 0.46f
                val carrotCenter = center + Offset(0f, cSize * 0.08f)
                val leafTop = carrotCenter - Offset(0f, cSize * 0.68f)

                drawLine(
                  color = iconColor,
                  start = carrotCenter - Offset(0f, cSize * 0.35f),
                  end = leafTop,
                  strokeWidth = 2.4f,
                  cap = StrokeCap.Round
                )
                drawLine(
                  color = iconColor,
                  start = carrotCenter - Offset(0f, cSize * 0.35f),
                  end = leafTop + Offset(-cSize * 0.24f, cSize * 0.1f),
                  strokeWidth = 2.0f,
                  cap = StrokeCap.Round
                )
                drawLine(
                  color = iconColor,
                  start = carrotCenter - Offset(0f, cSize * 0.35f),
                  end = leafTop + Offset(cSize * 0.24f, cSize * 0.1f),
                  strokeWidth = 2.0f,
                  cap = StrokeCap.Round
                )

                carrotPath.reset()
                carrotPath.moveTo(carrotCenter.x - cSize * 0.34f, carrotCenter.y - cSize * 0.30f)
                carrotPath.quadraticBezierTo(
                  carrotCenter.x, carrotCenter.y - cSize * 0.40f,
                  carrotCenter.x + cSize * 0.34f, carrotCenter.y - cSize * 0.30f
                )
                carrotPath.quadraticBezierTo(
                  carrotCenter.x + cSize * 0.26f, carrotCenter.y + cSize * 0.2f,
                  carrotCenter.x, carrotCenter.y + cSize * 0.55f
                )
                carrotPath.quadraticBezierTo(
                  carrotCenter.x - cSize * 0.26f, carrotCenter.y + cSize * 0.2f,
                  carrotCenter.x - cSize * 0.34f, carrotCenter.y - cSize * 0.30f
                )
                carrotPath.close()

                drawPath(
                  path = carrotPath,
                  color = iconColor,
                  style = Stroke(width = 2.4f, cap = StrokeCap.Round)
                )

                drawLine(
                  color = iconColor,
                  start = carrotCenter + Offset(-cSize * 0.16f, -cSize * 0.08f),
                  end = carrotCenter + Offset(cSize * 0.12f, -cSize * 0.08f),
                  strokeWidth = 1.8f,
                  cap = StrokeCap.Round
                )
                drawLine(
                  color = iconColor,
                  start = carrotCenter + Offset(-cSize * 0.10f, cSize * 0.14f),
                  end = carrotCenter + Offset(cSize * 0.16f, cSize * 0.14f),
                  strokeWidth = 1.8f,
                  cap = StrokeCap.Round
                )
              }

              // 4. Expanding Wave Ripple
              val wave = waveProgress.value
              if (isWaveEnabled && wave > 0f && wave < 1f) {
                val rippleR = scaledRadius * wave
                val rippleAlpha = (1f - wave) * 0.85f
                drawCircle(
                  color = Color.White.copy(alpha = rippleAlpha),
                  radius = rippleR,
                  center = center,
                  style = Stroke(width = 5f * (1f - wave * 0.4f))
                )
              }
            }

            // 5. Animated Breathing Red Circle Outline
            if (isLit && !isDown) {
              drawBreathingRedCircle(
                center = center,
                bubbleRadius = radius,
                fraction = redBreathingProvider(),
                gradientBrush = redGradientBrush
              )
            }
          }
        }
    )

    // Custom SVG Sparkle Animation Overlay (graphicsLayer 람다에서 애니메이션 값을 읽어 리컴포지션 0)
    if (isSparkling) {
      Image(
        painter = painterResource(id = R.drawable.pushpop_sparkle),
        contentDescription = null,
        modifier = Modifier
          .size(sizeDp * 2.4f)
          .graphicsLayer {
            val sp = sparkleAnim.value
            val sparkleAlpha = (1f - sp) * 0.95f
            val sparkleScale = 0.85f + (sp * 0.65f)
            scaleX = sparkleScale
            scaleY = sparkleScale
            alpha = sparkleAlpha
          },
        contentScale = ContentScale.Fit
      )
    }
  }
}

// Pre-allocated static colors for zero-allocation rendering
private val RedGlowColor = Color(0xFFFF1744)
private val RedGlowAuraColor = Color(0xFFFF243B)
private val RedInnerHighlightColor = Color(0xFFFF8A9E)
private val RedGradientColors = listOf(
  Color(0xFFFF5252),
  Color(0xFFFF1744),
  Color(0xFFD50000)
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBreathingRedCircle(
  center: Offset,
  bubbleRadius: Float,
  fraction: Float,
  gradientBrush: Brush
) {
  val baseRadius = bubbleRadius * 1.02f
  val mainStroke = baseRadius * (0.12f + 0.05f * fraction)
  val mainOpacity = 0.90f + (0.10f * fraction)

  val glowStroke = baseRadius * (0.19f + 0.08f * fraction)
  val glowOpacity = 0.28f + (0.42f * fraction)
  val glowR = baseRadius * (0.99f + 0.03f * fraction)

  drawCircle(
    color = RedGlowAuraColor,
    radius = glowR,
    center = center,
    alpha = glowOpacity * 0.40f,
    style = Stroke(width = glowStroke * 1.6f)
  )
  drawCircle(
    color = RedGlowColor,
    radius = glowR,
    center = center,
    alpha = glowOpacity * 0.85f,
    style = Stroke(width = glowStroke)
  )

  drawCircle(
    brush = gradientBrush,
    radius = baseRadius,
    center = center,
    alpha = mainOpacity,
    style = Stroke(width = mainStroke)
  )

  val innerR = baseRadius - (mainStroke * 0.30f)
  val innerStroke = baseRadius * (0.028f + 0.014f * fraction)
  val innerOpacity = 0.40f + (0.50f * fraction)
  drawCircle(
    color = RedInnerHighlightColor,
    radius = innerR,
    center = center,
    alpha = innerOpacity,
    style = Stroke(width = innerStroke)
  )
}
