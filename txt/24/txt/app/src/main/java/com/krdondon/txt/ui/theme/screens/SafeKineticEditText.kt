package com.krdondon.txt.ui.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.OverScroller

class SafeKineticEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    var appliedTextVersion: Int = -1

    private var kineticScroller: OverScroller? = null
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                abortKineticScroll()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val scroller = kineticScroller ?: return false
                val range = (computeVerticalScrollRange() - height).coerceAtLeast(0)
                if (range <= 0) return false

                scroller.fling(
                    scrollX,
                    scrollY,
                    0,
                    (-velocityY.toInt()).coerceIn(-12000, 12000),
                    0,
                    0,
                    0,
                    range
                )
                postInvalidateOnAnimation()
                return true
            }
        }
    )

    init {
        kineticScroller = OverScroller(context)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isScrollbarFadingEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setHorizontallyScrolling(false)
        breakStrategy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            LineBreaker.BREAK_STRATEGY_SIMPLE
        } else {
            @Suppress("DEPRECATION")
            Layout.BREAK_STRATEGY_SIMPLE
        }
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    }

    fun abortKineticScroll() {
        val scroller = kineticScroller ?: return
        if (!scroller.isFinished) {
            scroller.abortAnimation()
        }
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        abortKineticScroll()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return try {
            super.onTouchEvent(event)
        } catch (_: NullPointerException) {
            false
        }
    }

    override fun computeScroll() {
        super.computeScroll()
        val scroller = kineticScroller ?: return
        if (scroller.computeScrollOffset()) {
            val maxY = (computeVerticalScrollRange() - height).coerceAtLeast(0)
            scrollTo(scroller.currX, scroller.currY.coerceIn(0, maxY))
            postInvalidateOnAnimation()
        }
    }

    override fun draw(canvas: Canvas) {
        try {
            super.draw(canvas)
        } catch (_: NullPointerException) {
            drawWithoutFrameworkForeground(canvas)
        }
    }

    override fun onDrawForeground(canvas: Canvas) {
        // Android 15 / emulator 조합에서 EditText scrollbar drawable 이 null 인 상태로
        // View.onDrawScrollBars() 가 호출되어 NPE 가 나는 경우가 있어 foreground 기본 그리기를 건너뜁니다.
        // 이 화면은 스크롤바를 꺼서 사용하므로 영향이 거의 없습니다.
    }

    override fun awakenScrollBars(startDelay: Int, invalidate: Boolean): Boolean {
        return false
    }

    private fun drawWithoutFrameworkForeground(canvas: Canvas) {
        val saveCount = canvas.save()
        try {
            background?.draw(canvas)
            super.onDraw(canvas)
        } catch (_: NullPointerException) {
            // 마지막 방어선
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }
}
