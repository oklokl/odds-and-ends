package com.krdonon.wweather

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * Software Canvas(이미지 저장 캡처)에서도 둥근 모서리가 깨지지 않도록
 * 바깥쪽 4개 모서리를 Anti-aliased DST_OUT으로 지워내는 ImageView.
 */
class RoundedEmojiImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val radiusPx = 22f * resources.displayMetrics.density
    private val rect = RectF()
    private val cornerCutoutPath = Path()
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        val fullRectPath = Path().apply { addRect(rect, Path.Direction.CW) }
        val roundedPath = Path().apply { addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW) }
        cornerCutoutPath.reset()
        cornerCutoutPath.op(fullRectPath, roundedPath, Path.Op.DIFFERENCE)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            super.onDraw(canvas)
            return
        }
        val saveCount = canvas.saveLayer(rect, null)
        super.onDraw(canvas)
        canvas.drawPath(cornerCutoutPath, erasePaint)
        canvas.restoreToCount(saveCount)
    }
}
