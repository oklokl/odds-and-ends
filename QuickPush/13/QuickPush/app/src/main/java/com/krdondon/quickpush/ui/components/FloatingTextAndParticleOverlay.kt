package com.krdondon.quickpush.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.krdondon.quickpush.model.FloatingScore
import com.krdondon.quickpush.model.Particle

@Composable
fun FloatingTextAndParticleOverlay(
  floatingScores: List<FloatingScore>,
  particles: List<Particle>,
  modifier: Modifier = Modifier
) {
  Box(modifier = modifier) {
    // Particles (알맹이 둥근 점 효과) removed completely so only custom SVG sparkle is shown.

    // Animated floating score badges
    floatingScores.forEach { item ->
      FloatingScoreItem(item = item)
    }
  }
}

@Composable
private fun FloatingScoreItem(item: FloatingScore) {
  val offsetY = remember { Animatable(0f) }
  val alpha = remember { Animatable(1f) }

  LaunchedEffect(item.id) {
    offsetY.animateTo(-50f, tween(700, easing = LinearEasing))
  }
  LaunchedEffect(item.id) {
    alpha.animateTo(0f, tween(700, easing = LinearEasing))
  }

  Text(
    text = item.text,
    color = item.color,
    fontSize = 18.sp,
    fontWeight = FontWeight.Black,
    modifier = Modifier
      .offset { IntOffset(item.x.toInt(), (item.y + offsetY.value).toInt()) }
      .alpha(alpha.value)
  )
}
