package com.krdonon.wweather

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * headingDeg: 기기가 바라보는 방향(방위각)
     * 0=N, 90=E, 180=S, 270=W
     */
    var headingDeg: Float = 0f
        set(value) {
            field = normalizeDeg(value)
            invalidate()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0xFF2F2F2F.toInt()
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF2F2F2F.toInt()
        alpha = 90
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0xFF2F2F2F.toInt()
        alpha = 140
    }

    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(16f)
        isFakeBoldText = true
        color = 0xFF111111.toInt()
    }

    private val cardinalDegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
        color = 0xFF111111.toInt()
        alpha = 170
    }

    private val minorDegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        color = 0xFF111111.toInt()
        alpha = 130
    }

    // 상단 고정 포인터(일반 나침판 스타일)
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFD32F2F.toInt() // 빨강 포인터
        alpha = 235
    }

    private val pointerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF111111.toInt()
        alpha = 180
    }

    // 북(N) 강조용(원판에서 N만 빨갛게)
    private val northDialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        color = 0xFFD32F2F.toInt()
        alpha = 220
    }

    private val northTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(16f)
        isFakeBoldText = true
        color = 0xFFD32F2F.toInt()
    }
    private val northDegTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
        isFakeBoldText = true
        color = 0xFFD32F2F.toInt()
        alpha = 220
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // radius 계산: 카드널/각도 텍스트가 View 경계 밖으로 나가 잘리는 현상을 방지하기 위해
        // 텍스트(0/360, 90, 180, 270)까지 포함한 최대 반지름을 고려해 ringR을 상한으로 클램프한다.
        val maxR = min(w, h) / 2f - dp(12f)
        val ringR = kotlin.math.max(0f, kotlin.math.min(min(w, h) * 0.38f, maxR - dp(55f)))

        // 1) 상단 고정 포인터 먼저 그림 (회전 없음)
        drawTopPointer(canvas, cx, cy, ringR)

        // 2) 원판을 -heading 만큼 회전해서 그림
        canvas.save()
        canvas.rotate(-headingDeg, cx, cy) // 핵심: 원판 회전(사용자 회전 시 원판이 돌아감)

        drawDial(canvas, cx, cy, ringR)

        canvas.restore()
    }

    private fun drawDial(canvas: Canvas, cx: Float, cy: Float, ringR: Float) {
        // Outer ring
        canvas.drawCircle(cx, cy, ringR, ringPaint)

        val tickOuter = ringR
        val tickInnerMinor = ringR - dp(12f)
        val tickInnerMajor = ringR - dp(18f)

        // ticks: 30도 단위
        for (deg in 0 until 360 step 30) {
            val isMajor = (deg % 90 == 0)
            val isNorth = (deg == 0)

            val paint = when {
                isNorth -> northDialPaint               // 북쪽(0도)만 빨간 틱
                isMajor -> majorTickPaint
                else -> tickPaint
            }

            val inner = if (isMajor) tickInnerMajor else tickInnerMinor

            val (x1, y1) = pointOnCircle(cx, cy, tickOuter, deg.toFloat())
            val (x2, y2) = pointOnCircle(cx, cy, inner, deg.toFloat())
            canvas.drawLine(x1, y1, x2, y2, paint)
        }


        // Cardinal labels + degrees (원판에 붙어 같이 회전함)
        val maxR = min(width.toFloat(), height.toFloat()) / 2f - dp(12f)
        val labelR = kotlin.math.min(ringR + dp(30f), maxR - dp(18f))
        val degR = kotlin.math.min(ringR + dp(50f), maxR - dp(2f))
        drawCardinal(canvas, cx, cy, labelR, degR, "N", "0/360", 0f)
        drawCardinal(canvas, cx, cy, labelR, degR, "E", "90", 90f)
        drawCardinal(canvas, cx, cy, labelR, degR, "S", "180", 180f)
        drawCardinal(canvas, cx, cy, labelR, degR, "W", "270", 270f)

        // Minor degree labels
        val minorLabelR = ringR - dp(26f)
        val minorList = listOf(30, 60, 120, 150, 210, 240, 300, 330)
        for (d in minorList) {
            val (tx, ty) = pointOnCircle(cx, cy, minorLabelR, d.toFloat())
            canvas.drawText(d.toString(), tx, ty + dp(4f), minorDegPaint)
        }
    }

    private fun drawTopPointer(canvas: Canvas, cx: Float, cy: Float, ringR: Float) {
        // 상단(12시 방향) 고정 포인터: 일반 나침판처럼 "위가 내가 바라보는 방향"
        val topX = cx
        val topY = cy - ringR - dp(6f)

        val tip = Pair(topX, topY - dp(6f))
        val baseY = topY + dp(22f)
        val halfW = dp(12f)

        val path = Path().apply {
            moveTo(tip.first, tip.second)
            lineTo(topX - halfW, baseY)
            lineTo(topX + halfW, baseY)
            close()
        }

        canvas.drawPath(path, pointerPaint)
        canvas.drawPath(path, pointerStroke)
    }

    private fun drawCardinal(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        labelR: Float,
        degR: Float,
        label: String,
        degText: String,
        angle: Float
    ) {
        val isNorth = (angle == 0f)

        val (lx, ly) = pointOnCircle(cx, cy, labelR, angle)
        canvas.drawText(
            label,
            lx,
            ly + dp(6f),
            if (isNorth) northTextPaint else cardinalPaint
        )

        val (dx, dy) = pointOnCircle(cx, cy, degR, angle)
        canvas.drawText(
            degText,
            dx,
            dy + dp(6f),
            if (isNorth) northDegTextPaint else cardinalDegPaint
        )
    }


    /**
     * angleDeg: 0=N, 90=E, 180=S, 270=W
     * Canvas 좌표계(0=오른쪽)로 맞추기 위해 -90 보정
     */
    private fun pointOnCircle(cx: Float, cy: Float, r: Float, angleDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians((angleDeg - 90f).toDouble())
        val x = cx + (cos(rad) * r).toFloat()
        val y = cy + (sin(rad) * r).toFloat()
        return x to y
    }

    private fun normalizeDeg(v: Float): Float {
        var x = v % 360f
        if (x < 0f) x += 360f
        return x
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
