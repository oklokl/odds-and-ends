package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.example.model.CelestialStar
import com.example.model.TouchMode
import com.example.physics.ThreeBodySimulation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class BackgroundStar(
    val xRatio: Float,
    val yRatio: Float,
    val baseAlpha: Float,
    val size: Float,
    val phase: Float
)

@Composable
fun ThreeBodyCanvasView(
    simulation: ThreeBodySimulation,
    isPaused: Boolean,
    showFieldLines: Boolean,
    modifier: Modifier = Modifier,
    onTapTriggered: () -> Unit = {}
) {
    // Frame ticker state to trigger redraws
    var frameTick by remember { mutableIntStateOf(0) }
    var lastNanoTime by remember { mutableStateOf(0L) }
    var animTime by remember { mutableFloatStateOf(0f) }

    // Pre-computed starry background
    val backgroundStars = remember {
        List(95) {
            BackgroundStar(
                xRatio = Random.nextFloat(),
                yRatio = Random.nextFloat(),
                baseAlpha = 0.25f + Random.nextFloat() * 0.65f,
                size = 1f + Random.nextFloat() * 2.2f,
                phase = Random.nextFloat() * 2f * PI.toFloat()
            )
        }
    }

    // High performance continuous render loop
    LaunchedEffect(isPaused) {
        lastNanoTime = 0L
        while (true) {
            withFrameNanos { nowNano ->
                if (lastNanoTime == 0L) {
                    lastNanoTime = nowNano
                } else {
                    val dtSec = (nowNano - lastNanoTime) / 1_000_000_000f
                    lastNanoTime = nowNano
                    if (!isPaused) {
                        simulation.update(dtSec)
                        animTime += dtSec
                    }
                    frameTick++
                }
            }
        }
    }

    // Selected star for dragging/slinging
    var draggedStar by remember { mutableStateOf<CelestialStar?>(null) }
    var dragPrevPos by remember { mutableStateOf(Offset.Zero) }
    var dragVelocity by remember { mutableStateOf(Offset.Zero) }

    val currentTapCallback by rememberUpdatedState(onTapTriggered)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("three_body_canvas")
                .pointerInput(simulation.touchMode) {
                    detectTapGestures(
                        onTap = { offset ->
                            simulation.applyTouchImpulse(offset.x, offset.y)
                            currentTapCallback()
                        }
                    )
                }
                .pointerInput(simulation.touchMode) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (simulation.touchMode == TouchMode.SLING) {
                                draggedStar = simulation.findStarAt(offset.x, offset.y, 110f)
                                dragPrevPos = offset
                                dragVelocity = Offset.Zero
                            } else if (simulation.touchMode == TouchMode.WELL) {
                                simulation.gravityWellActive = true
                                simulation.gravityWellX = offset.x
                                simulation.gravityWellY = offset.y
                            } else {
                                // Default impulse on drag points
                                simulation.applyTouchImpulse(offset.x, offset.y, 180f)
                                currentTapCallback()
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val cur = change.position
                            if (simulation.touchMode == TouchMode.SLING && draggedStar != null) {
                                val s = draggedStar!!
                                s.x = cur.x
                                s.y = cur.y
                                dragVelocity = Offset(dragAmount.x * 40f, dragAmount.y * 40f)
                            } else if (simulation.touchMode == TouchMode.WELL) {
                                simulation.gravityWellActive = true
                                simulation.gravityWellX = cur.x
                                simulation.gravityWellY = cur.y
                            } else {
                                // Dragging in impulse mode generates a stream of cosmic wake
                                if (Random.nextFloat() < 0.35f) {
                                    simulation.applyTouchImpulse(cur.x, cur.y, 140f)
                                }
                            }
                        },
                        onDragEnd = {
                            if (simulation.touchMode == TouchMode.SLING && draggedStar != null) {
                                simulation.flingStar(draggedStar!!, dragVelocity.x, dragVelocity.y)
                                draggedStar = null
                            }
                            if (simulation.touchMode == TouchMode.WELL) {
                                simulation.gravityWellActive = false
                            }
                        },
                        onDragCancel = {
                            draggedStar = null
                            simulation.gravityWellActive = false
                        }
                    )
                }
        ) {
            // Read frameTick to trigger recomposition
            val _tick = frameTick

            // Update simulation dimensions
            if (size.width > 0f && size.height > 0f) {
                simulation.setBounds(size.width, size.height)
            }

            // 1. Draw Deep Cosmos Background
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF13172E), // Subtle cosmic deep indigo center
                        Color(0xFF090B16), // Outer dark cosmos
                        Color(0xFF04050A)  // Deep obsidian void
                    ),
                    center = center,
                    radius = max(size.width, size.height) * 0.85f
                )
            )

            // 2. Draw Twinkling Background Starfield
            for (s in backgroundStars) {
                val twinkle = (sin(animTime * 2.2f + s.phase) + 1f) * 0.5f
                val alpha = min(1f, max(0.1f, s.baseAlpha * (0.6f + twinkle * 0.4f)))
                drawCircle(
                    color = Color.White.copy(alpha = alpha),
                    radius = s.size,
                    center = Offset(s.xRatio * size.width, s.yRatio * size.height)
                )
            }

            // 3. Draw Gravitational Flux Field Lines between stars
            if (showFieldLines && simulation.stars.size >= 3) {
                drawGravitationalFieldLines(simulation.stars, animTime)
            }

            // 4. Draw Gravity Well Attractor if active
            if (simulation.gravityWellActive) {
                drawGravityWell(
                    simulation.gravityWellX,
                    simulation.gravityWellY,
                    animTime
                )
            }

            // 5. Draw Orbital Trails (잔상 효과)
            for (star in simulation.stars) {
                drawStarTrail(star)
            }

            // 6. Draw Shockwave Ripples
            for (shock in simulation.shockwaves) {
                drawCircle(
                    color = shock.color.copy(alpha = shock.alpha * 0.75f),
                    radius = shock.currentRadius,
                    center = Offset(shock.x, shock.y),
                    style = Stroke(width = max(1.5f, 5f * shock.alpha))
                )
            }

            // 7. Draw Cosmic Particles
            for (p in simulation.particles) {
                val pAlpha = min(1f, max(0f, p.life))
                drawCircle(
                    color = p.color.copy(alpha = pAlpha),
                    radius = p.size * (0.5f + pAlpha * 0.5f),
                    center = Offset(p.x, p.y)
                )
            }

            // 8. Draw Celestial Bodies (The Three Stars)
            for (star in simulation.stars) {
                drawCelestialStar(star, animTime)
            }
        }
    }
}

/**
 * Draws the connecting gravitational tension lines and barycenter between the three stars
 */
private fun DrawScope.drawGravitationalFieldLines(stars: List<CelestialStar>, time: Float) {
    val s0 = Offset(stars[0].x, stars[0].y)
    val s1 = Offset(stars[1].x, stars[1].y)
    val s2 = Offset(stars[2].x, stars[2].y)

    // Pulsing alpha
    val pulseAlpha = (sin(time * 3f) + 1f) * 0.5f * 0.15f + 0.12f

    // Draw triangle flux lines
    val pairs = listOf(
        Triple(s0, s1, stars[0].color),
        Triple(s1, s2, stars[1].color),
        Triple(s2, s0, stars[2].color)
    )

    for (p in pairs) {
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    p.third.copy(alpha = pulseAlpha),
                    Color.White.copy(alpha = pulseAlpha * 0.6f),
                    p.third.copy(alpha = pulseAlpha)
                ),
                start = p.first,
                end = p.second
            ),
            start = p.first,
            end = p.second,
            strokeWidth = 1.8f
        )
    }

    // Barycenter marker (center of mass)
    val mTot = stars[0].mass + stars[1].mass + stars[2].mass
    val baryX = (stars[0].x * stars[0].mass + stars[1].x * stars[1].mass + stars[2].x * stars[2].mass) / mTot
    val baryY = (stars[0].y * stars[0].mass + stars[1].y * stars[1].mass + stars[2].y * stars[2].mass) / mTot
    val bary = Offset(baryX, baryY)

    drawCircle(
        color = Color(0x33FFFFFF),
        radius = 5f,
        center = bary
    )
    drawCircle(
        color = Color(0x66FFFFFF),
        radius = 8f + (sin(time * 4f) + 1f) * 3f,
        center = bary,
        style = Stroke(width = 1f)
    )
}

/**
 * Draw temporary Black Hole / Gravity Well vortex
 */
private fun DrawScope.drawGravityWell(x: Float, y: Float, time: Float) {
    val wellCenter = Offset(x, y)
    val radius = 45f + (sin(time * 6f) + 1f) * 8f

    // Event horizon outer aura
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x99A855F7), // Violet accretion
                Color(0x446366F1),
                Color.Transparent
            ),
            center = wellCenter,
            radius = radius * 2.2f
        ),
        radius = radius * 2.2f,
        center = wellCenter
    )

    // Event horizon core
    drawCircle(
        color = Color(0xFF000000),
        radius = radius * 0.5f,
        center = wellCenter
    )
    drawCircle(
        color = Color(0xFFC084FC),
        radius = radius * 0.5f + 2f,
        center = wellCenter,
        style = Stroke(width = 2.5f)
    )

    // Spiraling accretion filaments
    val spiralRays = 6
    for (i in 0 until spiralRays) {
        val angle = (time * 4.5f + i * (2f * PI.toFloat() / spiralRays))
        val endOffset = Offset(
            x + cos(angle) * (radius * 1.5f),
            y + sin(angle) * (radius * 1.5f)
        )
        drawLine(
            color = Color(0x66D8B4FE),
            start = wellCenter,
            end = endOffset,
            strokeWidth = 1.5f
        )
    }
}

/**
 * Draws the smooth, fading, comet-like orbital trail of a celestial body
 */
private fun DrawScope.drawStarTrail(star: CelestialStar) {
    val trail = star.trail
    if (trail.size < 2) return

    val totalPoints = trail.size.toFloat()

    // Draw trail segments with progressive width and alpha
    for (i in 0 until trail.size - 1) {
        val p1 = trail[i]
        val p2 = trail[i + 1]

        val progress = i / totalPoints // 0.0 at oldest, ~1.0 at newest
        // Nonlinear alpha curve: oldest is very transparent, head is luminous
        val alpha = progress * progress * 0.85f
        val strokeWidth = max(1f, progress * (star.baseRadius * 0.65f))

        drawLine(
            color = star.color.copy(alpha = alpha),
            start = Offset(p1.x, p1.y),
            end = Offset(p2.x, p2.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Draws an individual star with its corona, proximity flares, core, and diffraction spikes
 */
private fun DrawScope.drawCelestialStar(star: CelestialStar, time: Float) {
    val starPos = Offset(star.x, star.y)

    // Dynamic proximity pulsation and flare
    val proximityBoost = star.proximityIntensity * 0.65f // flares up to +65%
    val pulse = (sin(time * 5f + star.id * 2.1f) + 1f) * 0.5f * 0.15f
    val effectiveScale = (star.flareScale + proximityBoost + pulse)
    val coreRadius = star.baseRadius * effectiveScale

    // 1. Vast outer coronal aura (Atmospheric / Lensing glow)
    val outerGlowRadius = coreRadius * 3.8f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                star.color.copy(alpha = min(0.6f, 0.28f + proximityBoost * 0.35f)),
                star.glowColor.copy(alpha = min(0.35f, 0.15f + proximityBoost * 0.2f)),
                Color.Transparent
            ),
            center = starPos,
            radius = outerGlowRadius
        ),
        radius = outerGlowRadius,
        center = starPos
    )

    // 2. Middle high-intensity stellar plasma corona
    val midCoronaRadius = coreRadius * 1.9f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.85f),
                star.color.copy(alpha = 0.75f),
                Color.Transparent
            ),
            center = starPos,
            radius = midCoronaRadius
        ),
        radius = midCoronaRadius,
        center = starPos
    )

    // 3. Stellar diffraction spikes (4 sparkling cross rays)
    val spikeLen = coreRadius * (2.8f + proximityBoost * 1.5f)
    val spikeRot = time * 0.6f + star.id * 1.05f
    val spikeColor = star.color.copy(alpha = min(0.7f, 0.35f + proximityBoost * 0.35f))

    for (angleDeg in listOf(0f, 90f)) {
        val rad = (angleDeg * PI / 180f + spikeRot).toFloat()
        val cosR = cos(rad) * spikeLen
        val sinR = sin(rad) * spikeLen
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, spikeColor, Color.White, spikeColor, Color.Transparent),
                start = Offset(star.x - cosR, star.y - sinR),
                end = Offset(star.x + cosR, star.y + sinR)
            ),
            start = Offset(star.x - cosR, star.y - sinR),
            end = Offset(star.x + cosR, star.y + sinR),
            strokeWidth = max(1f, 1.8f * (1f + proximityBoost))
        )
    }

    // 4. Solid stellar body
    drawCircle(
        color = star.color,
        radius = coreRadius,
        center = starPos
    )

    // 5. Supernova / Ultra-hot white core
    drawCircle(
        color = Color.White,
        radius = coreRadius * 0.48f,
        center = starPos
    )

    // 6. Proximity lightning / electric ring if in extreme proximity
    if (star.proximityIntensity > 0.4f) {
        val ringAlpha = min(0.9f, (star.proximityIntensity - 0.4f) * 1.6f)
        drawCircle(
            color = Color.White.copy(alpha = ringAlpha),
            radius = coreRadius * 1.4f,
            center = starPos,
            style = Stroke(width = 2f)
        )
    }
}
