package com.krdonon.metronome

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 사이버펑크 SF 오디오 인터페이스 스타일의 고급 메트로놈 다이얼 비주얼라이저.
 * - 가변 박자 수(beatsPerMeasure)에 맞춰 외곽 숫자 라벨(1..N) 자동 정렬 및 겹침 방지
 * - 네온 글로우 캡슐(Pill) 인디케이터
 * - 정밀 계측기 눈금(Tick marks) 및 네온 블루 원호 궤적
 * - 3D 메탈릭 볼록 돔과 발광 일시정지/재생 기호
 */
@Composable
fun CircularVisualizer(
    beatsPerMeasure: Int,
    currentBeat: Int,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
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

        // 360dp 기준 스케일 계수
        val scale = (minDim / 360f).coerceIn(0.5f, 2.5f)

        // 전체 최대 안전 반경
        val rawRadius = minDim / 2f

        val beats = beatsPerMeasure.coerceIn(1, 16)
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

        // [핵심] 숫자와 캡슐(흰 막대)이 절대 겹치지 않도록 계층 분리:
        // 1. 숫자는 최외곽에 위치 (캔버스 가장자리로부터 안전 여백)
        val numberRadius = rawRadius - (14f * scale)

        // 2. 캡슐의 바깥쪽 끝은 숫자 영역과 넉넉한 간격(14f * scale)을 두고 위치
        val capsuleOuterTip = numberRadius - (14f * scale)
        val capsuleCenterRadius = capsuleOuterTip - (capsuleHeight / 2f)
        val outerTrackRadius = capsuleCenterRadius

        // 3. 내부 눈금선 및 코어 돔 반경
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
        // 2. 네온 블루 원호 궤적 (외곽 네온 링 & 글로우)
        // ----------------------------------------------------
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.08f),
            radius = outerTrackRadius,
            center = center,
            style = Stroke(width = 10f * scale)
        )
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.35f),
            radius = outerTrackRadius,
            center = center,
            style = Stroke(width = 2f * scale)
        )

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
        // 현재 연주 중인 박자 (1박 시작 동기화)
        val displayBeat = if (isPlaying) {
            (currentBeat - 1 + beats) % beats
        } else {
            currentBeat
        }

        textPaint.textSize = numTextSize

        for (i in 0 until beats) {
            val angle = -90f + i * anglePerBeat
            val isCurrent = isPlaying && (i == displayBeat)

            // (A) 외곽 박자 번호 (1, 2, 3, ... N)
            // 캡슐 머리 바깥쪽에 절대 겹치지 않도록 안전하게 배치
            val radAngle = Math.toRadians(angle.toDouble())
            val numX = center.x + numberRadius * cos(radAngle).toFloat()
            val numY = center.y + numberRadius * sin(radAngle).toFloat()

            textPaint.color = if (isCurrent) {
                if (i == 0) android.graphics.Color.WHITE else Color(0xFF80D8FF).toArgb()
            } else {
                Color(0xFF90A4AE).toArgb()
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
            rotate(angle, pivot = center) {
                val pillLeft = center.x - (capsuleWidth / 2f)
                val pillTop = center.y - capsuleCenterRadius - (capsuleHeight / 2f)
                val pillCorner = CornerRadius(capsuleWidth / 2f, capsuleWidth / 2f)

                if (isCurrent) {
                    // 활성화 상태: 화려한 네온 발광
                    val isStrong = (i == 0)
                    val glowColor = if (isStrong) Color(0xFF00E5FF) else Color(0xFFFF3366)
                    val coreColor = if (isStrong) Color(0xFFFFFFFF) else Color(0xFFFF80AB)

                    // 1) 외곽 글로우 (여러 겹으로 부드러운 네온 효과)
                    drawRoundRect(
                        color = glowColor.copy(alpha = 0.25f),
                        topLeft = Offset(pillLeft - 8f * scale, pillTop - 8f * scale),
                        size = Size(capsuleWidth + 16f * scale, capsuleHeight + 16f * scale),
                        cornerRadius = CornerRadius((capsuleWidth + 16f * scale) / 2f)
                    )
                    drawRoundRect(
                        color = glowColor.copy(alpha = 0.5f),
                        topLeft = Offset(pillLeft - 3f * scale, pillTop - 3f * scale),
                        size = Size(capsuleWidth + 6f * scale, capsuleHeight + 6f * scale),
                        cornerRadius = CornerRadius((capsuleWidth + 6f * scale) / 2f)
                    )

                    // 2) 캡슐 본체 (밝은 네온 발광)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(coreColor, glowColor),
                            startY = pillTop,
                            endY = pillTop + capsuleHeight
                        ),
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(capsuleWidth, capsuleHeight),
                        cornerRadius = pillCorner
                    )

                    // 3) 상단 하이라이트 글래스 반사광
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.85f),
                        topLeft = Offset(pillLeft + 1.5f * scale, pillTop + 1.5f * scale),
                        size = Size(capsuleWidth - 3f * scale, capsuleHeight * 0.4f),
                        cornerRadius = CornerRadius((capsuleWidth - 3f * scale) / 2f)
                    )
                } else {
                    // 비활성 상태: 메탈릭 실버-슬레이트 알약 (3D 글래스 램프 느낌)
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.4f),
                        topLeft = Offset(pillLeft + 1f * scale, pillTop + 2f * scale),
                        size = Size(capsuleWidth, capsuleHeight),
                        cornerRadius = pillCorner
                    )

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF78909C),
                                Color(0xFF37474F)
                            ),
                            startY = pillTop,
                            endY = pillTop + capsuleHeight
                        ),
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(capsuleWidth, capsuleHeight),
                        cornerRadius = pillCorner
                    )

                    // 테두리 림
                    drawRoundRect(
                        color = Color(0xFF263238),
                        topLeft = Offset(pillLeft, pillTop),
                        size = Size(capsuleWidth, capsuleHeight),
                        cornerRadius = pillCorner,
                        style = Stroke(width = 1f * scale)
                    )

                    // 상단 은은한 하이라이트
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.35f),
                        topLeft = Offset(pillLeft + 1.5f * scale, pillTop + 1.5f * scale),
                        size = Size(capsuleWidth - 3f * scale, capsuleHeight * 0.35f),
                        cornerRadius = CornerRadius((capsuleWidth - 3f * scale) / 2f)
                    )
                }
            }
        }

        // ----------------------------------------------------
        // 5. 중앙 3D 메탈릭 볼록 돔 (Center Dome / Core Button)
        // ----------------------------------------------------
        // 돔 외곽 네온 시안 글로우 링
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.15f),
            radius = coreOuterRadius + (5f * scale),
            center = center,
            style = Stroke(width = 7f * scale)
        )
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.7f),
            radius = coreOuterRadius,
            center = center,
            style = Stroke(width = 2.5f * scale)
        )

        // 돔 본체: 빛을 받는 3D 볼록 구체 효과
        val domeBrush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF2C3E50), // 좌상단 하이라이트 반사광
                Color(0xFF162029),
                Color(0xFF0B1015)  // 심층 딥 다크
            ),
            center = Offset(center.x - coreDomeRadius * 0.25f, center.y - coreDomeRadius * 0.30f),
            radius = coreDomeRadius * 1.35f
        )
        drawCircle(
            brush = domeBrush,
            radius = coreDomeRadius,
            center = center
        )

        // 돔 내부 베벨 림
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = 0.3f),
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

            // 글로우
            val glowColor = Color(0xFF00E5FF).copy(alpha = 0.35f)
            drawLine(color = glowColor, start = Offset(b1X, barY1), end = Offset(b1X, barY2), strokeWidth = barW + 7f * scale, cap = StrokeCap.Round)
            drawLine(color = glowColor, start = Offset(b2X, barY1), end = Offset(b2X, barY2), strokeWidth = barW + 7f * scale, cap = StrokeCap.Round)

            // 코어 선
            val coreColor = Color(0xFF00F5FF)
            drawLine(color = coreColor, start = Offset(b1X, barY1), end = Offset(b1X, barY2), strokeWidth = barW, cap = StrokeCap.Round)
            drawLine(color = coreColor, start = Offset(b2X, barY1), end = Offset(b2X, barY2), strokeWidth = barW, cap = StrokeCap.Round)

            // 하이라이트 화이트 중심선
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

            // 정지 상태 네온 재생 기호
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
