package com.krdonon.metronome

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 사이버펑크 SF 오디오 인터페이스 스타일의 고급 메트로놈 다이얼 비주얼라이저.
 * - 가변 박자 수(beatsPerMeasure)에 맞춰 외곽 숫자 라벨(1..N)과 캡슐 인디케이터 정밀 정렬
 * - AudioTrack 하드웨어 타임스탬프 기반 60/120 FPS VSync 동기화 (오디오-애니메이션 무드리프트)
 * - Compose 재구성 없이 Canvas Draw 단계에서만 상태를 읽어 부드러운 어택/잔광 및 스윕 궤적 표현
 */
@Composable
fun CircularVisualizer(
    beatsPerMeasure: Int,
    isPlaying: Boolean,
    beatStateProvider: () -> BeatFrameState?,
    modifier: Modifier = Modifier
) {
    val currentProvider by rememberUpdatedState(beatStateProvider)
    val frameState = remember(isPlaying, beatsPerMeasure) {
        mutableStateOf(
            beatStateProvider() ?: BeatFrameState(
                isPlaying = isPlaying,
                beatsPerMeasure = beatsPerMeasure
            )
        )
    }

    LaunchedEffect(isPlaying, beatsPerMeasure) {
        if (!isPlaying) {
            frameState.value = BeatFrameState(
                isPlaying = false,
                beatsPerMeasure = beatsPerMeasure,
                currentBeat = 0,
                beatProgress = 0f,
                measureProgress = 0f
            )
            return@LaunchedEffect
        }

        while (isActive) {
            withFrameNanos {
                val snapshot = currentProvider()
                if (snapshot != null) {
                    frameState.value = snapshot
                }
            }
        }
    }

    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer { clip = true }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val minDim = min(size.width, size.height)
        if (minDim <= 0f) return@Canvas

        // Draw 단계에서만 frameState를 읽어 재생 중 불필요한 Recomposition을 완전히 제거
        val visual = frameState.value
        val beats = (if (isPlaying && visual.isPlaying) visual.beatsPerMeasure else beatsPerMeasure)
            .coerceIn(1, 16)
        val activePlaying = isPlaying && visual.isPlaying && !visual.isPreStart
        val displayBeat = visual.currentBeat.coerceIn(0, beats - 1)
        val beatProgress = visual.beatProgress.coerceIn(0f, 1f)
        val measureProgress = visual.measureProgress.coerceIn(0f, 1f)

        // 타격 순간(beatProgress == 0) 최대 밝기 후 지수 감쇠 잔광
        val beatImpact = if (activePlaying) (1f - beatProgress).pow(2.2f) else 0f

        // 360dp 기준 스케일 계수
        val scale = (minDim / 360f).coerceIn(0.5f, 2.5f)
        val rawRadius = minDim / 2f
        val anglePerBeat = 360f / beats

        // 박자 수에 따른 캡슐 너비, 높이, 폰트 크기 계산
        val capsuleWidth = when {
            beats > 12 -> 9f * scale
            beats > 8 -> 12f * scale
            beats > 4 -> 14f * scale
            else -> 17f * scale
        }
        val capsuleHeight = when {
            beats > 12 -> 22f * scale
            beats > 8 -> 26f * scale
            beats > 4 -> 30f * scale
            else -> 36f * scale
        }
        val numTextSize = when {
            beats > 12 -> 11f * scale
            beats > 8 -> 13f * scale
            beats > 4 -> 15f * scale
            else -> 18f * scale
        }

        // 숫자와 캡슐이 겹치지 않도록 계층 분리
        val numberRadius = rawRadius - (14f * scale)
        val capsuleOuterTip = numberRadius - (14f * scale)
        val capsuleCenterRadius = capsuleOuterTip - (capsuleHeight / 2f)
        val outerTrackRadius = capsuleCenterRadius

        val tickOuterRadius = capsuleCenterRadius - (capsuleHeight / 2f) - (8f * scale)
        val tickInnerRadius = tickOuterRadius - (9f * scale)
        val coreOuterRadius = tickInnerRadius - (14f * scale)
        val coreDomeRadius = coreOuterRadius - (3f * scale)

        // ----------------------------------------------------
        // 1. 외곽 베이스 다이얼 트랙 & 은은한 배경 링
        // ----------------------------------------------------
        drawCircle(
            color = Color(0xFF141C22),
            radius = outerTrackRadius + (capsuleHeight * 0.4f),
            center = center,
            style = Stroke(width = 1.5f * scale)
        )

        drawCircle(
            color = Color(0xFF0F151A),
            radius = outerTrackRadius,
            center = center,
            style = Stroke(width = 2f * scale)
        )

        // ----------------------------------------------------
        // 2. 네온 블루 원호 궤적 & 실시간 마디 스윕 인디케이터
        // ----------------------------------------------------
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.08f),
            radius = outerTrackRadius,
            center = center,
            style = Stroke(width = 10f * scale)
        )
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.30f),
            radius = outerTrackRadius,
            center = center,
            style = Stroke(width = 2f * scale)
        )

        if (activePlaying) {
            val sweepHeadDeg = -90f + measureProgress * 360f
            val tailSpan = (anglePerBeat * 0.75f).coerceIn(18f, 52f)
            val trackTopLeft = Offset(center.x - outerTrackRadius, center.y - outerTrackRadius)
            val trackSize = Size(outerTrackRadius * 2f, outerTrackRadius * 2f)
            val sweepColor = if (displayBeat == 0) {
                lerp(Color(0xFF00E5FF), Color(0xFFFF3366), 0.65f * beatImpact)
            } else {
                Color(0xFF00E5FF)
            }

            // 부드러운 회전 잔광 호 (다음 박자를 미리 예측할 수 있는 시각 궤적)
            drawArc(
                color = sweepColor.copy(alpha = 0.18f),
                startAngle = sweepHeadDeg - tailSpan,
                sweepAngle = tailSpan,
                useCenter = false,
                topLeft = trackTopLeft,
                size = trackSize,
                style = Stroke(width = 8f * scale, cap = StrokeCap.Round)
            )
            drawArc(
                color = sweepColor.copy(alpha = 0.65f),
                startAngle = sweepHeadDeg - (tailSpan * 0.55f),
                sweepAngle = tailSpan * 0.55f,
                useCenter = false,
                topLeft = trackTopLeft,
                size = trackSize,
                style = Stroke(width = 2.8f * scale, cap = StrokeCap.Round)
            )

            // 스윕 헤드 광점
            val headRad = Math.toRadians(sweepHeadDeg.toDouble())
            val headPos = Offset(
                center.x + outerTrackRadius * cos(headRad).toFloat(),
                center.y + outerTrackRadius * sin(headRad).toFloat()
            )
            drawCircle(
                color = sweepColor.copy(alpha = 0.45f),
                radius = 5.5f * scale,
                center = headPos
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = 2.2f * scale,
                center = headPos
            )
        }

        // ----------------------------------------------------
        // 3. 정밀 계측기 눈금 (Tick Marks - 오디오 게이지)
        // ----------------------------------------------------
        val totalTicks = 60
        for (t in 0 until totalTicks) {
            val tickAngleDeg = t * (360f / totalTicks)
            val isMajor = (t % 5 == 0)
            val tInner = if (isMajor) tickInnerRadius - (3f * scale) else tickInnerRadius
            val tColor = if (isMajor) Color(0xFF4A6B82) else Color(0xFF20323E)
            val tWidth = if (isMajor) 2f * scale else 1.2f * scale

            val rad = Math.toRadians(tickAngleDeg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val pStart = Offset(center.x + tickOuterRadius * cosA, center.y + tickOuterRadius * sinA)
            val pEnd = Offset(center.x + tInner * cosA, center.y + tInner * sinA)

            drawLine(
                color = tColor,
                start = pStart,
                end = pEnd,
                strokeWidth = tWidth,
                cap = StrokeCap.Round
            )
        }

        // 눈금 안쪽 원형 테두리
        drawCircle(
            color = Color(0xFF182631),
            radius = tickInnerRadius - (5f * scale),
            center = center,
            style = Stroke(width = 1.5f * scale)
        )

        // ----------------------------------------------------
        // 4. 박자 인디케이터 (Capsule Pills) & 외곽 박자 번호(1..N)
        // ----------------------------------------------------
        textPaint.textSize = numTextSize
        val inactiveNumberColor = Color(0xFF90A4AE)

        for (i in 0 until beats) {
            // 숫자는 cos/sin 기준 -90°가 12시 방향
            val numberAngleDeg = -90f + i * anglePerBeat
            // 캡슐의 기본 위치(center.y - capsuleCenterRadius)가 이미 12시(-90°)이므로
            // 회전 각도는 0°부터 i * anglePerBeat만큼만 회전시켜 번호와 정확히 1:1 정렬
            val pillRotationDeg = i * anglePerBeat
            val isCurrent = activePlaying && (i == displayBeat)

            // (A) 외곽 박자 번호 (1, 2, 3, ... N)
            val radAngle = Math.toRadians(numberAngleDeg.toDouble())
            val numX = center.x + numberRadius * cos(radAngle).toFloat()
            val numY = center.y + numberRadius * sin(radAngle).toFloat()

            textPaint.color = if (isCurrent) {
                val targetColor = if (i == 0) Color(0xFFFF3366) else Color(0xFF80D8FF)
                lerp(inactiveNumberColor, targetColor, 0.45f + 0.55f * beatImpact).toArgb()
            } else {
                inactiveNumberColor.toArgb()
            }

            val fontMetrics = textPaint.fontMetrics
            val baselineOffset = (fontMetrics.descent + fontMetrics.ascent) / 2f
            drawContext.canvas.nativeCanvas.drawText(
                (i + 1).toString(),
                numX,
                numY - baselineOffset,
                textPaint
            )

            // (B) 캡슐(Pill) 인디케이터
            rotate(pillRotationDeg, pivot = center) {
                val pulseExpand = if (isCurrent) 1.5f * scale * beatImpact else 0f
                val w = capsuleWidth + pulseExpand
                val h = capsuleHeight + pulseExpand
                val pillLeft = center.x - (w / 2f)
                val pillTop = center.y - capsuleCenterRadius - (h / 2f)
                val pillCorner = CornerRadius(w / 2f, w / 2f)

                if (isCurrent) {
                    val isStrong = (i == 0)
                    val glowColor = if (isStrong) Color(0xFFFF3366) else Color(0xFF00E5FF)
                    val coreColor = if (isStrong) Color(0xFFFF80AB) else Color(0xFF80D8FF)

                    // 1) 타격 순간 강하게 번지고 부드럽게 사그라드는 네온 글로우
                    val outerGlowAlpha = 0.14f + 0.28f * beatImpact
                    val innerGlowAlpha = 0.28f + 0.45f * beatImpact
                    drawRoundRect(
                        color = glowColor.copy(alpha = outerGlowAlpha),
                        topLeft = Offset(pillLeft - 8f * scale, pillTop - 8f * scale),
                        size = Size(w + 16f * scale, h + 16f * scale),
                        cornerRadius = CornerRadius((w + 16f * scale) / 2f)
                    )
                    drawRoundRect(
                        color = glowColor.copy(alpha = innerGlowAlpha),
                        topLeft = Offset(pillLeft - 3f * scale, pillTop - 3f * scale),
                        size = Size(w + 6f * scale, h + 6f * scale),
                        cornerRadius = CornerRadius((w + 6f * scale) / 2f)
                    )

                    // 2) 캡슐 본체 (밝은 네온 발광)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(coreColor, glowColor),
                            startY = pillTop,
                            endY = pillTop + h
                        ),
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(w, h),
                        cornerRadius = pillCorner
                    )

                    // 3) 상단 하이라이트 글래스 반사광
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.55f + 0.35f * beatImpact),
                        topLeft = Offset(pillLeft + 1.5f * scale, pillTop + 1.5f * scale),
                        size = Size(w - 3f * scale, h * 0.4f),
                        cornerRadius = CornerRadius((w - 3f * scale) / 2f)
                    )
                } else {
                    // 비활성 상태: 메탈릭 실버-슬레이트 알약
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.4f),
                        topLeft = Offset(pillLeft + 1f * scale, pillTop + 2f * scale),
                        size = Size(w, h),
                        cornerRadius = pillCorner
                    )

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF78909C),
                                Color(0xFF37474F)
                            ),
                            startY = pillTop,
                            endY = pillTop + h
                        ),
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(w, h),
                        cornerRadius = pillCorner
                    )

                    drawRoundRect(
                        color = Color(0xFF263238),
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(w, h),
                        cornerRadius = pillCorner,
                        style = Stroke(width = 1f * scale)
                    )

                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.35f),
                        topLeft = Offset(pillLeft + 1.5f * scale, pillTop + 1.5f * scale),
                        size = Size(w - 3f * scale, h * 0.35f),
                        cornerRadius = CornerRadius((w - 3f * scale) / 2f)
                    )
                }
            }
        }

        // ----------------------------------------------------
        // 5. 중앙 3D 메탈릭 볼록 돔 (Center Dome / Core Button)
        // ----------------------------------------------------
        val domeAccent = if (activePlaying && displayBeat == 0) {
            lerp(Color(0xFF00E5FF), Color(0xFFFF3366), 0.55f * beatImpact)
        } else {
            Color(0xFF00E5FF)
        }

        drawCircle(
            color = domeAccent.copy(alpha = 0.14f + 0.24f * beatImpact),
            radius = coreOuterRadius + (5f + 2f * beatImpact) * scale,
            center = center,
            style = Stroke(width = (7f + 2f * beatImpact) * scale)
        )
        drawCircle(
            color = domeAccent.copy(alpha = 0.65f + 0.35f * beatImpact),
            radius = coreOuterRadius,
            center = center,
            style = Stroke(width = (2.5f + 1.0f * beatImpact) * scale)
        )

        val domeBrush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF2C3E50),
                Color(0xFF162029),
                Color(0xFF0B1015)
            ),
            center = Offset(center.x - coreDomeRadius * 0.25f, center.y - coreDomeRadius * 0.30f),
            radius = coreDomeRadius * 1.35f
        )
        drawCircle(
            brush = domeBrush,
            radius = coreDomeRadius,
            center = center
        )

        drawCircle(
            color = domeAccent.copy(alpha = 0.28f + 0.25f * beatImpact),
            radius = coreDomeRadius - (2f * scale),
            center = center,
            style = Stroke(width = 1.2f * scale)
        )

        // ----------------------------------------------------
        // 6. 중앙 발광 네온 기호 (일시정지 || / 재생 ▶)
        // ----------------------------------------------------
        if (isPlaying) {
            val barW = 7f * scale
            val barH = 30f * scale
            val barGap = 12f * scale

            val b1X = center.x - (barGap / 2f) - (barW / 2f)
            val b2X = center.x + (barGap / 2f) + (barW / 2f)
            val barY1 = center.y - (barH / 2f)
            val barY2 = center.y + (barH / 2f)

            val glowColor = Color(0xFF00E5FF).copy(alpha = 0.30f + 0.32f * beatImpact)
            drawLine(color = glowColor, start = Offset(b1X, barY1), end = Offset(b1X, barY2), strokeWidth = barW + 7f * scale, cap = StrokeCap.Round)
            drawLine(color = glowColor, start = Offset(b2X, barY1), end = Offset(b2X, barY2), strokeWidth = barW + 7f * scale, cap = StrokeCap.Round)

            val coreColor = Color(0xFF00F5FF)
            drawLine(color = coreColor, start = Offset(b1X, barY1), end = Offset(b1X, barY2), strokeWidth = barW, cap = StrokeCap.Round)
            drawLine(color = coreColor, start = Offset(b2X, barY1), end = Offset(b2X, barY2), strokeWidth = barW, cap = StrokeCap.Round)

            drawLine(color = Color.White.copy(alpha = 0.8f), start = Offset(b1X, barY1 + 3f * scale), end = Offset(b1X, barY2 - 3f * scale), strokeWidth = 2f * scale, cap = StrokeCap.Round)
            drawLine(color = Color.White.copy(alpha = 0.8f), start = Offset(b2X, barY1 + 3f * scale), end = Offset(b2X, barY2 - 3f * scale), strokeWidth = 2f * scale, cap = StrokeCap.Round)
        } else {
            val triSize = 26f * scale
            val triOffset = 3f * scale

            val p1 = Offset(center.x - triSize / 2f + triOffset, center.y - triSize / 2f)
            val p2 = Offset(center.x - triSize / 2f + triOffset, center.y + triSize / 2f)
            val p3 = Offset(center.x + triSize / 2f + triOffset, center.y)

            val playPath = Path().apply {
                moveTo(p1.x, p1.y)
                lineTo(p2.x, p2.y)
                lineTo(p3.x, p3.y)
                close()
            }

            drawPath(
                path = playPath,
                color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                style = Stroke(width = 5f * scale, cap = StrokeCap.Round)
            )
            drawPath(
                path = playPath,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF80D8FF), Color(0xFF00E5FF)),
                    start = p1,
                    end = p3
                )
            )
        }
    }
}

@Preview(name = "Circular Visualizer Strong Beat")
@Composable
private fun CircularVisualizerStrongBeatPreview() {
    Box(
        modifier = Modifier
            .background(Color(0xFF0A0A0A))
            .padding(16.dp)
    ) {
        CircularVisualizer(
            beatsPerMeasure = 4,
            isPlaying = true,
            beatStateProvider = {
                BeatFrameState(
                    isPlaying = true,
                    beatsPerMeasure = 4,
                    currentBeat = 0,
                    beatProgress = 0.08f,
                    measureProgress = 0.02f
                )
            },
            modifier = Modifier.size(300.dp)
        )
    }
}

@Preview(name = "Circular Visualizer Beat 2")
@Composable
private fun CircularVisualizerBeat2Preview() {
    Box(
        modifier = Modifier
            .background(Color(0xFF0A0A0A))
            .padding(16.dp)
    ) {
        CircularVisualizer(
            beatsPerMeasure = 4,
            isPlaying = true,
            beatStateProvider = {
                BeatFrameState(
                    isPlaying = true,
                    beatsPerMeasure = 4,
                    currentBeat = 1,
                    beatProgress = 0.15f,
                    measureProgress = 0.29f
                )
            },
            modifier = Modifier.size(300.dp)
        )
    }
}
