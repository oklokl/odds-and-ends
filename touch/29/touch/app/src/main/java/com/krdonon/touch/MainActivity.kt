@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.krdonon.touch

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Bundle
import android.os.SystemClock
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.krdonon.touch.ui.theme.TouchTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.random.Random
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import android.view.MotionEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import android.content.pm.ActivityInfo


import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow


enum class LayoutMode {
    PHONE_PORTRAIT,
    TABLET_LANDSCAPE
}

enum class Season {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER
}

fun getCurrentSeason(): Season {
    val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
    return when (month) {
        3, 4, 5 -> Season.SPRING
        6, 7, 8 -> Season.SUMMER
        9, 10, 11 -> Season.AUTUMN
        else -> Season.WINTER // 12, 1, 2
    }
}

fun getSeasonBackgroundRes(season: Season, layoutMode: LayoutMode): Int {
    val isLandscape = layoutMode == LayoutMode.TABLET_LANDSCAPE
    return when (season) {
        Season.SPRING -> if (isLandscape) R.drawable.bg_landscape else R.drawable.bg_portrait
        Season.SUMMER -> if (isLandscape) R.drawable.bg_summer_landscape else R.drawable.bg_summer_portrait
        Season.AUTUMN -> if (isLandscape) R.drawable.bg_autumn_landscape else R.drawable.bg_autumn_portrait
        Season.WINTER -> if (isLandscape) R.drawable.bg_winter_landscape else R.drawable.bg_winter_portrait
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var ageSignalsCompliance: AgeSignalsCompliance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ageSignalsCompliance = AgeSignalsCompliance(applicationContext)
        enableEdgeToEdge()
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (isTablet) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContent {
            TouchTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = Color.Transparent
                ) {
                    if (isTablet) {
                        TabletLandscapeScreen()
                    } else {
                        TouchGameScreen()
                    }
                }
            }
        }

        // Play Age Signals 0.0.4 공식 2단계 흐름을 앱 실행 시 시작합니다.
        // 결과는 메모리에만 유지하며 게임 UI/기존 기능은 변경하지 않습니다.
        ageSignalsCompliance.refresh(this)
    }
}

data class TouchTarget(
    val id: Long,
    val x: Float,
    val y: Float,
    val spawnTimeMs: Long = SystemClock.elapsedRealtime(),
)

data class PopEffect(
    val id: Long,
    val x: Float,
    val y: Float
)

private fun formatElapsedTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe % 3_600_000L) / 60_000L
    val seconds = (safe % 60_000L) / 1_000L
    val millis = safe % 1_000L
    return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
}

@Composable
private fun ElapsedTimeDisplay(
    elapsedMsProvider: () -> Long,
    modifier: Modifier = Modifier
) {
    Text(
        text = formatElapsedTime(elapsedMsProvider()),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF0F325E),
        modifier = modifier
    )
}

/**
 * 단계 계산 (제한 없이 무한 상승):
 * - 1 ~ 140단계: 40초(40,000ms) 단위로 1단계씩 상승 (Perfect 보너스로 빠르게 도달 가능)
 * - 140단계 이후: 너무 성급하게 올라가지 않고 무난한 느낌을 주기 위해 120초(2분)마다 1단계씩 차분하게 계속 상승!
 */
private fun stageFromElapsedMs(elapsedMs: Long): Int {
    val safe = elapsedMs.coerceAtLeast(0L)
    val cutoffMs = 139L * 40_000L // 140단계까지의 기준 시간 (5,560,000ms)

    return if (safe <= cutoffMs) {
        val step = (safe / 40_000L).toInt()
        step + 1
    } else {
        val overflowMs = safe - cutoffMs
        val extraSteps = (overflowMs / 120_000L).toInt() // 140단계 이후는 120초(2분) 단위로 천천히 상승
        140 + extraSteps
    }
}

/**
 * 속도 조절:
 * - 1~140단계: 1단계 기준 1.0~2.0초에서 점진적으로 가속
 * - 140단계 이후: 게임의 플레이 가능성과 쾌적한 밸런스를 유지하기 위해 미세하게(단계당 약 0.35ms) 지속 가속
 */
private fun spawnDelayRangeMs(stage: Int): LongRange {
    val s = stage.coerceAtLeast(1)
    return if (s <= 140) {
        val expSteps = (s - 1)
        val factor = 0.985.pow(expSteps.toDouble())
        val min = (1000L * factor).toLong().coerceAtLeast(120L)
        val max = (2000L * factor).toLong().coerceAtLeast(min + 120L)
        min..max
    } else {
        val extra = (s - 140)
        // 140단계 이후로는 미세하고 부드럽게 가속 (하한값 80ms)
        val min = (120L - (extra * 0.35).toLong()).coerceAtLeast(80L)
        val max = (min + 120L - (extra * 0.35).toLong()).coerceAtLeast(min + 60L)
        min..max
    }
}

private fun targetLifetimeMs(stage: Int): Long {
    val s = stage.coerceAtLeast(1)
    return if (s <= 140) {
        val expSteps = (s - 1)
        val factor = 0.993.pow(expSteps.toDouble())
        (2200L * factor).toLong().coerceAtLeast(700L)
    } else {
        val extra = (s - 140)
        // 140단계 이후로는 미세하게 줄어들며 하한값 500ms 유지
        (700L - (extra * 1.2).toLong()).coerceAtLeast(500L)
    }
}

/**
 * 동시 등장 물방울이 늘어났을 때(점점 증가 / 수동 증가 사용 시)
 * 여러 개의 물방울을 차례대로 보고 터치할 수 있도록 충분한 여유 시간을 보정해 줍니다.
 * (물방울 개수마다 +0.3초씩, 7개일 때 약 +1.8초 추가 부여)
 */
private fun extraLifetimeMsForTargetCount(targetCount: Int): Long {
    val count = targetCount.coerceIn(1, 7)
    return if (count > 1) (count - 1) * 300L else 0L
}

/**
 * 물방울이 하나씩 차례대로 이슬 맺히듯(두더지처럼 뿅뿅) 나타나는 순차 등장 간격.
 * 140단계 이후로도 미세하게 짧아지며 리듬감 유지.
 */
private fun targetStaggerDelayRangeMs(stage: Int): LongRange {
    val s = stage.coerceAtLeast(1)
    return if (s <= 140) {
        val expSteps = (s - 1)
        val factor = 0.99.pow(expSteps.toDouble())
        val min = (220L * factor).toLong().coerceAtLeast(120L)
        val max = (420L * factor).toLong().coerceAtLeast(min + 60L)
        min..max
    } else {
        val extra = (s - 140)
        val min = (120L - (extra * 0.25).toLong()).coerceAtLeast(80L)
        val max = (min + 60L).coerceAtLeast(min + 40L)
        min..max
    }
}

private fun randomTargetStaggerDelayMs(stage: Int): Long {
    val range = targetStaggerDelayRangeMs(stage)
    if (range.first == range.last) return range.first

    val base = Random.nextLong(range.first, range.last + 1)
    // 자연스러운 물방울 맺힘 리듬을 위해 미세한 템포 변화
    val varied = when (Random.nextInt(4)) {
        0 -> (base * 0.85).toLong()
        3 -> (base * 1.15).toLong()
        else -> base
    }
    return varied.coerceIn(100L, 500L)
}



/**
 * 단계(7단위) 증가에 따라 동시 등장 물방울 개수 증가 (최대 7개).
 * - 1~6단계: 1개
 * - 7~13단계: 2개
 * - 14~20단계: 3개
 * - 21~27단계: 4개
 * - 28~34단계: 5개
 * - 35~41단계: 6개
 * - 42단계~: 7개(최대)
 */
private fun targetCountFromStage(stage: Int): Int {
    val s = stage.coerceAtLeast(0)
    return (1 + (s / 7)).coerceIn(1, 7)
}

/**
 * 동시 등장 타겟을 만들 때 특정 사분면에 갇히지 않고 화면 전체에서 자유롭게 랜덤 생성합니다.
 * - 타겟 개수(최대 7개)에 맞춘 유기적 거리 제약으로, 자연스러운 겹침은 살리면서도
 *   완전히 포개지는 현상은 방지하여 실제 아케이드 게임처럼 다이나믹하게 배치합니다.
 */
private fun generateTargets(count: Int): List<TouchTarget> {
    val targetCount = count.coerceIn(1, 7)
    if (targetCount <= 0) return emptyList()

    val result = mutableListOf<TouchTarget>()
    val baseId = SystemClock.elapsedRealtimeNanos()

    // 타겟 개수에 따른 유기적 기본 최소 거리 (개수가 많을수록 좀 더 가깝게, 적을수록 넓게)
    val baseMinDist = when {
        targetCount <= 3 -> 0.30f
        targetCount <= 5 -> 0.24f
        else -> 0.20f
    }

    repeat(targetCount) { idx ->
        var minDist = baseMinDist
        var bestX = 0.5f
        var bestY = 0.5f
        var placed = false

        // 3단계에 걸쳐 거리를 부드럽게 완화하며 최적의 유기적 랜덤 위치 탐색
        var sIdx = 0
        searchStage@ while (sIdx < 3) {
            sIdx++
            val minDistSq = minDist * minDist

            var aIdx = 0
            while (aIdx < 25) {
                aIdx++
                // 화면 전체(0.05f ~ 0.95f)에서 완전 자유롭게 랜덤 위치 생성
                val x = 0.05f + Random.nextFloat() * 0.90f
                val y = 0.05f + Random.nextFloat() * 0.90f

                val isFarEnough = result.all { t ->
                    val dx = t.x - x
                    val dy = t.y - y
                    (dx * dx + dy * dy) >= minDistSq
                }

                if (isFarEnough) {
                    bestX = x
                    bestY = y
                    placed = true
                    break@searchStage
                }
            }

            minDist *= 0.80f // 거리를 조금 완화하여 재시도
        }

        if (!placed) {
            // 자리를 찾지 못한 경우 기존 타겟들과 가장 거리가 먼 지점 선택
            var maxDistanceSq = -1f
            repeat(15) {
                val x = 0.05f + Random.nextFloat() * 0.90f
                val y = 0.05f + Random.nextFloat() * 0.90f
                val closest = result.minOfOrNull { t ->
                    val dx = t.x - x
                    val dy = t.y - y
                    dx * dx + dy * dy
                } ?: Float.MAX_VALUE
                if (closest > maxDistanceSq) {
                    maxDistanceSq = closest
                    bestX = x
                    bestY = y
                }
            }
        }

        result += TouchTarget(
            id = baseId + idx,
            x = bestX.coerceIn(0f, 1f),
            y = bestY.coerceIn(0f, 1f)
        )
    }

    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchGameScreen(layoutMode: LayoutMode = LayoutMode.PHONE_PORTRAIT) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current

    // 게임 진행 시간 (일시정지 시 시간 정지)
    var elapsedMs by rememberSaveable { mutableLongStateOf(0L) }
    var accumulatedMs by rememberSaveable { mutableLongStateOf(0L) }
    var lastStartRealtimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    var touchCount by rememberSaveable { mutableIntStateOf(0) }
    var gameScore by rememberSaveable { mutableLongStateOf(0L) } // 콤보 및 Perfect 배율이 반영된 실제 게임 점수
    var failCount by rememberSaveable { mutableIntStateOf(0) }
    var comboCount by rememberSaveable { mutableIntStateOf(0) }
    var isGamePaused by rememberSaveable { mutableStateOf(false) }
    var touchTargets by remember { mutableStateOf<List<TouchTarget>>(emptyList()) }
    var popEffects by remember { mutableStateOf<List<PopEffect>>(emptyList()) }
    var isBgmEnabled by rememberSaveable { mutableStateOf(true) }

    // 난이도 옵션
    // 요청: 시작부터 활성화 X -> 기본은 회색(OFF)
    var isAutoDifficultyEnabled by rememberSaveable { mutableStateOf(false) } // “점점 어렵게”
    var isManualStageEnabled by rememberSaveable { mutableStateOf(false) }
    var manualStage by rememberSaveable { mutableIntStateOf(0) }
    var manualStageStartMs by rememberSaveable { mutableLongStateOf(0L) } // 수동 설정 적용 시점의 진행 시간
    var showStageDialog by remember { mutableStateOf(false) }

    // 물방울(타겟) 동시 등장 개수 옵션
    // - 점점 증가: 단계가 올라가면 7단계마다 1개씩(최대 7개)
    // - 수동 증가: 시작 개수 설정 후 단계를 올려가며 계속 증가(최대 7개)
    var isAutoIncreaseEnabled by rememberSaveable { mutableStateOf(false) } // "점점 증가"
    var isManualIncreaseEnabled by rememberSaveable { mutableStateOf(false) }
    var manualTargetCount by rememberSaveable { mutableIntStateOf(2) } // 수동 증가 시작 개수 (2~7)
    var manualTargetStartStage by rememberSaveable { mutableIntStateOf(0) } // 수동 개수 적용 시점의 단계
    var showIncreaseDialog by remember { mutableStateOf(false) }

    // 앱 백그라운드 -> 복귀 시 자동으로 재개(풍선 안 뜨는 문제 대응)
    var wasAutoPaused by remember { mutableStateOf(false) }

    // Reset을 위해 효과 재시작 트리거
    var resetToken by rememberSaveable { mutableIntStateOf(0) }

    // 터치 게임 영역만 화면 전체로 확대/축소하는 상태
    var isGameAreaExpanded by rememberSaveable { mutableStateOf(false) }

    // assets/music 내의 모든 음원 파일을 자동 인식하여 순서대로 무한 반복 재생하는 BGM 관리자
    val bgmManager = remember { BgmPlaylistManager(appContext) }

    val soundPool = remember {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(audioAttributes)
            .build()
    }

    val soundId = remember {
        try {
            soundPool.load(appContext, R.raw.sound, 1)
        } catch (_: Exception) {
            -1
        }
    }

    val superComboSoundId = remember {
        try {
            soundPool.load(appContext, R.raw.super_combo, 1)
        } catch (_: Exception) {
            -1
        }
    }

    // 터치 터짐(스프라이트) 프레임들 - res/drawable 에 btouchimage1~5.png
    val popFrames = remember {
        listOf(
            R.drawable.btouchimage1,
            R.drawable.btouchimage2,
            R.drawable.btouchimage3,
            R.drawable.btouchimage4,
            R.drawable.btouchimage5
        )
    }


    // BGM 토글
    LaunchedEffect(isBgmEnabled) {
        bgmManager.setEnabled(isBgmEnabled)
    }

    // 앱이 백그라운드로 가면 자동 일시정지 / 복귀 시 자동 재개(자동 일시정지였던 경우만)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    bgmManager.onResumeOrStart()
                }

                Lifecycle.Event.ON_RESUME -> {
                    bgmManager.onResumeOrStart()
                    if (wasAutoPaused) {
                        isGamePaused = false
                        wasAutoPaused = false
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    // 일시적 포커스 변경(다이얼로그 팝업 등) 시 BGM은 유지하고 게임 시간만 기록
                    accumulatedMs = elapsedMs
                    wasAutoPaused = !isGamePaused
                    isGamePaused = true
                }

                Lifecycle.Event.ON_STOP -> {
                    // 홈 화면 나가기/화면 끄기 등 앱을 실제로 떠날 때만 BGM 정지
                    accumulatedMs = elapsedMs
                    bgmManager.onPauseOrStop()
                }

                Lifecycle.Event.ON_DESTROY -> {
                    bgmManager.release()
                }

                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 게임 진행 시간 업데이트 (일시정지 시 멈춤, 부드러운 .000 밀리초 흐름을 위해 약 60fps(16ms) 주기로 갱신)
    LaunchedEffect(isGamePaused, resetToken) {
        if (!isGamePaused) {
            lastStartRealtimeMs = SystemClock.elapsedRealtime()
            while (!isGamePaused) {
                val now = SystemClock.elapsedRealtime()
                elapsedMs = accumulatedMs + (now - lastStartRealtimeMs)
                delay(16)
            }
            accumulatedMs = elapsedMs
        }
    }

    val autoStage = remember(isAutoDifficultyEnabled, elapsedMs / 1000L) {
        if (isAutoDifficultyEnabled) stageFromElapsedMs(elapsedMs) else 1
    }

    // 수동 단계가 활성화되었을 때도 멈춰있지 않고, 해당 시작 단계부터 플레이 진행(시간 경과 + Perfect 가산)에 따라 계속 상승
    val dynamicManualStage = remember(isManualStageEnabled, manualStage, elapsedMs / 1000L, manualStageStartMs) {
        if (isManualStageEnabled) {
            val elapsedSinceManual = (elapsedMs - manualStageStartMs).coerceAtLeast(0L)
            val addedStages = (stageFromElapsedMs(elapsedSinceManual) - 1).coerceAtLeast(0)
            manualStage + addedStages
        } else {
            manualStage
        }
    }

    val effectiveStage: Int = remember(
        isManualStageEnabled,
        dynamicManualStage,
        isAutoDifficultyEnabled,
        autoStage,
    ) {
        when {
            isManualStageEnabled -> dynamicManualStage.coerceIn(0, 9999)
            isAutoDifficultyEnabled -> autoStage
            else -> 1
        }
    }

    // 수동 개수 설정 시에도 해당 시작 개수(예: 3개)에서 멈춰있지 않고,
    // 단계를 올려가며 계속 맞추다 보면 4개, 5개, 6개, 7개로 자연스럽게 계속 증가!
    val dynamicManualTargetCount = remember(
        isManualIncreaseEnabled,
        manualTargetCount,
        effectiveStage,
        manualTargetStartStage
    ) {
        if (isManualIncreaseEnabled) {
            val stagesAdvanced = (effectiveStage - manualTargetStartStage).coerceAtLeast(0)
            (manualTargetCount + (stagesAdvanced / 7)).coerceIn(manualTargetCount, 7)
        } else {
            manualTargetCount
        }
    }

    val effectiveTargetCount: Int = remember(
        isManualIncreaseEnabled,
        dynamicManualTargetCount,
        isAutoIncreaseEnabled,
        effectiveStage,
    ) {
        when {
            isManualIncreaseEnabled -> dynamicManualTargetCount
            isAutoIncreaseEnabled -> targetCountFromStage(effectiveStage)
            else -> 1
        }
    }

    val currentEffectiveStage by rememberUpdatedState(effectiveStage)
    val currentEffectiveTargetCount by rememberUpdatedState(effectiveTargetCount)

    // 타겟 생성 및 라이프사이클 관리
    // 설정/단계가 바뀌어도 진행 중인 물방울이 캔슬되어 멈추는 일이 없도록
    // LaunchedEffect 키는 isGamePaused와 resetToken으로만 제한합니다.
    LaunchedEffect(isGamePaused, resetToken) {
        while (!isGamePaused) {
            // 이전 웨이브의 화면상 물방울이 남아 있으면 모두 처리될 때까지 대기
            if (touchTargets.isNotEmpty()) {
                delay(25)
                continue
            }

            val stageForThisSpawn = currentEffectiveStage
            val countForThisSpawn = currentEffectiveTargetCount.coerceIn(1, 7)

            // 사이터스 성장 시간(750ms) 동안 작았다가 커진 뒤에도, 지금처럼 충분히 터치할 수 있는 넉넉한 시간 부여
            val lifetimeForThisSpawn = 750L + targetLifetimeMs(stageForThisSpawn) +
                    extraLifetimeMsForTargetCount(countForThisSpawn)

            val delayRange = spawnDelayRangeMs(stageForThisSpawn)
            val nextDelay = if (delayRange.first == delayRange.last) delayRange.first
            else Random.nextLong(delayRange.first, delayRange.last + 1)

            delay(nextDelay)

            if (isGamePaused) break
            if (touchTargets.isNotEmpty()) continue

            // 7개의 물방울이 한 번에 뭉쳐 나오지 않고,
            // 두더지처럼 뿅뿅, 이슬이 하나씩 방울방울 맺히듯 순서대로 차례차례 등장합니다.
            val waveTargets = generateTargets(countForThisSpawn)

            for ((index, target) in waveTargets.withIndex()) {
                if (index > 0) {
                    delay(randomTargetStaggerDelayMs(stageForThisSpawn))
                }

                if (isGamePaused) break

                // 화면에 실제로 등장한 시점의 타임스탬프로 spawnTimeMs를 기록하여 사이터스 성장 시작
                val activeTarget = target.copy(spawnTimeMs = SystemClock.elapsedRealtime())
                touchTargets = touchTargets + activeTarget

                val targetId = activeTarget.id
                launch {
                    delay(lifetimeForThisSpawn)
                    if (touchTargets.any { it.id == targetId }) {
                        touchTargets = touchTargets.filterNot { it.id == targetId }
                        failCount++
                        comboCount = 0 // 실패 시 콤보 0으로 초기화
                    }
                }
            }
        }
    }

    if (showStageDialog) {
        var pickerValue by remember { mutableIntStateOf(manualStage.coerceIn(0, 999)) }
        var manualEnabled by remember { mutableStateOf(isManualStageEnabled) }
        AlertDialog(
            onDismissRequest = { showStageDialog = false },
            title = { Text("단계 수동 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "0 ~ 999 단계 선택",
                        fontSize = 14.sp
                    )
                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx).apply {
                                minValue = 0
                                maxValue = 999
                                value = pickerValue
                                setOnValueChangedListener { _, _, newVal ->
                                    pickerValue = newVal
                                }
                            }
                        },
                        update = { it.value = pickerValue },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = manualEnabled,
                            onCheckedChange = { manualEnabled = it }
                        )
                        Text("수동 단계 적용")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        manualStage = pickerValue
                        manualStageStartMs = elapsedMs
                        isManualStageEnabled = manualEnabled
                        showStageDialog = false
                    }
                ) { Text("적용") }
            },
            dismissButton = {
                TextButton(onClick = { showStageDialog = false }) { Text("닫기") }
            }
        )
    }

    if (showIncreaseDialog) {
        var pickerValue by remember { mutableIntStateOf(manualTargetCount.coerceIn(2, 7)) }
        var manualEnabled by remember { mutableStateOf(isManualIncreaseEnabled) }

        AlertDialog(
            onDismissRequest = { showIncreaseDialog = false },
            title = { Text("수동 증가 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "동시에 등장할 물방울 개수 (2 ~ 7)",
                        fontSize = 14.sp
                    )

                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx).apply {
                                minValue = 2
                                maxValue = 7
                                value = pickerValue
                                setOnValueChangedListener { _, _, newVal ->
                                    pickerValue = newVal
                                }
                            }
                        },
                        update = { it.value = pickerValue },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = manualEnabled,
                            onCheckedChange = { manualEnabled = it }
                        )
                        Text("수동 증가 적용")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        manualTargetCount = pickerValue.coerceIn(2, 7)
                        manualTargetStartStage = effectiveStage
                        isManualIncreaseEnabled = manualEnabled
                        showIncreaseDialog = false
                    }
                ) { Text("적용") }
            },
            dismissButton = {
                TextButton(onClick = { showIncreaseDialog = false }) { Text("닫기") }
            }
        )
    }


    fun handleTargetHit(hitTarget: TouchTarget, gainedScore: Int = 100) {
        // 같은 타겟이 이미 처리된 뒤 들어온 중복 터치는 무시합니다.
        if (touchTargets.none { it.id == hitTarget.id }) return

        touchCount += 1
        comboCount += 1
        gameScore += gainedScore // 콤보 및 Perfect 배율이 반영된 점수 가산
        val currentCombo = comboCount
        touchTargets = touchTargets.filterNot { it.id == hitTarget.id }

        popEffects = popEffects + PopEffect(
            id = SystemClock.elapsedRealtimeNanos(),
            x = hitTarget.x,
            y = hitTarget.y
        )

        // 40의 배수 콤보일 때 super_combo.wav 재생, 그 외(10단위 포함)에는 기존 효과음 재생
        if (currentCombo > 0 && currentCombo % 40 == 0 && superComboSoundId != -1) {
            soundPool.play(superComboSoundId, 1.0f, 1.0f, 2, 0, 1.0f)
        } else if (soundId != -1) {
            soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
        }
    }

    // 덜 커진 상태에서 조기 터치했을 때 실패 처리 (FAIL)
    fun handleEarlyFail(hitTarget: TouchTarget) {
        if (touchTargets.none { it.id == hitTarget.id }) return
        failCount += 1
        comboCount = 0
        touchTargets = touchTargets.filterNot { it.id == hitTarget.id }
        if (soundId != -1) {
            soundPool.play(soundId, 0.6f, 0.6f, 1, 0, 0.75f)
        }
    }

    // Perfect 터치 시 7단계로 빠르게 도달할 수 있도록 진행 시간을 앞당겨주는 함수
    fun advanceGameTime(bonusMs: Long) {
        accumulatedMs += bonusMs
        elapsedMs += bonusMs
    }

    // 확대 상태에서 시스템 뒤로가기를 누르면 앱을 종료하지 않고 원래 화면으로 복귀합니다.
    BackHandler(enabled = isGameAreaExpanded) {
        isGameAreaExpanded = false
    }

    val currentSeason = remember { getCurrentSeason() }
    val bgRes = remember(currentSeason, layoutMode) {
        getSeasonBackgroundRes(currentSeason, layoutMode)
    }

    // 전체 화면 물결 파동 및 점수/PERFECT 팝업 상태 관리자
    val rippleState = rememberWaterRippleState()
    val scorePopupState = rememberScorePopupState()

    // 스마트폰 기울기에 따른 실시간 물방울 수평 회전 각도
    val gravityAngle = rememberGravityRotationAngle()

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = bgRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (isGameAreaExpanded) 0.dp else 14.dp)
                .padding(
                    top = if (isGameAreaExpanded) 0.dp else 10.dp,
                    bottom = if (isGameAreaExpanded) 0.dp else 6.dp
                ),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (!isGameAreaExpanded) {
                // 1) 상단: 게임 진행 시간 + BGM 스위치 카드
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(22.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(Color(0xFFEAF5FF), Color(0xFFD4E9FA))
                            ),
                            shape = RoundedCornerShape(22.dp)
                        )
                        .border(
                            width = 1.5.dp,
                            color = Color(0xFFB8DAF5),
                            shape = RoundedCornerShape(22.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_alarm_clock),
                            contentDescription = "시계",
                            modifier = Modifier.size(36.dp)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "게임 진행 시간",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E3A5F)
                            )
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = Color(0xFFCBE5FA),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 3.dp)
                            ) {
                                ElapsedTimeDisplay(
                                    elapsedMsProvider = { elapsedMs }
                                )
                            }
                        }
                    }

                    Switch(
                        checked = isBgmEnabled,
                        onCheckedChange = { isBgmEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF4CAF50),
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFB0BEC5)
                        )
                    )
                }

                // 2) 성공/실패 횟수 카드
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 버튼 누른 횟수 (골든 옐로우 카드)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .shadow(4.dp, RoundedCornerShape(20.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xFFFFF9C4), Color(0xFFFFE082))
                                ),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .border(
                                width = 2.dp,
                                color = Color(0xFFFFD54F),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_bulb_lit),
                                contentDescription = "성공 전구",
                                modifier = Modifier.size(36.dp)
                            )
                            Column {
                                Text(
                                    text = "버튼 누른 횟수",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4E342E)
                                )
                                Text(
                                    text = touchCount.toString(),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF1A237E)
                                )
                            }
                        }
                        Image(
                            painter = painterResource(id = R.drawable.ic_leaf_decor),
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .align(Alignment.BottomEnd)
                        )
                    }

                    // 실패 횟수 (화이트/소프트 그레이 카드)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .shadow(4.dp, RoundedCornerShape(20.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F8))
                                ),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .border(
                                width = 2.dp,
                                color = Color(0xFFCFD8DC),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_bulb_unlit),
                                contentDescription = "실패 전구",
                                modifier = Modifier.size(36.dp)
                            )
                            Column {
                                Text(
                                    text = "실패 횟수",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF37474F)
                                )
                                Text(
                                    text = failCount.toString(),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF263238)
                                )
                            }
                        }
                    }
                }
            }

            // 3) 게임 보드
            TouchGameArea(
                modifier = if (isGameAreaExpanded) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                },
                effectiveStage = effectiveStage,
                gameScore = gameScore,
                comboCount = comboCount,
                gravityAngle = gravityAngle,
                touchTargets = touchTargets,
                popEffects = popEffects,
                isGamePaused = isGamePaused,
                popFrames = popFrames,
                isExpanded = isGameAreaExpanded,
                onToggleExpanded = { isGameAreaExpanded = !isGameAreaExpanded },
                onTargetHit = ::handleTargetHit,
                onTargetEarlyFail = ::handleEarlyFail,
                onAdvanceGameTime = ::advanceGameTime,
                onPopFinished = { effectId ->
                    popEffects = popEffects.filterNot { it.id == effectId }
                },
                rippleState = rippleState,
                scorePopupState = scorePopupState,
            )

            if (!isGameAreaExpanded) {
                // 전체화면 텍스트 버튼 (부드러운 반투명 뱃지 스타일)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0x66FFFFFF), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x66B0BEC5), RoundedCornerShape(12.dp))
                            .clickable { isGameAreaExpanded = true }
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⛶  전체화면",
                            color = Color(0xFF1E3A5F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 4) 1행 버튼: 재생/일시정지, 초기화
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .shadow(4.dp, RoundedCornerShape(24.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xFFFF9E1B), Color(0xFFF57C00))
                                ),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .border(1.5.dp, Color(0xFFFFB74D), RoundedCornerShape(24.dp))
                            .clickable { isGamePaused = !isGamePaused },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isGamePaused) "▶  재생" else "❚❚  일시 정지",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .shadow(4.dp, RoundedCornerShape(24.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xFF42A5F5), Color(0xFF1976D2))
                                ),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .border(1.5.dp, Color(0xFF90CAF9), RoundedCornerShape(24.dp))
                            .clickable {
                                touchCount = 0
                                gameScore = 0L
                                failCount = 0
                                comboCount = 0
                                touchTargets = emptyList()
                                isGamePaused = false
                                accumulatedMs = 0L
                                elapsedMs = 0L
                                lastStartRealtimeMs = SystemClock.elapsedRealtime()
                                isAutoDifficultyEnabled = false
                                isManualStageEnabled = false
                                manualStage = 0
                                manualStageStartMs = 0L
                                isAutoIncreaseEnabled = false
                                isManualIncreaseEnabled = false
                                manualTargetCount = 2
                                manualTargetStartStage = 0
                                showIncreaseDialog = false
                                resetToken++
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⏩  초기화",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 5) 2행 버튼: 점점 어렵게, 단계 수동
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .shadow(3.dp, RoundedCornerShape(24.dp))
                            .background(
                                brush = if (isAutoDifficultyEnabled) {
                                    Brush.verticalGradient(listOf(Color(0xFFBA68C8), Color(0xFF8E24AA)))
                                } else {
                                    Brush.verticalGradient(listOf(Color(0xFFE2EAF1), Color(0xFFCFDCE7)))
                                },
                                shape = RoundedCornerShape(24.dp)
                            )
                            .border(
                                1.dp,
                                if (isAutoDifficultyEnabled) Color(0xFFCE93D8) else Color(0xFFB0C4D6),
                                RoundedCornerShape(24.dp)
                            )
                            .clickable { isAutoDifficultyEnabled = !isAutoDifficultyEnabled },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚙  점점 어렵게",
                            color = if (isAutoDifficultyEnabled) Color.White else Color(0xFF263238),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .shadow(3.dp, RoundedCornerShape(24.dp))
                            .background(
                                brush = if (isManualStageEnabled) {
                                    Brush.verticalGradient(listOf(Color(0xFF4DB6AC), Color(0xFF00796B)))
                                } else {
                                    Brush.verticalGradient(listOf(Color(0xFFE2EAF1), Color(0xFFCFDCE7)))
                                },
                                shape = RoundedCornerShape(24.dp)
                            )
                            .border(
                                1.dp,
                                if (isManualStageEnabled) Color(0xFF80CBC4) else Color(0xFFB0C4D6),
                                RoundedCornerShape(24.dp)
                            )
                            .clickable { showStageDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "📊  단계 수동 $effectiveStage",
                            color = if (isManualStageEnabled) Color.White else Color(0xFF263238),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 6) 3행 버튼: 점점 증가, 수동 증가
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .shadow(3.dp, RoundedCornerShape(24.dp))
                            .background(
                                brush = if (isAutoIncreaseEnabled) {
                                    Brush.verticalGradient(listOf(Color(0xFF81C784), Color(0xFF388E3C)))
                                } else {
                                    Brush.verticalGradient(listOf(Color(0xFFE2EAF1), Color(0xFFCFDCE7)))
                                },
                                shape = RoundedCornerShape(24.dp)
                            )
                            .border(
                                1.dp,
                                if (isAutoIncreaseEnabled) Color(0xFFA5D6A7) else Color(0xFFB0C4D6),
                                RoundedCornerShape(24.dp)
                            )
                            .clickable { isAutoIncreaseEnabled = !isAutoIncreaseEnabled },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🔊  점점 증가",
                            color = if (isAutoIncreaseEnabled) Color.White else Color(0xFF263238),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .shadow(3.dp, RoundedCornerShape(24.dp))
                            .background(
                                brush = if (isManualIncreaseEnabled) {
                                    Brush.verticalGradient(listOf(Color(0xFF9575CD), Color(0xFF5E35B1)))
                                } else {
                                    Brush.verticalGradient(listOf(Color(0xFFE2EAF1), Color(0xFFCFDCE7)))
                                },
                                shape = RoundedCornerShape(24.dp)
                            )
                            .border(
                                1.dp,
                                if (isManualIncreaseEnabled) Color(0xFFB39DDB) else Color(0xFFB0C4D6),
                                RoundedCornerShape(24.dp)
                            )
                            .clickable { showIncreaseDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🎵  수동 증가 ${if (isManualIncreaseEnabled) effectiveTargetCount else 0}",
                            color = if (isManualIncreaseEnabled) Color.White else Color(0xFF263238),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 7) 하단 푸터
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_leaf_decor),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "즐거운 게임 되세요!",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF33691E)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Image(
                        painter = painterResource(id = R.drawable.ic_leaf_decor),
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer(scaleX = -1f)
                    )
                }
            }
        }

        // 전체 화면 물결 파동 드로잉 레이어: 게임 박스 테두리 바깥으로도 자연스럽게 퍼져나감!
        WaterRippleCanvas(
            state = rippleState,
            modifier = Modifier.fillMaxSize(),
        )

        // 전체 화면 점수 및 PERFECT/FAIL 팝업 레이어: 게임 박스 테두리 바깥으로도 자연스럽게 둥실 떠오름!
        ScorePopupOverlay(
            state = scorePopupState,
            modifier = Modifier.fillMaxSize(),
        )
    }


    DisposableEffect(Unit) {
        onDispose {
            bgmManager.release()
            soundPool.release()
        }
    }
}

/**
 * 스마트폰의 중력/가속도 센서를 감지하여, 기기 기울기에 따라 물방울이 수평을 유지하도록 회전 각도를 계산합니다.
 * - 앱이 백그라운드로 나가거나 화면이 꺼지면 센서 리스너를 완전히 해제하여 배터리 누수 방지
 * - 스프링 감쇠 애니메이션(spring)을 적용하여 물처럼 찰랑거리는 부드러운 액체 회전감 제공
 */
@Composable
private fun rememberGravityRotationAngle(): Float {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var rawAngle by remember { mutableFloatStateOf(0f) }

    DisposableEffect(lifecycleOwner, context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var lastAngle = 0f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val gx = event.values[0]
                val gy = event.values[1]

                // 스마트폰 기울기에 따라 물방울 엉덩이(바닥)가 항상 중력 아래쪽을 정확히 가리키도록 회전
                // 좌측 기울임 -> 좌측 대각선, 우측 기울임 -> 우측 대각선, 완전 가로 회전 시에도 뒤집힘 없이 수평 완벽 유지!
                if (gx * gx + gy * gy > 1.2f) {
                    val target = Math.toDegrees(kotlin.math.atan2(gx.toDouble(), gy.toDouble())).toFloat()
                    var diff = target - lastAngle
                    while (diff < -180f) diff += 360f
                    while (diff > 180f) diff -= 360f
                    lastAngle += diff * 0.18f
                    rawAngle = lastAngle
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // 앱을 나갔다 다시 들어왔을 때 센서가 먹통/고정되는 현상을 완벽히 방지하는 생명주기 옵저버
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (sensor != null) {
                        sensorManager?.unregisterListener(listener)
                        sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    sensorManager?.unregisterListener(listener)
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (sensor != null) {
                sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager?.unregisterListener(listener)
        }
    }

    val smoothAngle by animateFloatAsState(
        targetValue = rawAngle,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 160f),
        label = "gravityWaterRotation"
    )

    return smoothAngle
}

/**
 * 사이터스(Cytus) 스타일의 청량하고 영롱한 파란색 숨쉬는 테두리 (Breathing Blue Circle Ring)
 * - 물방울의 맑은 유리 질감과 조화를 이루는 네온 아쿠아 & 사파이어 블루 그라데이션
 * - 물방울 구체보다 약 1mm(2.5dp) 여유를 두어 시원하고 고급스럽게 감싸도록 세밀 조정
 */
@Composable
private fun BreathingBlueRing(
    sizeDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    strokePulse: Float = 6.5f,
    glowAlpha: Float = 0.55f,
) {
    Canvas(modifier = modifier.size(sizeDp)) {
        val center = this.center
        // 물방울 구체(반경 비율 약 0.525)보다 약 1mm(2.5dp) 살짝 여유를 둔 완벽한 테두리 반경
        val currentRadius = (size.minDimension / 2f) * 0.525f + 2.5.dp.toPx()
        val mainStrokePx = strokePulse.dp.toPx()

        // 1) 외부 네온 아쿠아 글로우 (물방울과 어울리는 청량한 푸른빛 아우라)
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = glowAlpha),
            radius = currentRadius,
            center = center,
            style = Stroke(width = mainStrokePx + 4.5.dp.toPx())
        )

        // 2) 메인 파란색 사파이어/아쿠아 그라데이션 링 (#00E5FF -> #2979FF -> #00B0FF -> #1565C0)
        val ringBrush = Brush.sweepGradient(
            colors = listOf(
                Color(0xFF00E5FF), // 네온 아쿠아
                Color(0xFF2979FF), // 일렉트릭 블루
                Color(0xFF00B0FF), // 스카이 블루
                Color(0xFF1565C0), // 딥 사파이어
                Color(0xFF00E5FF)
            ),
            center = center
        )
        drawCircle(
            brush = ringBrush,
            radius = currentRadius,
            center = center,
            style = Stroke(width = mainStrokePx)
        )

        // 3) 안쪽 맑은 아이스 화이트 하이라이트 림
        drawCircle(
            color = Color(0xFFE1F5FE).copy(alpha = 0.92f),
            radius = (currentRadius - mainStrokePx / 2f + 1.dp.toPx()).coerceAtLeast(0f),
            center = center,
            style = Stroke(width = 1.8.dp.toPx())
        )
    }
}

/**
 * 사이터스(Cytus) 스타일의 점진적 성장 및 완전 성장 후 두근두근 숨쉬는 물방울:
 * - 0ms ~ expandDurationMs(750ms): 작은 상태(0.22배)에서 1.0배 크기로 숨쉬듯 부드럽게 팽창
 * - 750ms 이후: 완전 성장 완료! 물방울과 푸른 링이 함께 살아 숨쉬듯 미세하게 두근두근 펄스 (0.975 ~ 1.025)
 * - 물방울을 약 2.5mm(6.5dp) 위로 오프셋 보정하여 링과 물방울이 완벽한 동심원으로 정렬
 * - 스마트폰을 가로/세로로 돌리거나 기울이면 물처럼 제자리에서 찰랑거리며 실시간 수평 회전(gravityAngle)!
 * - GPU 레이어(graphicsLayer) 최적화로 CPU/배터리 소모 0%에 가깝게 매끄럽게 구동
 */
@Composable
private fun CytusBubbleItem(
    target: TouchTarget,
    targetSizeDp: androidx.compose.ui.unit.Dp,
    gravityAngle: Float = 0f,
    expandDurationMs: Long = 750L,
    modifier: Modifier = Modifier
) {
    var ageMs by remember(target.id) {
        mutableLongStateOf(SystemClock.elapsedRealtime() - target.spawnTimeMs)
    }

    LaunchedEffect(target.id, target.spawnTimeMs) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val diff = now - target.spawnTimeMs
            ageMs = diff
            if (diff >= expandDurationMs) {
                break
            }
            delay(16)
        }
    }

    val isFullyGrown = ageMs >= expandDurationMs
    val growProgress = (ageMs.toFloat() / expandDurationMs).coerceIn(0f, 1f)

    // 완전 성장 시 두근두근 살아 숨쉬는 미세 펄스 애니메이션 (하드웨어 가속)
    val infiniteTransition = rememberInfiniteTransition(label = "bubbleHeartbeat")

    val heartbeatScale by infiniteTransition.animateFloat(
        initialValue = 0.975f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartbeatScale"
    )

    val strokePulse by infiniteTransition.animateFloat(
        initialValue = 5.5f,
        targetValue = 8.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "strokePulse"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // 최종 스케일: 커지는 동안은 0.22 -> 1.0, 다 커진 후에는 미세하게 두근두근 숨쉼!
    val finalScale = if (isFullyGrown) {
        heartbeatScale
    } else {
        0.22f + 0.78f * FastOutSlowInEasing.transform(growProgress)
    }

    Box(
        modifier = modifier
            .size(targetSizeDp)
            .graphicsLayer {
                scaleX = finalScale
                scaleY = finalScale
            },
        contentAlignment = Alignment.Center
    ) {
        // 물방울과 푸른 링을 하나의 몸체로 묶어 스마트폰 기울기에 따라 완벽히 일체화되어 함께 수평 회전!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = gravityAngle
                },
            contentAlignment = Alignment.Center
        ) {
            // 1) 완전 성장 시 물방울 테두리를 감싸는 푸른 숨쉬는 링 (물방울과 함께 회전)
            if (isFullyGrown) {
                BreathingBlueRing(
                    sizeDp = targetSizeDp,
                    strokePulse = strokePulse,
                    glowAlpha = glowAlpha,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 2) 물방울 이미지 (2.5mm 위로 보정되어 링과 완벽한 동심원으로 함께 회전)
            Image(
                painter = painterResource(id = R.drawable.btouch),
                contentDescription = "터치 타겟",
                modifier = Modifier
                    .size(targetSizeDp)
                    .offset(y = (-6.5).dp),
                contentScale = ContentScale.Inside
            )
        }
    }
}

@Composable
private fun TouchGameArea(
    modifier: Modifier = Modifier,
    effectiveStage: Int,
    gameScore: Long,
    comboCount: Int,
    gravityAngle: Float,
    touchTargets: List<TouchTarget>,
    popEffects: List<PopEffect>,
    isGamePaused: Boolean,
    popFrames: List<Int>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTargetHit: (TouchTarget, Int) -> Unit,
    onTargetEarlyFail: (TouchTarget) -> Unit,
    onAdvanceGameTime: (Long) -> Unit,
    onPopFinished: (Long) -> Unit,
    rippleState: WaterRippleState,
    scorePopupState: ScorePopupState,
) {
    // 기존 176dp에서 0.7배로 조정한 물방울 타겟 크기 (124dp, 반지름 62dp)
    val targetSize = 124f
    val targetSizeDp = 124.dp
    val expandDurationMs = 750L // 사이터스 스타일 팽창 성장 시간 (0.75초)
    var gameAreaSizePx by remember { mutableStateOf(IntSize.Zero) }

    // pointerInput 코루틴이 재시작되지 않아도 항상 최신 게임 상태/콜백을 보도록 합니다.
    val currentTouchTargets by rememberUpdatedState(touchTargets)
    val currentIsGamePaused by rememberUpdatedState(isGamePaused)
    val currentOnTargetHit by rememberUpdatedState(onTargetHit)
    val currentOnTargetEarlyFail by rememberUpdatedState(onTargetEarlyFail)
    val currentOnAdvanceGameTime by rememberUpdatedState(onAdvanceGameTime)
    val currentScorePopupState by rememberUpdatedState(scorePopupState)
    val currentComboCount by rememberUpdatedState(comboCount)

    BoxWithConstraints(
        modifier = modifier
            .shadow(
                elevation = if (isExpanded) 0.dp else 6.dp,
                shape = if (isExpanded) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp)
            )
            .background(
                color = Color(0xD4FFFDF5), // 뒤쪽 4계절 배경이 은은하게 비치면서도 물방울 대비를 살려주는 반투명 (약 83%)
                shape = if (isExpanded) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp)
            )
            .border(
                width = if (isExpanded) 0.dp else 3.5.dp,
                color = Color(0xCCE2CFA5),
                shape = if (isExpanded) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp)
            )
            .padding(8.dp)
            .onSizeChanged { gameAreaSizePx = it }
    ) {
        val bottomControlReserveDp = if (isExpanded) 48f else 0f
        val gameAreaWidthDp = (this.maxWidth.value - (targetSize + 16f)).coerceAtLeast(0f)
        val gameAreaHeightDp = (
                this.maxHeight.value - (targetSize + 16f + bottomControlReserveDp)
                ).coerceAtLeast(0f)

        // 전체 화면 전환 및 태블릿 회전 시에도 pointerInput 코루틴이 즉시 최신 영역 크기를 반영하도록 상태 캡처
        val currentGameAreaWidthDp by rememberUpdatedState(gameAreaWidthDp)
        val currentGameAreaHeightDp by rememberUpdatedState(gameAreaHeightDp)
        val currentDensity by rememberUpdatedState(LocalDensity.current)

        if (!isExpanded) {
            Image(
                painter = painterResource(id = R.drawable.ic_leaf_decor),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
            )
            Image(
                painter = painterResource(id = R.drawable.ic_leaf_decor),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .graphicsLayer(scaleX = -1f)
            )
            Image(
                painter = painterResource(id = R.drawable.ic_leaf_decor),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .graphicsLayer(scaleY = -1f)
            )
        }

        // 실제 게임 내부 영역의 Root 기준 정확한 좌표 (외부 테두리/마진 오차 완전 제거)
        var internalOffsetOnRoot by remember { mutableStateOf(Offset.Zero) }

        /*
         * 게임 입력 전용 레이어.
         * 전체 화면/축소 전환 시 ���역 크기(gameAreaWidthDp, gameAreaHeightDp)가 바뀌면
         * pointerInput이 즉시 최신 화면 크기에 맞게 동기화되어 태블릿 및 전체화면에서도 정확히 터치됩니다.
         */
        Box(
            modifier = Modifier
                .matchParentSize()
                .onGloballyPositioned { coordinates ->
                    internalOffsetOnRoot = coordinates.positionInRoot()
                }
                .pointerInput(gameAreaWidthDp, gameAreaHeightDp) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val claimedTargetIds = mutableSetOf<Long>()

                            event.changes.forEach { change ->
                                if (change.pressed && !change.previousPressed) {
                                    if (currentIsGamePaused) return@forEach

                                    val xPx = change.position.x
                                    val yPx = change.position.y

                                    val targetSizePx = with(currentDensity) { targetSizeDp.toPx() }

                                    // 물방울 및 푸른 링 반경(약 35.5dp)에 손가락 터치 여유를 둔 44dp 정밀 타격 반경
                                    val touchRadiusPx = with(currentDensity) { 44.dp.toPx() }
                                    val touchRadiusSq = touchRadiusPx * touchRadiusPx

                                    // 화면에 렌더링된 물방울 위치와 100% 동일한 최신 수식으로 중심점 계산
                                    val hitResult = currentTouchTargets
                                        .filter { target -> target.id !in claimedTargetIds }
                                        .mapNotNull { target ->
                                            val targetLeftPx = with(currentDensity) {
                                                (target.x * currentGameAreaWidthDp).dp.toPx()
                                            }
                                            val targetTopPx = with(currentDensity) {
                                                (target.y * currentGameAreaHeightDp).dp.toPx()
                                            }
                                            val centerX = targetLeftPx + targetSizePx / 2f
                                            val centerY = targetTopPx + targetSizePx / 2f

                                            val dx = xPx - centerX
                                            val dy = yPx - centerY
                                            val distSq = dx * dx + dy * dy

                                            if (distSq <= touchRadiusSq) {
                                                Triple(target, distSq, Offset(centerX, centerY))
                                            } else {
                                                null
                                            }
                                        }
                                        .minByOrNull { it.second } // 터치한 곳과 가장 가까운 물방울 1개만 정밀 타격!

                                    if (hitResult != null) {
                                        val (hitTarget, _, targetCenter) = hitResult
                                        claimedTargetIds += hitTarget.id

                                        val now = SystemClock.elapsedRealtime()
                                        val ageMs = now - hitTarget.spawnTimeMs
                                        val isPerfect = ageMs >= expandDurationMs

                                        val globalTouchPos = internalOffsetOnRoot + Offset(xPx, yPx)

                                        if (isPerfect) {
                                            val nextCombo = currentComboCount + 1
                                            // 기본 10점 + 콤보 보너스(콤보당 +2점, 최대 +50점)에 PERFECT 1.5배 배율 적용 (적당하고 현실적인 점수 밸런스)
                                            val baseScore = 10 + (nextCombo * 2).coerceAtMost(50)
                                            val gainedScore = (baseScore * 1.5f).toInt()

                                            // 1) 사이터스 PERFECT 적중! (적당하고 자연스러운 점수 팝업)
                                            currentScorePopupState.spawnPerfectPopup(
                                                position = globalTouchPos,
                                                comboScore = gainedScore,
                                            )

                                            // 터치 즉시 물결 파동 발생
                                            val rippleCenterInRoot = internalOffsetOnRoot + targetCenter
                                            rippleState.spawnRipple(
                                                center = rippleCenterInRoot,
                                                maxRadius = 250.dp,
                                                durationMs = 950,
                                                waveColor = Color(0xFF0091EA),
                                            )

                                            // 7단계 고속 진입을 위한 시간 단축 보너스 (기본 +5초, 10콤보 이상 +8초 앞당김)
                                            val timeAdvance = if (nextCombo >= 10) 8000L else 5000L
                                            currentOnAdvanceGameTime(timeAdvance)

                                            currentOnTargetHit(hitTarget, gainedScore)
                                        } else {
                                            // 2) 아직 작을 때 조기 터치 -> FAIL!
                                            currentScorePopupState.spawnFailPopup(
                                                position = globalTouchPos,
                                            )
                                            currentOnTargetEarlyFail(hitTarget)
                                        }
                                    }

                                    change.consume()
                                }
                            }
                        }
                    }
                }
        )

        // 박스 내부 좌측 상단에 은은하고 흐릿하게 표시되는 콤보 누적 점수 & 콤보 (상단 글자 겹침 완전 해결)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 10.dp)
                .graphicsLayer { alpha = 0.45f }
        ) {
            Text(
                text = "점수  ${String.format(java.util.Locale.US, "%,d", gameScore)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1565C0)
            )
            if (comboCount > 0) {
                Text(
                    text = "콤보  $comboCount",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE65100)
                )
            }
        }

        // 터치 안내 문구를 박스 하단으로 이동하여 상단 점수와의 겹침을 없애고 은은하게 흐릿하게 개선
        Text(
            text = "터치 하는 곳 둥근 원을 터치 하세요",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isExpanded) 52.dp else 14.dp)
                .graphicsLayer { alpha = 0.35f },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1E3A5F)
        )

        Text(
            text = effectiveStage.toString(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 12.dp,
                    bottom = if (isExpanded) 40.dp else 12.dp
                )
                .shadow(3.dp, RoundedCornerShape(12.dp))
                .background(
                    color = Color(0xFF5B8C51),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 6.dp),
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold
        )

        touchTargets.forEach { target ->
            Box(
                modifier = Modifier
                    .size(targetSizeDp)
                    .offset(
                        x = (target.x * gameAreaWidthDp).dp,
                        y = (target.y * gameAreaHeightDp).dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                CytusBubbleItem(
                    target = target,
                    targetSizeDp = targetSizeDp,
                    gravityAngle = gravityAngle,
                    expandDurationMs = expandDurationMs,
                )
            }
        }

        popEffects.forEach { effect ->
            Box(
                modifier = Modifier
                    .size(targetSizeDp)
                    .offset(
                        x = (effect.x * gameAreaWidthDp).dp,
                        y = (effect.y * gameAreaHeightDp).dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                PopBurst(
                    frames = popFrames,
                    baseSize = 148.dp, // 물방울 0.7배 축소에 맞춘 터짐 효과 크기 (기존 211dp * 0.7)
                    onFinished = {
                        onPopFinished(effect.id)
                    }
                )
            }
        }

        if (isExpanded) {
            // 확대 화면의 하단 전체를 누르기 쉬운 '축소' 텍스트 버튼 영역으로 사용합니다.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .shadow(4.dp, RoundedCornerShape(20.dp))
                    .background(Color(0xEEFFFFFF), RoundedCornerShape(20.dp))
                    .border(1.5.dp, Color(0xFF90CAF9), RoundedCornerShape(20.dp))
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⛶  축소",
                    color = Color(0xFF1E3A5F),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}





@Composable
fun PopBurst(
    frames: List<Int>,
    baseSize: androidx.compose.ui.unit.Dp,
    frameDurationMs: Long = 35L,
    onFinished: () -> Unit
) {
    // 프레임 애니메이션 + 스케일/알파 전환
    var frameIndex by remember { mutableIntStateOf(0) }

    val scale = remember { Animatable(0.85f) }
    val alpha = remember { Animatable(1f) }

    val totalDurationMs = (frames.size * frameDurationMs).coerceAtLeast(1L)

    LaunchedEffect(frames) {
        // 1) 스케일: 살짝 커졌다가 줄어드는 “팝” 느낌
        launch {
            scale.animateTo(
                targetValue = 1.15f,
                animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing)
            )
            scale.animateTo(
                targetValue = 0.95f,
                animationSpec = tween(
                    durationMillis = (totalDurationMs - 80L).toInt().coerceAtLeast(60),
                    easing = FastOutSlowInEasing
                )
            )
        }

        // 2) 알파: 빠르게 사라지도록
        launch {
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = totalDurationMs.toInt(), easing = LinearEasing)
            )
        }

        // 3) 프레임 전환
        for (i in frames.indices) {
            frameIndex = i
            delay(frameDurationMs)
        }

        onFinished()
    }

    Box(
        modifier = Modifier
            .size(baseSize)
            .graphicsLayer(
                scaleX = scale.value,
                scaleY = scale.value,
                alpha = alpha.value
            ),
        contentAlignment = Alignment.Center
    ) {
        // Sprite frame
        val safeIndex = frameIndex.coerceIn(0, frames.lastIndex)
        Image(
            painter = painterResource(id = frames[safeIndex]),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}