package com.krdondon.read.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.OverScroller
import java.util.Collections
import kotlin.math.abs

class LinedEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    // Paint for line numbers (very small blue text as requested by user)
    private val lineNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2") // Blue color
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }

    // Paint for wrapped lines indicator
    private val wrapIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90CAF9") // Soft light blue for wrapped line indicator
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }

    // Vertical divider line between line numbers and text
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBDEFB")
        strokeWidth = dpToPx(1f)
    }

    // Background color for gutter
    private val gutterBgPaint = Paint().apply {
        color = Color.parseColor("#F5F9FF")
    }

    private val lineBoundsRect = Rect()
    private val clipBoundsRect = Rect()

    // Store newline offsets for binary-search physical line lookup
    private val newlineOffsets = ArrayList<Int>()

    // High-performance smooth physics momentum scrolling
    private val scroller = OverScroller(context)
    private val flingGestureListener = FlingGestureListener()
    private val gestureDetector = GestureDetector(context, flingGestureListener).apply {
        setIsLongpressEnabled(false) // Let EditText handle text selection long presses
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var isScrolling = false
    private var startTouchX = 0f
    private var startTouchY = 0f

    var onContentChanged: ((String) -> Unit)? = null
    var onUserActivity: (() -> Unit)? = null

    init {
        lineNumPaint.textSize = spToPx(10f)
        wrapIndicatorPaint.textSize = spToPx(9f)

        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isVerticalScrollBarEnabled = true
        setHorizontallyScrolling(false)

        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.parseColor("#1C1B1F"))
        setBackgroundColor(Color.TRANSPARENT)

        updatePaddingForGutter()

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onUserActivity?.invoke()
            }
            override fun afterTextChanged(s: Editable?) {
                rebuildNewlineOffsets(s)
                updatePaddingForGutter()
                onContentChanged?.invoke(s?.toString().orEmpty())
            }
        })
    }

    fun setInitialContent(content: String) {
        setText(content)
        rebuildNewlineOffsets(text)
        updatePaddingForGutter()
        setSelection(0)
    }

    private fun rebuildNewlineOffsets(s: CharSequence?) {
        newlineOffsets.clear()
        if (s == null) return
        val len = s.length
        for (i in 0 until len) {
            if (s[i] == '\n') {
                newlineOffsets.add(i)
            }
        }
    }

    fun getTotalLineCount(): Int {
        return newlineOffsets.size + 1
    }

    private fun getGutterWidth(): Int {
        val digits = maxOf(3, getTotalLineCount().toString().length)
        val charWidth = lineNumPaint.measureText("9")
        return (digits * charWidth + dpToPx(16f)).toInt()
    }

    private fun updatePaddingForGutter() {
        val gutter = getGutterWidth()
        val top = dpToPx(8f).toInt()
        val right = dpToPx(8f).toInt()
        val bottom = dpToPx(8f).toInt()
        if (paddingLeft != gutter || paddingTop != top || paddingRight != right || paddingBottom != bottom) {
            setPadding(gutter, top, right, bottom)
        }
    }

    fun getMaxScrollY(): Int {
        val l = this.layout ?: return 0
        val totalHeight = l.height
        val visibleHeight = height - paddingTop - paddingBottom
        return (totalHeight - visibleHeight).coerceAtLeast(0)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val currY = scroller.currY
            val maxScrollY = getMaxScrollY()
            val targetY = currY.coerceIn(0, maxScrollY)
            if (targetY != scrollY) {
                scrollTo(0, targetY)
            }
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = event.x
                startTouchY = event.y
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                    isScrolling = true
                } else {
                    isScrolling = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = abs(event.y - startTouchY)
                val dx = abs(event.x - startTouchX)
                if (dy > touchSlop && dy > dx) {
                    isScrolling = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        // Process touch event through GestureDetector for smooth scrolling & velocity fling
        val handledByGesture = gestureDetector.onTouchEvent(event)

        if (isScrolling && event.actionMasked == MotionEvent.ACTION_MOVE) {
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (isScrolling) {
                isScrolling = false
                return true
            }
        }

        val result = super.onTouchEvent(event) || handledByGesture
        if (event.actionMasked == MotionEvent.ACTION_UP && !isScrolling) {
            performClick()
        }
        return result
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private inner class FlingGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) {
                scroller.abortAnimation()
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val maxScrollY = getMaxScrollY()
            if (maxScrollY <= 0) return false

            isScrolling = true
            parent?.requestDisallowInterceptTouchEvent(true)

            // 천천히 움직일 경우: 손가락 움직임에 1:1로 정밀하게 반응
            val newY = (scrollY + distanceY.toInt()).coerceIn(0, maxScrollY)
            if (newY != scrollY) {
                scrollTo(0, newY)
            }
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val maxScrollY = getMaxScrollY()
            if (maxScrollY <= 0) return false

            // 속도 기반 동적 관성 가속 (Velocity-based Dynamic Momentum Fling)
            // 천천히 스와이프할 경우: 1.0x (부드럽고 짧게 이동)
            // 빠르게 튕기듯 스와이프할 경우: 1.8x ~ 2.5x로 속도 증폭 + 마찰력(friction) 감소로 훨씬 많은 범위로 확 내려감
            val absVelocityY = abs(velocityY)
            val accelerationFactor = when {
                absVelocityY > 9000 -> 2.4f // 초고속 플링: 2.4배 가속
                absVelocityY > 5000 -> 1.8f // 빠른 플링: 1.8배 가속
                absVelocityY > 2500 -> 1.3f // 중간 플링: 1.3배 가속
                else -> 1.0f                // 부드러운 스와이프: 1.0배
            }

            val boostedVelocityY = (velocityY * accelerationFactor).toInt()

            // 마찰력 동적 설정: 빠른 스와이프일수록 마찰력을 낮추어 시원하게 멀리 미끄러지도록 함
            val dynamicFriction = if (absVelocityY > 4000) 0.006f else 0.012f
            scroller.setFriction(dynamicFriction)

            scroller.fling(
                0, scrollY,
                0, -boostedVelocityY,
                0, 0,
                0, maxScrollY,
                0, 0
            )

            postInvalidateOnAnimation()
            return true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val currentLayout = this.layout
        if (currentLayout == null) {
            super.onDraw(canvas)
            return
        }

        canvas.getClipBounds(clipBoundsRect)
        val gutterWidth = getGutterWidth().toFloat()
        val dividerX = gutterWidth - dpToPx(6f)

        // Draw gutter background
        canvas.drawRect(
            clipBoundsRect.left.toFloat(),
            clipBoundsRect.top.toFloat(),
            dividerX,
            clipBoundsRect.bottom.toFloat(),
            gutterBgPaint
        )

        // Draw vertical divider line
        canvas.drawLine(
            dividerX,
            clipBoundsRect.top.toFloat(),
            dividerX,
            clipBoundsRect.bottom.toFloat(),
            dividerPaint
        )

        val firstLine = currentLayout.getLineForVertical(clipBoundsRect.top)
        val lastLine = currentLayout.getLineForVertical(clipBoundsRect.bottom)
        val textSeq = text ?: ""

        val numberX = dividerX - dpToPx(4f)

        for (line in firstLine..lastLine) {
            val baseline = getLineBounds(line, lineBoundsRect)
            val lineStart = currentLayout.getLineStart(line)

            val isPhysicalLineStart = (line == 0 || (lineStart > 0 && lineStart - 1 < textSeq.length && textSeq[lineStart - 1] == '\n'))
            if (isPhysicalLineStart) {
                val physLineNum = findPhysicalLineNumber(lineStart)
                canvas.drawText(physLineNum.toString(), numberX, baseline.toFloat(), lineNumPaint)
            } else {
                // Subtle wrapped line marker
                canvas.drawText("·", numberX, baseline.toFloat(), wrapIndicatorPaint)
            }
        }

        super.onDraw(canvas)
    }

    private fun findPhysicalLineNumber(charOffset: Int): Int {
        if (newlineOffsets.isEmpty()) return 1
        val idx = Collections.binarySearch(newlineOffsets, charOffset)
        return if (idx >= 0) {
            idx + 2
        } else {
            -idx
        }
    }

    /**
     * Search next occurrence starting from current selection end (or cursor).
     */
    fun searchNext(query: String): SearchResult {
        if (query.isBlank()) return SearchResult(isFound = false, matchIndex = -1, totalMatches = 0, didWrap = false)
        val content = text?.toString() ?: return SearchResult(isFound = false, matchIndex = -1, totalMatches = 0, didWrap = false)

        val matches = findAllMatchIndices(content, query)
        if (matches.isEmpty()) {
            return SearchResult(isFound = false, matchIndex = -1, totalMatches = 0, didWrap = false)
        }

        val startCursor = selectionEnd.coerceAtLeast(0)
        var matchIdx = matches.indexOfFirst { it >= startCursor }
        var didWrap = false

        if (matchIdx == -1) {
            matchIdx = 0
            didWrap = true
        }

        val targetOffset = matches[matchIdx]
        highlightAndScrollTo(targetOffset, query.length)

        return SearchResult(
            isFound = true,
            matchIndex = matchIdx + 1,
            totalMatches = matches.size,
            didWrap = didWrap
        )
    }

    /**
     * Search previous occurrence before current selection start.
     */
    fun searchPrevious(query: String): SearchResult {
        if (query.isBlank()) return SearchResult(isFound = false, matchIndex = -1, totalMatches = 0, didWrap = false)
        val content = text?.toString() ?: return SearchResult(isFound = false, matchIndex = -1, totalMatches = 0, didWrap = false)

        val matches = findAllMatchIndices(content, query)
        if (matches.isEmpty()) {
            return SearchResult(isFound = false, matchIndex = -1, totalMatches = 0, didWrap = false)
        }

        val startCursor = (selectionStart - 1).coerceAtLeast(0)
        var matchIdx = matches.indexOfLast { it <= startCursor }
        var didWrap = false

        if (matchIdx == -1) {
            matchIdx = matches.lastIndex
            didWrap = true
        }

        val targetOffset = matches[matchIdx]
        highlightAndScrollTo(targetOffset, query.length)

        return SearchResult(
            isFound = true,
            matchIndex = matchIdx + 1,
            totalMatches = matches.size,
            didWrap = didWrap
        )
    }

    fun countMatches(query: String): Int {
        if (query.isBlank()) return 0
        val content = text?.toString() ?: return 0
        return findAllMatchIndices(content, query).size
    }

    private fun findAllMatchIndices(content: String, query: String): List<Int> {
        val list = mutableListOf<Int>()
        var index = content.indexOf(query, 0, ignoreCase = true)
        while (index != -1) {
            list.add(index)
            index = content.indexOf(query, index + query.length.coerceAtLeast(1), ignoreCase = true)
        }
        return list
    }

    private fun highlightAndScrollTo(offset: Int, length: Int) {
        requestFocus()
        setSelection(offset, offset + length)
        post {
            val l = this.layout ?: return@post
            val line = l.getLineForOffset(offset)
            val lineTop = l.getLineTop(line)
            val scrollTarget = (lineTop - height / 3).coerceAtLeast(0)
            scrollTo(0, scrollTarget)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun spToPx(sp: Float): Float {
        return sp * resources.displayMetrics.scaledDensity
    }

    data class SearchResult(
        val isFound: Boolean,
        val matchIndex: Int,
        val totalMatches: Int,
        val didWrap: Boolean
    )
}
