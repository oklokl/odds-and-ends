package com.krdonon.metronome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

@Composable
fun CircularVisualizer(
    beatsPerMeasure: Int,
    currentBeat: Int,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth(0.8f)
            .aspectRatio(1f)
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = size.minDimension / 2f * 0.85f
        val innerRadius = radius * 0.75f

        // 바깥 원
        drawCircle(
            color = Color(0xFF2A2A2A),
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 2f)
        )

        // 안쪽 원
        drawCircle(
            color = Color(0xFF1A1A1A),
            radius = innerRadius,
            center = Offset(centerX, centerY)
        )

        // 한 박자당 회전 각도
        val anglePerBeat = 360f / beatsPerMeasure

        // 실제 소리 타이밍(한 박자 빠르던 문제) 보정
        val displayBeat = if (isPlaying) {
            (currentBeat - 1 + beatsPerMeasure) % beatsPerMeasure
        } else {
            currentBeat
        }

        // 세그먼트 그리기
        for (i in 0 until beatsPerMeasure) {
            val angle = -90f + i * anglePerBeat
            val isCurrent = isPlaying && i == displayBeat

            val segmentColor = when {
                isCurrent && i == 0 -> Color(0xFFFF5555)  // 강박 활성
                isCurrent -> Color(0xFF55FFFF)           // 약박 활성
                i == 0 -> Color(0xFF666666)              // 강박 비활성
                else -> Color(0xFF444444)                // 약박 비활성
            }

            val segmentWidth = if (isCurrent) 24f else 16f
            val segmentRadius = if (isCurrent) radius + 10f else radius

            rotate(angle, pivot = Offset(centerX, centerY)) {
                val startY = centerY - segmentRadius
                val endY = startY + segmentWidth * 3

                drawLine(
                    color = segmentColor,
                    start = Offset(centerX, startY),
                    end = Offset(centerX, endY),
                    strokeWidth = segmentWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        // 중앙 아이콘
        if (isPlaying) {
            // 일시정지 (||)
            val barWidth = 12f
            val barHeight = 50f
            val barSpacing = 16f

            drawLine(
                color = Color(0xFF55FFFF),
                start = Offset(centerX - barSpacing / 2 - barWidth / 2, centerY - barHeight / 2),
                end = Offset(centerX - barSpacing / 2 - barWidth / 2, centerY + barHeight / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )

            drawLine(
                color = Color(0xFF55FFFF),
                start = Offset(centerX + barSpacing / 2 + barWidth / 2, centerY - barHeight / 2),
                end = Offset(centerX + barSpacing / 2 + barWidth / 2, centerY + barHeight / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        } else {
            // 재생 아이콘 (▶)
            val triangleSize = 40f
            val triangleOffset = 5f

            val p1 = Offset(centerX - triangleSize / 3 + triangleOffset, centerY - triangleSize / 2)
            val p2 = Offset(centerX - triangleSize / 3 + triangleOffset, centerY + triangleSize / 2)
            val p3 = Offset(centerX + triangleSize * 2 / 3 + triangleOffset, centerY)

            drawLine(color = Color(0xFF888888), start = p1, end = p2, strokeWidth = 8f, cap = StrokeCap.Round)
            drawLine(color = Color(0xFF888888), start = p2, end = p3, strokeWidth = 8f, cap = StrokeCap.Round)
            drawLine(color = Color(0xFF888888), start = p3, end = p1, strokeWidth = 8f, cap = StrokeCap.Round)
        }
    }
}
