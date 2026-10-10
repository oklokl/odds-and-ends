package com.krdondon.quickpush.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.krdondon.quickpush.R
import com.krdondon.quickpush.model.AnimationIntensity
import com.krdondon.quickpush.model.BubbleItem
import com.krdondon.quickpush.model.FloatingScore
import com.krdondon.quickpush.model.GridDimension
import com.krdondon.quickpush.model.Particle
import com.krdondon.quickpush.ui.theme.BoardBackground
import com.krdondon.quickpush.ui.theme.BoardBorder
import com.krdondon.quickpush.ui.theme.BoardShadow

@Composable
fun PushPopBoard(
  bubbles: List<BubbleItem>,
  gridDimension: GridDimension,
  isGlowEnabled: Boolean,
  isWaveEnabled: Boolean,
  isBorderSparkleEnabled: Boolean,
  animationIntensity: AnimationIntensity,
  floatingScores: List<FloatingScore>,
  particles: List<Particle>,
  onBubbleClick: (id: Int, x: Float, y: Float) -> Unit,
  onGridAvailableSizeChanged: (widthDp: Float, heightDp: Float) -> Unit,
  modifier: Modifier = Modifier
) {
  BoxWithConstraints(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    val availableW = maxWidth.value
    val availableH = maxHeight.value

    // Notify ViewModel of available layout space
    onGridAvailableSizeChanged(availableW, availableH)

    val rows = gridDimension.rows
    val cols = gridDimension.cols
    val bubbleSize = gridDimension.bubbleSizeDp.dp
    val spacing = gridDimension.spacingDp.dp

    // Silicone Toy Board Outer Shell
    Card(
      modifier = Modifier
        .wrapContentSize()
        .padding(8.dp)
        .shadow(
          elevation = 12.dp,
          shape = RoundedCornerShape(36.dp),
          ambientColor = BoardShadow,
          spotColor = BoardShadow
        ),
      shape = RoundedCornerShape(36.dp),
      colors = CardDefaults.cardColors(containerColor = BoardBackground),
      elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
      Box(
        modifier = Modifier
          .wrapContentSize()
          .border(4.dp, BoardBorder, RoundedCornerShape(36.dp))
          .background(
            brush = Brush.verticalGradient(
              colors = listOf(
                Color(0xFFFFF7FA),
                BoardBackground,
                Color(0xFFFFEEF3)
              )
            )
          )
          .padding(horizontal = 20.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          // Cute Silicone Mascot Badge at Board Top
          Box(
            modifier = Modifier
              .size(46.dp)
              .shadow(4.dp, CircleShape)
              .clip(CircleShape)
              .background(Color.White)
              .border(2.dp, BoardBorder, CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Image(
              painter = painterResource(id = R.drawable.toy_mascot),
              contentDescription = "토이 마스코트",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          // PushPop Bubble Grid
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
                    PushPopBubble(
                      bubble = bubble,
                      sizeDp = bubbleSize,
                      isGlowEnabled = isGlowEnabled,
                      isWaveEnabled = isWaveEnabled,
                      isBorderSparkleEnabled = isBorderSparkleEnabled,
                      animationIntensity = animationIntensity,
                      onBubbleClick = onBubbleClick
                    )
                  } else {
                    Spacer(modifier = Modifier.size(bubbleSize))
                  }
                }
              }
            }
          }
        }

        // Overlay for Floating Scores & Sparkle Particles
        FloatingTextAndParticleOverlay(
          floatingScores = floatingScores,
          particles = particles
        )
      }
    }
  }
}
