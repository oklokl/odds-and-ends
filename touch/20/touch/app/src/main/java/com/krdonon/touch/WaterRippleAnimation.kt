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

/**
 * 고성능 물결 파동(Ripple Wave) 상태 관리 컨트롤러.
 *
 * [최적화 특징]
 * 1. 파동이 없을 때는 애니메이션 루프가 완전히 정지(0% CPU/GPU 소모).
 * 2. 파동이 활성화되면 단 하나의 [withFrameMillis] 루프가 동작하여 Compose Recomposition을 유발하지 않고
 *    Draw Phase에서만 초당 60/120Hz로 GPU 가속 렌더링을 수행합니다.
 * 3. 각 파동 애니메이션 종료 시 리소스를 즉시 반환하여 메모리 누수를 방지합니다.
 */
class WaterRippleState {
    internal class RippleWaveData(
        val center: Offset,
        val maxRadius: Dp,
        val durationMs: Long,
        val startTimeMs: Long,
        val waveColor: Color,
    )

    internal val activeRipples = ArrayList<RippleWaveData>(16)
    internal val ripplesLock = Any()

    var frameTick by mutableLongStateOf(0L)
        internal set

    var hasActiveRipples by mutableStateOf(value = false)
        internal set

    /**
     * 원하는 좌표(center)에 하얀색과 파란색의 2가지 톤으로 크게 울려 퍼지는 원형 파동을 1회 생성합니다.
     */
    fun spawnRipple(
        center: Offset,
        maxRadius: Dp = 290.dp,
        durationMs: Int = 950,
        waveColor: Color = Color(0xFF0091EA), // 청명하고 선명한 딥 아쿠아 블루
    ) {
        val now = SystemClock.uptimeMillis()
        synchronized(ripplesLock) {
            activeRipples.add(
                RippleWaveData(
                    center = center,
                    maxRadius = maxRadius,
                    durationMs = durationMs.toLong(),
                    startTimeMs = now,
                    waveColor = waveColor,
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
 * - 파동이 진행 중일 때만 [withFrameMillis] 클럭이 활성화됩니다.
 * - frameTick 상태 구독을 [Canvas] 드로잉 람다 내부로 지연(Defer)시켜 불필요한 Recomposition을 원천 차단합니다.
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
        // Draw Phase에서만 frameTick을 읽어 불필요한 Recomposition 방지
        if (state.frameTick < 0) return@Canvas
        val now = SystemClock.uptimeMillis()
        val baseStrokeWidthPx = 3.6.dp.toPx()

        synchronized(state.ripplesLock) {
            for (ripple in state.activeRipples) {
                val elapsed = (now - ripple.startTimeMs).coerceAtLeast(0L)
                val rawFraction = (elapsed.toFloat() / ripple.durationMs).coerceIn(0f, 1f)

                if (rawFraction < 1f) {
                    val progress = FastOutSlowInEasing.transform(rawFraction)
                    val maxRadiusPx = ripple.maxRadius.toPx()
                    val currentRadius = maxRadiusPx * progress

                    // 바깥으로 퍼질수록 부드럽게 감쇄하는 주 파동 알파
                    val mainAlpha = ((1f - progress).pow(1.25f) * 0.9f).coerceIn(0f, 1f)
                    // 퍼질수록 에너지가 분산되어 선이 자연스럽게 얇아짐
                    val strokeWidth = baseStrokeWidthPx * (1f - (progress * 0.45f))

                    // 1) [중앙부] 하얀색 계통 코어 파동 (중심에서 은은하게 먼저 번지는 하얀 링)
                    val centerProgress = (progress * 1.9f).coerceIn(0f, 1f)
                    if (centerProgress < 1f) {
                        val centerRadius = (maxRadiusPx * 0.28f) * centerProgress
                        val centerAlpha = ((1f - centerProgress).pow(1.2f) * 0.75f).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color.White.copy(alpha = centerAlpha),
                            radius = centerRadius,
                            center = ripple.center,
                            style = Stroke(width = strokeWidth * 0.9f),
                        )
                    }

                    // 2) [내측 울림] 주 파동 뒤를 따라오는 은은한 하얀색 서브 울림 물결
                    val echoFraction = ((rawFraction - 0.12f) / 0.88f).coerceIn(0f, 1f)
                    if ((echoFraction > 0f) && (echoFraction < 1f)) {
                        val echoProgress = FastOutSlowInEasing.transform(echoFraction)
                        val echoRadius = maxRadiusPx * echoProgress * 0.76f
                        val echoAlpha = ((1f - echoProgress).pow(1.3f) * 0.5f).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color.White.copy(alpha = echoAlpha),
                            radius = echoRadius,
                            center = ripple.center,
                            style = Stroke(width = strokeWidth * 0.7f),
                        )
                    }

                    // 3) [외측 메인 파동 - 파란색 톤] 깊고 선명한 블루 물결 링
                    drawCircle(
                        color = ripple.waveColor.copy(alpha = mainAlpha * 0.85f),
                        radius = currentRadius,
                        center = ripple.center,
                        style = Stroke(width = strokeWidth * 1.45f),
                    )

                    // 4) [외측 메인 파동 - 하얀색 톤] 파란색 링 상단의 선명한 하얀색 물결 능선(Crest)
                    val crestRadius = (currentRadius - (strokeWidth * 0.4f)).coerceAtLeast(0f)
                    drawCircle(
                        color = Color.White.copy(alpha = mainAlpha),
                        radius = crestRadius,
                        center = ripple.center,
                        style = Stroke(width = strokeWidth * 0.75f),
                    )
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
    maxRadius: Dp = 290.dp,
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
            maxRadius = 300.dp,
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
