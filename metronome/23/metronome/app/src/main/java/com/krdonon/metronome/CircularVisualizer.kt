package com.krdonon.metronome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.max
import kotlin.math.min

@Composable
fun CircularVisualizer(
    beatsPerMeasure: Int,
    currentBeat: Int,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer { clip = true }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val minDim = min(size.width, size.height)
        if (minDim <= 0f) return@Canvas

        // 캔버스 크기에 맞춘 유동적 비율 스케일링
        val scale = (minDim / 300f).coerceIn(0.5f, 2.5f)

        // ---- Safe geometry (화면 크기에 비례하여 안전하게 크기 조절) ----
        val maxActiveWidth = 22f * scale
        val maxInactiveWidth = 14f * scale
        val maxWidth = max(maxActiveWidth, maxInactiveWidth)

        val activeLength = maxActiveWidth * 2.5f
        val inactiveLength = maxInactiveWidth * 2.5f
        val maxLength = max(activeLength, inactiveLength)

        val safety = (maxWidth / 2f) + maxLength + (8f * scale)

        val rawRadius = minDim / 2f
        val radius = max(0f, rawRadius - safety)
        val innerRadius = radius * 0.74f

        drawCircle(
            color = Color(0xFF2A2A2A),
            radius = radius,
            center = center,
            style = Stroke(width = max(1.5f, 2f * scale))
        )

        drawCircle(
            color = Color(0xFF1A1A1A),
            radius = innerRadius,
            center = center
        )

        val anglePerBeat = 360f / max(1, beatsPerMeasure)

        val displayBeat = if (isPlaying) {
            (currentBeat - 1 + beatsPerMeasure) % beatsPerMeasure
        } else {
            currentBeat
        }

        for (i in 0 until beatsPerMeasure) {
            val angle = -90f + i * anglePerBeat
            val isCurrent = isPlaying && i == displayBeat

            val segmentColor = when {
                isCurrent && i == 0 -> Color(0xFFFF5555)
                isCurrent -> Color(0xFF55FFFF)
                i == 0 -> Color(0xFF666666)
                else -> Color(0xFF444444)
            }

            val segmentWidth = if (isCurrent) maxActiveWidth else maxInactiveWidth
            val segmentLength = if (isCurrent) activeLength else inactiveLength

            rotate(angle, pivot = center) {
                val startY = center.y - radius
                val endY = startY + segmentLength

                drawLine(
                    color = segmentColor,
                    start = Offset(center.x, startY),
                    end = Offset(center.x, endY),
                    strokeWidth = segmentWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        if (isPlaying) {
            val barWidth = 11f * scale
            val barHeight = 46f * scale
            val barSpacing = 15f * scale

            drawLine(
                color = Color(0xFF55FFFF),
                start = Offset(center.x - barSpacing / 2 - barWidth / 2, center.y - barHeight / 2),
                end = Offset(center.x - barSpacing / 2 - barWidth / 2, center.y + barHeight / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )

            drawLine(
                color = Color(0xFF55FFFF),
                start = Offset(center.x + barSpacing / 2 + barWidth / 2, center.y - barHeight / 2),
                end = Offset(center.x + barSpacing / 2 + barWidth / 2, center.y + barHeight / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        } else {
            val triangleSize = 36f * scale
            val triangleOffset = 4f * scale

            val p1 = Offset(center.x - triangleSize / 3 + triangleOffset, center.y - triangleSize / 2)
            val p2 = Offset(center.x - triangleSize / 3 + triangleOffset, center.y + triangleSize / 2)
            val p3 = Offset(center.x + triangleSize * 2 / 3 + triangleOffset, center.y)

            val strokeW = max(3f, 5f * scale)
            drawLine(color = Color(0xFF888888), start = p1, end = p2, strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(color = Color(0xFF888888), start = p2, end = p3, strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(color = Color(0xFF888888), start = p3, end = p1, strokeWidth = strokeW, cap = StrokeCap.Round)
        }
    }
}
