package com.krdonon.wweather

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 풍향계(바늘) 표시용 뷰.
 * - 입력: windDirectionDeg (0=N, 90=E, 180=S, 270=W)
 * - 화면: 원형 다이얼 + N/E/S/W + 바늘 회전
 */
class WindVaneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var windDirectionDeg: Float = Float.NaN
        set(value) {
            field = value
            invalidate()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0xFF333333.toInt()
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0x55333333
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = sp(16f)
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFF222222.toInt()
    }

    // 카드널 각도(0/90/180/270) 표기용: 조금 더 진하고 크게
    private val degreePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFF444444.toInt()
    }

    // 중간 각도(30/60/120...) 표기용: 작은 글씨
    private val minorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = sp(9f)
        color = 0xFF9A9A9A.toInt()
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = dp(2f)

        // 빨간색 + 투명(알파) 처리
        color = 0xFFD32F2F.toInt()  // CompassView의 빨강과 톤 맞춤
        alpha = 150                // 0~255 (값이 작을수록 더 투명)
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF555555.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.40f

        // 외곽 링
        canvas.drawCircle(cx, cy, r, ringPaint)

        // 4방향 틱
        for (deg in listOf(0f, 90f, 180f, 270f)) {
            val rot = deg - 90f
            canvas.save()
            canvas.rotate(rot, cx, cy)
            canvas.drawLine(cx + r * 0.78f, cy, cx + r, cy, tickPaint)
            canvas.restore()
        }

        // N/E/S/W (+ 각도) + 중간 각도 라벨
// - 카드널(N/E/S/W)은 바깥쪽에, 각도(0/90/180/270)는 카드널 아래에 표시
// - 30도 단위의 중간 각도는 작은 글씨로 원 안쪽에 표시
        val outer = r + dp(30f)

        // 카드널(문자)과 각도(숫자) 사이 기본 간격
        val dyDeg = dp(16f)

        // 미세 튜닝(요청: N은 위로, 180은 아래로. S 위치는 그대로)
        val nUp = dp(12f)          // 약 2mm
        val sDegDown = dp(12f)     // 약 2mm

        fun drawCardinal(label: String, degText: String, x: Float, y: Float, degExtraY: Float = 0f) {
            // 카드널
            canvas.drawText(label, x, y + textBaselineFix(textPaint), textPaint)
            // 각도(카드널 아래)
            canvas.drawText(degText, x, y + dyDeg + degExtraY + textBaselineFix(degreePaint), degreePaint)
        }

        // N: 전체를 위로 살짝 올리되, 상단 잘림은 방지
        val halfLetter = textPaint.textSize * 0.5f
        val topSafeY = dp(2f) + halfLetter
        val yN = maxOf(topSafeY, cy - outer - nUp)
        drawCardinal("N", "0/360", cx, yN)

        // E/W: 기존 위치 유지
        drawCardinal("E", "90", cx + outer, cy)
        drawCardinal("W", "270", cx - outer, cy)

        // S: 문자 위치는 유지, 180만 아래로
        val halfDeg = degreePaint.textSize * 0.5f
        val bottomSafeYForDeg = height.toFloat() - dp(2f) - halfDeg - dyDeg - sDegDown
        val yS = minOf(cy + outer, bottomSafeYForDeg)
        drawCardinal("S", "180", cx, yS, degExtraY = sDegDown)

        // 중간 각도(30도 단위, 카드널 제외)
        val minorRadius = r * 0.78f
        val minorDegrees = intArrayOf(30, 60, 120, 150, 210, 240, 300, 330)
        for (d in minorDegrees) {
            val theta = Math.toRadians((d - 90).toDouble())
            val x = (cx + Math.cos(theta) * minorRadius).toFloat()
            val y = (cy + Math.sin(theta) * minorRadius).toFloat()
            canvas.drawText(d.toString(), x, y + textBaselineFix(minorTextPaint), minorTextPaint)
        }

// 바늘
        if (!windDirectionDeg.isNaN()) {
            val rot = normalizeDeg(windDirectionDeg) - 90f
            canvas.save()
            canvas.rotate(rot, cx, cy)
            drawNeedle(canvas, cx, cy, r)
            canvas.restore()
        } else {
            // 값이 없으면 중앙 허브만
            canvas.drawCircle(cx, cy, dp(6f), hubPaint)
        }
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 바늘 본체: 중심 -> 오른쪽(0deg)
        val bodyLen = r * 0.92f
        val tailLen = r * 0.35f

        canvas.drawLine(cx - tailLen, cy, cx + bodyLen, cy, needlePaint)

        // 화살촉
        val headW = r * 0.10f
        val headL = r * 0.18f
        val tipX = cx + bodyLen
        val baseX = tipX - headL

        val path = Path().apply {
            moveTo(tipX, cy)
            lineTo(baseX, cy - headW)
            lineTo(baseX, cy + headW)
            close()
        }
        canvas.drawPath(path, needlePaint)

        // 중심 허브
        canvas.drawCircle(cx, cy, dp(6f), hubPaint)
    }

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    private fun textBaselineFix(p: Paint): Float {
        // Align.CENTER는 baseline 기준이므로 약간 보정
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
