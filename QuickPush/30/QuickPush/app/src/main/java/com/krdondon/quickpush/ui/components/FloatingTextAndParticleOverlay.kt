package com.krdondon.quickpush.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.krdondon.quickpush.model.FloatingScore
import com.krdondon.quickpush.model.Particle
import kotlinx.coroutines.launch

@Composable
fun FloatingTextAndParticleOverlay(
  floatingScores: List<FloatingScore>,
  particles: List<Particle>,
  modifier: Modifier = Modifier
) {
  Box(modifier = modifier) {
    // Animated floating score badges (keyed by item.id so removals never shift animations)
    floatingScores.forEach { item ->
      key(item.id) {
        FloatingScoreItem(item = item)
      }
    }
  }
}

@Composable
private fun FloatingScoreItem(item: FloatingScore) {
  val offsetY = remember { Animatable(0f) }
  val alpha = remember { Animatable(1f) }
  val scale = remember { Animatable(0.7f) }

  LaunchedEffect(item.id) {
    launch { offsetY.animateTo(-60f, tween(700, easing = LinearEasing)) }
    launch { alpha.animateTo(0f, tween(700, easing = LinearEasing)) }
    launch {
      scale.animateTo(1.2f, tween(150, easing = LinearEasing))
      scale.animateTo(1.0f, tween(150, easing = LinearEasing))
    }
  }

  Text(
    text = item.text,
    color = item.color,
    fontSize = 20.sp,
    fontWeight = FontWeight.Black,
    modifier = Modifier
      .offset { IntOffset(item.x.toInt(), (item.y + offsetY.value).toInt()) }
      .graphicsLayer {
        this.alpha = alpha.value
        this.scaleX = scale.value
        this.scaleY = scale.value
      }
  )
}
