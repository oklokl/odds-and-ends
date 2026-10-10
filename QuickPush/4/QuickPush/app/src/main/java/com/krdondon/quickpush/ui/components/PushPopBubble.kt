package com.krdondon.quickpush.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.krdondon.quickpush.R
import com.krdondon.quickpush.model.AnimationIntensity
import com.krdondon.quickpush.model.BubbleItem
import kotlinx.coroutines.launch
import kotlin.math.sin

@Composable
fun PushPopBubble(
  bubble: BubbleItem,
  sizeDp: Dp,
  isGlowEnabled: Boolean,
  isWaveEnabled: Boolean,
  isBorderSparkleEnabled: Boolean,
  animationIntensity: AnimationIntensity,
  modifier: Modifier = Modifier,
  onBubbleClick: (id: Int, x: Float, y: Float) -> Unit
) {
  val scope = rememberCoroutineScope()
  var isPressing by remember { mutableStateOf(false) }

  val scaleAnim = remember { Animatable(1.0f) }
  val waveProgress = remember { Animatable(0f) }
  val glowAlpha = remember { Animatable(0f) }
  val sparkleAnim = remember { Animatable(0f) } // 0 to 1 for sparkle starburst

  // Pulsing animation for lit or highlighted LED bubbles
  val infiniteTransition = rememberInfiniteTransition(label = "ledPulse")
  val ledBreath by infiniteTransition.animateFloat(
    initialValue = 0.82f,
    targetValue = 1.05f,
    animationSpec = infiniteRepeatable(
      animation = tween(400, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "ledBreath"
  )

  LaunchedEffect(bubble.isHighlighted || bubble.isLit) {
    if (bubble.isHighlighted || bubble.isLit) {
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

  Box(
    modifier = modifier
      .size(sizeDp)
      .testTag("bubble_${bubble.id}")
      .semantics {
        contentDescription = "푸시팝 버블 ${bubble.id + 1}번"
      }
      .pointerInput(bubble.id) {
        detectTapGestures(
          onPress = { offset ->
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
            // Sparkle action trigger on tap (버튼 누를 때마다 사방으로 화려하게 반짝이는 별빛 연출)
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
    val effectiveScale = scaleAnim.value
    val isDown = bubble.isPopped || isPressing
    val isLit = bubble.isLit || bubble.isHighlighted
    val ledColor = if (bubble.isHighlighted) Color(0xFFFFEB3B) else bubble.ledColor
    val vividColor = bubble.baseColor
    val bubbleClipPath = remember { Path() }

    Canvas(modifier = Modifier.size(sizeDp)) {
      val w = size.width
      val h = size.height
      val radius = (w.coerceAtMost(h) / 2f) * 0.85f
      val center = Offset(w / 2f, h / 2f)

      // 1. External 3D Socket Rim (Deep shadow and metallic/plastic beveled ring)
      val socketBrush = Brush.radialGradient(
        colors = listOf(
          Color.White.copy(alpha = 0.8f),
          Color(0xFFE8DCD0),
          Color(0xFF8D7B68)
        ),
        center = center - Offset(radius * 0.2f, radius * 0.2f),
        radius = radius * 1.2f
      )
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
      val scaledRadius = radius * effectiveScale
      bubbleClipPath.reset()
      bubbleClipPath.addOval(Rect(center, scaledRadius))

      clipPath(bubbleClipPath) {
        if (!isDown) {
          // --- CONVEX 3D DOME (Puffed outward with rich vibrant color) ---
          if (isLit) {
            // Ultra-bright glowing LED dome
            val litDomeBrush = Brush.radialGradient(
              colors = listOf(
                Color.White,
                Color.White.copy(alpha = 0.95f),
                ledColor,
                ledColor.copy(red = (ledColor.red * 0.75f).coerceIn(0f, 1f))
              ),
              center = center - Offset(scaledRadius * 0.15f, scaledRadius * 0.15f),
              radius = scaledRadius * ledBreath
            )
            drawCircle(brush = litDomeBrush, radius = scaledRadius, center = center)

            // Inner glowing core
            drawCircle(
              color = Color.White.copy(alpha = 0.9f),
              radius = scaledRadius * 0.42f,
              center = center
            )
          } else {
            // High-saturation vivid 3D dome
            val vividDomeBrush = Brush.radialGradient(
              colors = listOf(
                Color.White.copy(alpha = 0.85f),
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
          }
        } else {
          // --- CONCAVE PRESSED STATE (Deep inverted dimple) ---
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
            val darkVivid = vividColor.copy(
              red = (vividColor.red * 0.55f).coerceIn(0f, 1f),
              green = (vividColor.green * 0.55f).coerceIn(0f, 1f),
              blue = (vividColor.blue * 0.55f).coerceIn(0f, 1f)
            )
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

            // Deep socket occlusion shadow
            val rimShadowBrush = Brush.radialGradient(
              colors = listOf(Color.Transparent, Color(0x45000000)),
              center = center,
              radius = scaledRadius
            )
            drawCircle(brush = rimShadowBrush, radius = scaledRadius, center = center)

            // Center depression ring
            drawCircle(
              color = Color(0x25000000),
              radius = scaledRadius * 0.45f,
              center = center
            )
          }
        }

        // 3. Embossed Cute Carrot Icon (🥕)
        if (bubble.hasCarrotIcon) {
          val iconColor = if (isLit) {
            Color.White
          } else if (isDown) {
            Color.White.copy(alpha = 0.5f)
          } else {
            Color.White.copy(alpha = 0.85f)
          }

          val cSize = scaledRadius * 0.46f
          val carrotCenter = center + Offset(0f, cSize * 0.08f)

          // Carrot leaves
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

          // Carrot body
          val carrotPath = Path().apply {
            moveTo(carrotCenter.x - cSize * 0.34f, carrotCenter.y - cSize * 0.30f)
            quadraticBezierTo(
              carrotCenter.x, carrotCenter.y - cSize * 0.40f,
              carrotCenter.x + cSize * 0.34f, carrotCenter.y - cSize * 0.30f
            )
            quadraticBezierTo(
              carrotCenter.x + cSize * 0.26f, carrotCenter.y + cSize * 0.2f,
              carrotCenter.x, carrotCenter.y + cSize * 0.55f
            )
            quadraticBezierTo(
              carrotCenter.x - cSize * 0.26f, carrotCenter.y + cSize * 0.2f,
              carrotCenter.x - cSize * 0.34f, carrotCenter.y - cSize * 0.30f
            )
            close()
          }

          drawPath(
            path = carrotPath,
            color = iconColor,
            style = Stroke(width = 2.4f, cap = StrokeCap.Round)
          )

          // Ridges
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
        if (isWaveEnabled && waveProgress.value > 0f && waveProgress.value < 1f) {
          val rippleR = scaledRadius * waveProgress.value
          val rippleAlpha = (1f - waveProgress.value) * 0.85f
          drawCircle(
            color = Color.White.copy(alpha = rippleAlpha),
            radius = rippleR,
            center = center,
            style = Stroke(width = 5f * (1f - waveProgress.value * 0.4f))
          )
        }
      }

      // 5. LED Bloom Glow (Radiates outside button when lit)
      if (isLit) {
        val outerBloomBrush = Brush.radialGradient(
          colors = listOf(
            ledColor.copy(alpha = 0.65f * ledBreath),
            ledColor.copy(alpha = 0.25f * ledBreath),
            Color.Transparent
          ),
          center = center,
          radius = radius * 1.45f
        )
        drawCircle(brush = outerBloomBrush, radius = radius * 1.35f, center = center)
      }
    }

    // Custom SVG Sparkle Animation Overlay (사용자분이 제작해주신 투명 센터 반짝이 SVG 효과)
    val sp = sparkleAnim.value
    if (sp > 0f && sp < 1f) {
      val sparkleAlpha = (1f - sp) * 0.95f
      val sparkleScale = 0.85f + (sp * 0.65f)
      Image(
        painter = painterResource(id = R.drawable.pushpop_sparkle),
        contentDescription = null,
        modifier = Modifier
          .size(sizeDp * 2.4f)
          .graphicsLayer(
            scaleX = sparkleScale,
            scaleY = sparkleScale,
            alpha = sparkleAlpha
          ),
        contentScale = ContentScale.Fit
      )
    }
  }
}
