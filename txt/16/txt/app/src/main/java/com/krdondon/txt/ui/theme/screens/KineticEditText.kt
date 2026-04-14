package com.krdondon.txt.ui.screens

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.EditText
import android.widget.OverScroller
import androidx.core.view.GestureDetectorCompat
import kotlin.math.abs

/**
 * EditText with stronger inertial/fling scrolling for long plain-text documents.
 * Keeps normal text editing behavior but makes manual scroll feel less stiff.
 */
class KineticEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    private val scroller = OverScroller(context)

    private val gestureDetector = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (layout == null) return false
                if (abs(velocityY) < 250f) return false

                val maxY = (computeVerticalScrollRange() - height).coerceAtLeast(0)
                if (maxY <= 0) return false

                scroller.fling(
                    scrollX,
                    scrollY,
                    0,
                    (-velocityY).toInt(),
                    0,
                    0,
                    0,
                    maxY,
                    0,
                    height / 5
                )
                postInvalidateOnAnimation()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) scroller.abortAnimation()
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        }
        super.computeScroll()
    }
}
