package com.krdonon.touch

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlin.random.Random

/**
 * 고성능 물결 파동(Ripple Wave) 상태 관리 컨트롤러.
 *
 * 주요 특징:
 * 1. 각 물방울이 터질 때마다 동심원 파동의 겹(2~4겹)과 간격이 자연스럽게 랜덤화되어 유기적인 물결 연출.
 * 2. 화면 전체 Canvas와 연동되어 게임 보드 테두리 바깥으로도 시원하게 물결이 퍼져나감.
 * 3. 평상시 0% CPU/GPU 점유율로 완벽한 배터리/성능 최적화.
 */
class WaterRippleState {
    internal class RingSpec(
        val delayFactor: Float,
        val radiusMultiplier: Float,
        val isWhiteAccent: Boolean,
        val strokeScale: Float,
    )

    internal class RippleWaveData(
        val center: Offset,
        val maxRadius: Dp,
        val durationMs: Long,
        val startTimeMs: Long,
        val waveColor: Color,
        val rings: List<RingSpec>,
    )

    internal val activeRipples = ArrayList<RippleWaveData>(16)
    internal val ripplesLock = Any()

    var frameTick by mutableLongStateOf(0L)
        internal set

    var hasActiveRipples by mutableStateOf(value = false)
        internal set

    /**
     * 원하는 좌표(center)에 2~4겹의 자연스럽게 랜덤화된 동심원 파동을 생성합니다.
     *
     * @param center 파동 중심 좌표
     * @param maxRadius 최대 확산 반경 (과도하게 크지 않도록 250.dp 전후 권장)
     * @param durationMs 파동 애니메이션 지속 시간 (밀리초)
     * @param waveColor 주 파동 색상
     */
    fun spawnRipple(
        center: Offset,
        maxRadius: Dp = 250.dp,
        durationMs: Int = 950,
        waveColor: Color = Color(0xFF0091EA),
    ) {
        val now = SystemClock.uptimeMillis()

        // 파동의 겹 수를 3~4겹 사이에서 랜덤하게 결정하고 각 링의 위상과 반경을 자연스럽게 분산
        val ringCount = Random.nextInt(3, 5)
        val ringList = ArrayList<RingSpec>(ringCount)
        for (i in 0 until ringCount) {
            val delay = i * Random.nextDouble(0.09, 0.15).toFloat()
            val radiusMult = 1f - (i * Random.nextDouble(0.08, 0.14).toFloat())
            val isWhite = ((i % 2) == 0)
            val strokeScale = Random.nextDouble(0.85, 1.25).toFloat()
            ringList.add(RingSpec(delay, radiusMult, isWhite, strokeScale))
        }

        synchronized(ripplesLock) {
            activeRipples.add(
                RippleWaveData(
                    center = center,
                    maxRadius = maxRadius,
                    durationMs = durationMs.toLong(),
                    startTimeMs = now,
                    waveColor = waveColor,
                    rings = ringList,
                ),
            )
        }
        hasActiveRipples = true
    }
}

/**
 * [WaterRippleState]를 생성하여 기억하는 Composable 헬퍼
 */
@Composable
fun rememberWaterRippleState(): WaterRippleState {
    return remember { WaterRippleState() }
}

/**
 * [WaterRippleState]에 등록된 파동을 전체 화면 영역에 그리는 전용 Canvas Layer.
 *
 * - 게임 박스 내부에 갇히지 않고 바깥 들판 배경까지 시원하게 번져나가도록 전체 화면 크기로 배치됩니다.
 * - Draw Phase에서만 프레임 틱을 구독하여 Recomposition 없이 GPU 가속 드로잉만 수행합니다.
 */
@Composable
fun WaterRippleCanvas(
    state: WaterRippleState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.hasActiveRipples) {
        if (!state.hasActiveRipples) return@LaunchedEffect

        while (state.hasActiveRipples) {
            withFrameMillis { frameTime ->
                state.frameTick = frameTime

                val now = SystemClock.uptimeMillis()
                synchronized(state.ripplesLock) {
                    val iterator = state.activeRipples.iterator()
                    while (iterator.hasNext()) {
                        val ripple = iterator.next()
                        if ((now - ripple.startTimeMs) >= ripple.durationMs) {
                            iterator.remove()
                        }
                    }
                    if (state.activeRipples.isEmpty()) {
                        state.hasActiveRipples = false
                    }
                }
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (state.frameTick < 0) return@Canvas
        val now = SystemClock.uptimeMillis()
        val baseStrokeWidthPx = 3.4.dp.toPx()

        synchronized(state.ripplesLock) {
            for (ripple in state.activeRipples) {
                val elapsed = (now - ripple.startTimeMs).coerceAtLeast(0L)
                val rawFraction = (elapsed.toFloat() / ripple.durationMs).coerceIn(0f, 1f)

                if (rawFraction < 1f) {
                    val maxRadiusPx = ripple.maxRadius.toPx()

                    // 1) [중앙부] 터치 순간 퍼져나가는 하얀빛 수면 코어 스플래시
                    val centerProgress = (rawFraction * 2.2f).coerceIn(0f, 1f)
                    if (centerProgress < 1f) {
                        val centerRadius = (maxRadiusPx * 0.22f) * FastOutSlowInEasing.transform(centerProgress)
                        val centerAlpha = ((1f - centerProgress).pow(1.2f) * 0.75f).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color.White.copy(alpha = centerAlpha),
                            radius = centerRadius,
                            center = ripple.center,
                            style = Stroke(width = baseStrokeWidthPx * 0.9f),
                        )
                    }

                    // 2) [다중 파동 링] 랜덤하게 구성된 여러 겹(3~4겹)의 동심원 파동
                    for (ring in ripple.rings) {
                        val ringFraction = ((rawFraction - ring.delayFactor) / (1f - ring.delayFactor)).coerceIn(0f, 1f)
                        if ((ringFraction > 0f) && (ringFraction < 1f)) {
                            val ringProgress = FastOutSlowInEasing.transform(ringFraction)
                            val ringRadius = maxRadiusPx * ringProgress * ring.radiusMultiplier
                            val ringAlpha = ((1f - ringProgress).pow(1.3f) * (if (ring.isWhiteAccent) 0.85f else 0.72f)).coerceIn(0f, 1f)
                            val ringStroke = baseStrokeWidthPx * ring.strokeScale * (1f - (ringProgress * 0.45f))

                            if (ring.isWhiteAccent) {
                                // 하얀색 물결 링
                                drawCircle(
                                    color = Color.White.copy(alpha = ringAlpha),
                                    radius = ringRadius,
                                    center = ripple.center,
                                    style = Stroke(width = ringStroke),
                                )
                            } else {
                                // 파란색 톤 물결 링 + 하얀색 하이라이트 림
                                drawCircle(
                                    color = ripple.waveColor.copy(alpha = ringAlpha * 0.9f),
                                    radius = ringRadius,
                                    center = ripple.center,
                                    style = Stroke(width = ringStroke * 1.35f),
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = ringAlpha * 0.4f),
                                    radius = (ringRadius - (ringStroke * 0.5f)).coerceAtLeast(0f),
                                    center = ripple.center,
                                    style = Stroke(width = ringStroke * 0.65f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 터치 시 연못에 돌을 던진 것처럼 물방울 위치에서 원형 파동(Circular Ripple Wave)이 퍼져나가는
 * 커스텀 애니메이션 오버레이 래퍼 컴포저블.
 */
@Composable
fun WaterPondRippleBox(
    modifier: Modifier = Modifier,
    state: WaterRippleState = rememberWaterRippleState(),
    maxRadius: Dp = 250.dp,
    waveColor: Color = Color(0xFF0091EA),
    durationMs: Int = 950,
    onTouchOccurred: ((Offset) -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    state.spawnRipple(
                        center = tapOffset,
                        maxRadius = maxRadius,
                        durationMs = durationMs,
                        waveColor = waveColor,
                    )
                    onTouchOccurred?.invoke(tapOffset)
                }
            }
    ) {
        content()
        WaterRippleCanvas(state = state, modifier = Modifier.matchParentSize())
    }
}

/**
 * 물방울 파동 애니메이션 단독 테스트 / 데모 화면
 */
@Composable
fun WaterPondRippleDemoScreen() {
    val rippleState = rememberWaterRippleState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF102A43)),
        contentAlignment = Alignment.Center,
    ) {
        WaterPondRippleBox(
            modifier = Modifier.fillMaxSize(),
            state = rippleState,
            maxRadius = 250.dp,
            waveColor = Color(0xFF0091EA),
            durationMs = 950,
        ) {
            Text(
                text = "화면 어디든 터치하여\n연못 물결 파동을 확인하세요 💧",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WaterPondRippleDemoPreview() {
    WaterPondRippleDemoScreen()
}
