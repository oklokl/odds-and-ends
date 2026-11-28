package com.krdonon.timer.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 90도(시계방향) 회전 컨테이너.
 *
 * - android:rotation 을 쓰지 않고 "레이아웃 단위"로 화면을 회전시킨 것처럼 보이게 함
 * - 터치 좌표도 역변환하여 버튼 터치 영역이 보이는 위치와 1:1로 일치하도록 보정
 *
 * 사용: 이 레이아웃 안에 "가로(landscape) 기준"으로 설계한 UI를 넣으면
 * 세로(portrait)에서도 가로 화면이 90도 돌아간 형태로 정확하게 동작합니다.
 */
class QuarterTurnLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 자식은 "가로 화면" 기준으로 측정해야 하므로 가로/세로 스펙을 교환해서 측정
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)

        // 측정된 값도 교환 (부모는 실제 세로 화면 크기를 유지)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // 자식은 가로 기준 크기(= 부모의 height x width)로 배치
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(0, 0, height, width)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrices()
    }

    private fun updateMatrices() {
        // child -> parent 변환
        // 세로 화면(부모) 위에 가로 기준(자식)을 90도(시계방향) 회전해서 올리되,
        // 회전으로 인해 y가 음수가 되는 영역을 아래로 이동(translate)하여 화면 안으로 넣는다.
        //
        // 90도 시계방향 회전 후, 회전으로 인해 왼쪽으로 이동한 콘텐츠를
        // 다시 화면 안으로 가져오기 위해 width만큼 오른쪽으로 이동
        //
        // 변환 순서: 먼저 회전(rotate) → 그 다음 이동(translate)
        // postRotate를 먼저 하면 "R"이 먼저 적용, postTranslate가 "T"로 나중에 적용
        // 결과: T * R (오른쪽에서 왼쪽으로 읽음)
        drawMatrix.reset()
        drawMatrix.postRotate(90f)
        drawMatrix.postTranslate(width.toFloat(), 0f)

        drawMatrix.invert(inverseMatrix)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.concat(drawMatrix)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // parent 좌표 -> child 좌표로 역변환하여 터치 정합성 확보
        val transformed = MotionEvent.obtain(ev)
        transformed.transform(inverseMatrix)
        val handled = super.dispatchTouchEvent(transformed)
        transformed.recycle()
        return handled
    }
}
