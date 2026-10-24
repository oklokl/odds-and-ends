package com.krdonon.touch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/**
 * 태블릿(가로)에서만 "기존(세로) UI"를 -90°(시계 반대방향) 회전해 보여주는 래퍼.
 *
 * 포인트:
 * - 자식은 (가로/세로) 제약을 뒤집어서 측정(=세로 UI처럼 측정)한다.
 * - 부모는 태블릿 화면(가로)의 크기를 그대로 유지한다.
 * - placement 시 layer 변환으로 회전/이동을 적용한다.
 *
 * 주의: pointerInteropFilter를 사용하는 경우, MotionEvent 좌표는
 * placeRelativeWithLayer의 변환이 적용되지 않은 상태로 전달됩니다.
 * 따라서 TouchGameScreen에서 layoutMode를 확인하여 좌표를 수동으로 역변환해야 합니다.
 */
@Composable
fun TabletLandscapeScreen() {
    Rotated90CounterClockwise(modifier = Modifier) {
        TouchGameScreen(layoutMode = LayoutMode.TABLET_LANDSCAPE)
    }
}

@Composable
private fun Rotated90CounterClockwise(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        modifier = modifier,
        content = content
    ) { measurables, constraints ->
        val measurable = measurables.first()

        // 자식은 (가로/세로) 제약을 뒤집어서 측정한다.
        val childConstraints = Constraints(
            minWidth = constraints.minHeight,
            maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth,
            maxHeight = constraints.maxWidth
        )

        val placeable = measurable.measure(childConstraints)

        // 부모는 원래 가로 화면 크기를 유지
        layout(constraints.maxWidth, constraints.maxHeight) {
            // placeRelativeWithLayer는 Compose UI 버전에 따라 이름이 다를 수 있어,
            // BOM(2024.09.00) 기준으로는 아래 형태가 정상입니다.
            placeable.placeRelativeWithLayer(0, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                rotationZ = -90f
                // -90° 회전 시 y가 음수로 가므로, 자식의 '너비'만큼 아래로 이동
                translationY = placeable.width.toFloat()
                clip = false
            }
        }
    }
}
