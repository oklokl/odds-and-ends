package com.krdonon.touch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 콤보 레벨 구분
 */
enum class ComboType {
    NORMAL,            // 일반 점수
    COMBO_10,          // 10단위 콤보 (10, 20, 30, 50, 60...)
    SUPER_COMBO_40,    // 40단위 슈퍼 콤보 (40, 80, 120...)
}

/**
 * 콤보 점수 팝업 데이터 모델
 */
data class ScorePopupItem(
    val id: Long,
    val position: Offset,
    val text: String,
    val comboType: ComboType,
    val startTimeMs: Long,
    val durationMs: Long,
)

/**
 * 점수 팝업 상태 관리 컨트롤러
 */
class ScorePopupState {
    internal val activePopups = ArrayList<ScorePopupItem>(16)
    internal val lock = Any()

    var frameTick by mutableLongStateOf(0L)
        internal set

    var hasActivePopups by mutableStateOf(value = false)
        internal set

    private val idGenerator = AtomicLong(0L)

    /**
     * 터치한 손가락 위치에 점수 팝업을 생성합니다.
     *
     * @param position 터치된 X, Y 좌표 (픽셀 단위)
     * @param comboScore 현재 달성한 콤보/터치 점수
     */
    fun spawnScorePopup(
        position: Offset,
        comboScore: Int,
    ) {
        val comboType = when {
            ((comboScore > 0) && (comboScore % 40 == 0)) -> ComboType.SUPER_COMBO_40
            ((comboScore > 0) && (comboScore % 10 == 0)) -> ComboType.COMBO_10
            else -> ComboType.NORMAL
        }

        val displayText = when (comboType) {
            ComboType.SUPER_COMBO_40 -> "+$comboScore!"
            ComboType.COMBO_10 -> "+$comboScore!"
            ComboType.NORMAL -> "+1"
        }

        val duration = when (comboType) {
            ComboType.SUPER_COMBO_40 -> 1300L
            ComboType.COMBO_10 -> 950L
            ComboType.NORMAL -> 750L
        }

        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            activePopups.add(
                ScorePopupItem(
                    id = idGenerator.incrementAndGet(),
                    position = position,
                    text = displayText,
                    comboType = comboType,
                    startTimeMs = now,
                    durationMs = duration,
                ),
            )
        }
        hasActivePopups = true
    }
}

@Composable
fun rememberScorePopupState(): ScorePopupState {
    return remember { ScorePopupState() }
}

/**
 * Jetpack Compose 화면 위에 점수 팝업 및 반짝이(Sparkle) 효과를 실시간 렌더링하는 오버레이
 */
@Composable
fun ScorePopupOverlay(
    state: ScorePopupState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    // 애니메이션 프레임 루프 (팝업이 활성화된 동안만 동작하여 평상시 CPU/GPU 소모 0%)
    LaunchedEffect(state.hasActivePopups) {
        if (!state.hasActivePopups) return@LaunchedEffect

        while (state.hasActivePopups) {
            withFrameMillis { frameTime ->
                state.frameTick = frameTime
                val now = SystemClock.uptimeMillis()

                synchronized(state.lock) {
                    val iterator = state.activePopups.iterator()
                    while (iterator.hasNext()) {
                        val popup = iterator.next()
                        if ((now - popup.startTimeMs) >= popup.durationMs) {
                            iterator.remove()
                        }
                    }
                    if (state.activePopups.isEmpty()) {
                        state.hasActivePopups = false
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (state.frameTick < 0) return@Box
        val now = SystemClock.uptimeMillis()

        val popupsSnapshot = synchronized(state.lock) {
            state.activePopups.toList()
        }

        popupsSnapshot.forEach { popup ->
            val elapsed = (now - popup.startTimeMs).coerceAtLeast(0L)
            val rawFraction = (elapsed.toFloat() / popup.durationMs).coerceIn(0f, 1f)

            if (rawFraction < 1f) {
                // 1) 스케일 애니메이션: 톡 터지듯 커졌다가 줄어드는 역동적인 팝업 바운스
                val scale = when (popup.comboType) {
                    ComboType.SUPER_COMBO_40 -> {
                        // 40단위: 초반에 2.45배까지 폭발적으로 커진 후 탄력있게 바운스
                        if (rawFraction < 0.18f) {
                            0.4f + (2.05f * (rawFraction / 0.18f))
                        } else if (rawFraction < 0.38f) {
                            2.45f - (1.05f * ((rawFraction - 0.18f) / 0.20f))
                        } else {
                            1.40f - (0.25f * ((rawFraction - 0.38f) / 0.62f))
                        }
                    }
                    ComboType.COMBO_10 -> {
                        // 10단위: 1.55배까지 시원하게 팝업 후 부드럽게 정착
                        if (rawFraction < 0.22f) {
                            0.5f + (1.05f * (rawFraction / 0.22f))
                        } else if (rawFraction < 0.40f) {
                            1.55f - (0.40f * ((rawFraction - 0.22f) / 0.18f))
                        } else {
                            1.15f - (0.15f * ((rawFraction - 0.40f) / 0.60f))
                        }
                    }
                    ComboType.NORMAL -> {
                        // 일반 점수: 0.7 -> 1.15 -> 1.0으로 가볍고 경쾌하게 팝
                        if (rawFraction < 0.25f) {
                            0.7f + (0.45f * (rawFraction / 0.25f))
                        } else {
                            1.15f - (0.15f * ((rawFraction - 0.25f) / 0.75f))
                        }
                    }
                }

                // 2) 위로 둥실 떠오르는 부유 거리 (Float up)
                val floatDistancePx = with(density) {
                    val targetDist = when (popup.comboType) {
                        ComboType.SUPER_COMBO_40 -> 65.dp.toPx()
                        ComboType.COMBO_10 -> 52.dp.toPx()
                        ComboType.NORMAL -> 40.dp.toPx()
                    }
                    FastOutSlowInEasing.transform(rawFraction) * targetDist
                }

                // 3) 페이드 아웃 (Fade-out)
                val alpha = when (popup.comboType) {
                    ComboType.SUPER_COMBO_40 -> {
                        if (rawFraction < 0.65f) 1f else ((1f - rawFraction) / 0.35f).coerceIn(0f, 1f)
                    }
                    ComboType.COMBO_10 -> {
                        if (rawFraction < 0.55f) 1f else ((1f - rawFraction) / 0.45f).coerceIn(0f, 1f)
                    }
                    ComboType.NORMAL -> {
                        if (rawFraction < 0.50f) 1f else ((1f - rawFraction) / 0.50f).coerceIn(0f, 1f)
                    }
                }

                // 손가락 터치 위치 바로 위로 오프셋 설정
                val yOffsetInitialPx = with(density) {
                    if (popup.comboType == ComboType.SUPER_COMBO_40) 45.dp.toPx() else 34.dp.toPx()
                }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = popup.position.x.roundToInt(),
                                y = (popup.position.y - yOffsetInitialPx - floatDistancePx).roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            // 크기와 텍스트 길이에 상관없이 터치 중앙에 완벽히 정렬
                            translationX = -size.width / 2f
                            translationY = -size.height / 2f
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 반짝이(별/빛 가루) 파티클
                    if (popup.comboType == ComboType.SUPER_COMBO_40) {
                        SparkleParticlesCanvas(
                            fraction = rawFraction,
                            modifier = Modifier.matchParentSize(),
                            particleCount = 14,
                            maxSpreadDp = 72.dp,
                        )
                    } else if (popup.comboType == ComboType.COMBO_10) {
                        SparkleParticlesCanvas(
                            fraction = rawFraction,
                            modifier = Modifier.matchParentSize(),
                            particleCount = 8,
                            maxSpreadDp = 48.dp,
                        )
                    }

                    // 점수 텍스트 색상
                    val textColor = when (popup.comboType) {
                        ComboType.SUPER_COMBO_40 -> Color(0xFFFF0000) // 선명한 진한 빨간색(#FF0000)
                        ComboType.COMBO_10 -> Color(0xFFFF1744)       // 10단위 네온 레드
                        ComboType.NORMAL -> Color(0xFFFFF176)         // 일반 점수 골드 옐로우
                    }

                    // 점수 텍스트 크기: 크고 뚜렷하게 강조
                    val textSize = when (popup.comboType) {
                        ComboType.SUPER_COMBO_40 -> 52.sp             // 일반 점수의 2.3배 이상 초대형 크기!
                        ComboType.COMBO_10 -> 30.sp                   // 10단위 시원한 크기
                        ComboType.NORMAL -> 22.sp                     // 일반 점수 기본 크기
                    }

                    // 입체감을 위한 선명한 드롭 섀도우
                    val textShadow = when (popup.comboType) {
                        ComboType.SUPER_COMBO_40 -> Shadow(
                            color = Color.Black.copy(alpha = 0.95f),
                            offset = Offset(3f, 5f),
                            blurRadius = 10f,
                        )
                        ComboType.COMBO_10 -> Shadow(
                            color = Color.Black.copy(alpha = 0.9f),
                            offset = Offset(2f, 4f),
                            blurRadius = 6f,
                        )
                        ComboType.NORMAL -> Shadow(
                            color = Color.Black.copy(alpha = 0.85f),
                            offset = Offset(2f, 3f),
                            blurRadius = 4f,
                        )
                    }

                    Text(
                        text = popup.text,
                        color = textColor,
                        fontSize = textSize,
                        fontWeight = FontWeight.ExtraBold,
                        style = TextStyle(shadow = textShadow),
                    )
                }
            }
        }
    }
}

/**
 * 콤보 달성 시 점수 글자 주변으로 터져나가는 방사형 반짝이(Sparkle) 파티클
 */
@Composable
private fun SparkleParticlesCanvas(
    fraction: Float,
    modifier: Modifier = Modifier,
    particleCount: Int = 8,
    maxSpreadDp: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val density = LocalDensity.current
    val maxSpreadDistPx = with(density) { maxSpreadDp.toPx() }
    val particleSizePx = with(density) { 5.dp.toPx() }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val particleProgress = FastOutSlowInEasing.transform(fraction)
        val currentDist = maxSpreadDistPx * particleProgress
        val particleAlpha = ((1f - fraction).pow(1.35f) * 0.95f).coerceIn(0f, 1f)

        val angleStep = (2 * PI) / particleCount

        for (i in 0 until particleCount) {
            val angleRad = (i * angleStep).toFloat()
            val px = center.x + (cos(angleRad) * currentDist)
            val py = center.y + (sin(angleRad) * currentDist)

            val pColor = when {
                i % 3 == 0 -> Color(0xFFFFD700) // 반짝이는 골드
                i % 3 == 1 -> Color(0xFFFF5252) // 빛나는 레드-오렌지
                else -> Color.White
            }

            // 4방향 십자 별(Sparkle Star)
            val starSize = particleSizePx * (1f - (fraction * 0.35f))
            val path = Path().apply {
                moveTo(px, py - starSize)
                lineTo(px + (starSize * 0.35f), py)
                lineTo(px, py + starSize)
                lineTo(px - (starSize * 0.35f), py)
                close()
                moveTo(px - starSize, py)
                lineTo(px, py + (starSize * 0.35f))
                lineTo(px + starSize, py)
                lineTo(px, py - (starSize * 0.35f))
                close()
            }

            drawPath(
                path = path,
                color = pColor.copy(alpha = particleAlpha),
            )
        }
    }
}

/**
 * [전통 Android View 기반] XML/ViewGroup 환경에서 사용할 수 있는 점수 팝업 헬퍼 클래스
 */
object AndroidViewScorePopupHelper {
    fun showScorePopup(
        parentLayout: ViewGroup,
        touchX: Float,
        touchY: Float,
        comboScore: Int,
    ) {
        val context = parentLayout.context
        val isCombo40 = (comboScore > 0) && (comboScore % 40 == 0)
        val isCombo10 = (comboScore > 0) && (comboScore % 10 == 0)

        val textView = TextView(context).apply {
            text = if (isCombo10) "+$comboScore!" else "+1"
            textSize = when {
                isCombo40 -> 52f
                isCombo10 -> 30f
                else -> 22f
            }
            setTextColor(
                when {
                    isCombo40 -> android.graphics.Color.parseColor("#FF0000")
                    isCombo10 -> android.graphics.Color.parseColor("#FF1744")
                    else -> android.graphics.Color.parseColor("#FFF176")
                },
            )
            setTypeface(null, android.graphics.Typeface.BOLD)
            setShadowLayer(
                if (isCombo40) 10f else 6f,
                if (isCombo40) 3f else 2f,
                if (isCombo40) 5f else 4f,
                android.graphics.Color.BLACK,
            )

            measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            layoutParams = lp
            x = touchX - (measuredWidth / 2f)
            y = touchY - measuredHeight - (if (isCombo40) 45f else 34f)
        }

        parentLayout.addView(textView)

        val targetScale = if (isCombo40) 2.45f else if (isCombo10) 1.55f else 1.15f
        val floatDist = if (isCombo40) -110f else if (isCombo10) -85f else -65f
        val animDuration = if (isCombo40) 1300L else if (isCombo10) 950L else 750L

        val scaleX = ObjectAnimator.ofFloat(textView, View.SCALE_X, 0.4f, targetScale, 1f)
        val scaleY = ObjectAnimator.ofFloat(textView, View.SCALE_Y, 0.4f, targetScale, 1f)
        val floatUp = ObjectAnimator.ofFloat(textView, View.TRANSLATION_Y, 0f, floatDist)
        val fadeOut = ObjectAnimator.ofFloat(textView, View.ALPHA, 1f, 1f, 0f)

        val animatorSet = AnimatorSet().apply {
            playTogether(scaleX, scaleY, floatUp, fadeOut)
            duration = animDuration
            interpolator = if (isCombo40 || isCombo10) OvershootInterpolator(2.6f) else DecelerateInterpolator()
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        parentLayout.removeView(textView)
                    }
                },
            )
        }

        animatorSet.start()
    }
}
