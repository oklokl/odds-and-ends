package com.krdonon.timer

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/**
 * HorizontalScrollView는 스크롤/플링/스냅 애니메이션 중(mScroller가 아직 끝나지 않았을 때)
 * ACTION_DOWN을 가로채어 자식 버튼 클릭을 막는다.
 * 이 클래스는 스크롤 중에도 사용자가 드래그하지 않고 버튼을 짧게 탭하면
 * 스크롤을 멈추고 해당 버튼의 클릭이 정상 동작하도록 처리한다.
 */
class PresetHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val touchSlopSq = touchSlop * touchSlop
    private val hitRect = Rect()

    private var tapCandidate: View? = null
    private var isTapCandidateActive = false
    private var downX = 0f
    private var downY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val intercepted = super.onInterceptTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            clearTapCandidate()
            if (intercepted) {
                val candidate = findClickableChildUnder(ev.x, ev.y)
                if (candidate != null) {
                    tapCandidate = candidate
                    isTapCandidateActive = true
                    downX = ev.x
                    downY = ev.y
                    activePointerId = ev.getPointerId(0)
                    updateHotspot(candidate, ev.x, ev.y)
                    candidate.isPressed = true
                }
            }
        }
        return intercepted
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                clearTapCandidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTapCandidateActive) {
                    val idx = ev.findPointerIndex(activePointerId)
                    if (idx < 0) {
                        clearTapCandidate()
                    } else {
                        val dx = ev.getX(idx) - downX
                        val dy = ev.getY(idx) - downY
                        if (dx * dx + dy * dy > touchSlopSq) {
                            clearTapCandidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val candidate = tapCandidate
                val idx = ev.findPointerIndex(activePointerId)
                val upX = if (idx >= 0) ev.getX(idx) else ev.x
                val upY = if (idx >= 0) ev.getY(idx) else ev.y
                val shouldClick = isTapCandidateActive &&
                    candidate != null &&
                    isPointInChildWithSlop(candidate, upX, upY)
                clearTapCandidate()

                if (shouldClick) {
                    val cancelEvent = MotionEvent.obtain(ev).apply {
                        action = MotionEvent.ACTION_CANCEL
                    }
                    val handled = super.onTouchEvent(cancelEvent)
                    cancelEvent.recycle()
                    candidate.performClick()
                    return handled
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                clearTapCandidate()
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun clearTapCandidate() {
        tapCandidate?.isPressed = false
        tapCandidate = null
        isTapCandidateActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun findClickableChildUnder(x: Float, y: Float): View? {
        val container = getChildAt(0) as? ViewGroup ?: return null
        val contentX = (x + scrollX - container.left).toInt()
        val contentY = (y + scrollY - container.top).toInt()

        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child.visibility != VISIBLE || !child.isClickable || !child.isEnabled) continue
            child.getHitRect(hitRect)
            if (hitRect.contains(contentX, contentY)) {
                return child
            }
        }
        return null
    }

    private fun isPointInChildWithSlop(child: View, x: Float, y: Float): Boolean {
        val container = getChildAt(0) as? ViewGroup ?: return false
        val contentX = (x + scrollX - container.left).toInt()
        val contentY = (y + scrollY - container.top).toInt()
        child.getHitRect(hitRect)
        hitRect.inset(-touchSlop, -touchSlop)
        return hitRect.contains(contentX, contentY)
    }

    private fun updateHotspot(child: View, x: Float, y: Float) {
        val container = getChildAt(0) as? ViewGroup ?: return
        val localX = x + scrollX - container.left - child.left
        val localY = y + scrollY - container.top - child.top
        child.drawableHotspotChanged(localX, localY)
    }
}
