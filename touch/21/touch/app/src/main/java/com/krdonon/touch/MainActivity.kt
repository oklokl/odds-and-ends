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
    val y: Float
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

private fun stageFromElapsedMs(elapsedMs: Long): Int {
    // 40초(40,000ms) 단위로 1단계씩 상승. 시작은 1단계.
    val step = (elapsedMs / 40_000L).toInt()
    return (step + 1).coerceIn(1, 140)
}

/**
 * 속도 조절(전체적으로 "현재보다 반절 정도 느리게"):
 * - (이전 4배 빠른 버전 대비) 생성 간격/유지 시간을 2배로 늘림
 * - 단, 고단계는 여전히 사람 손으로 버티기 힘들 정도로 빠르게 하한값 유지
 */
private fun spawnDelayRangeMs(stage: Int): LongRange {
    val s = stage.coerceIn(0, 140)
    val expSteps = (s - 1).coerceAtLeast(0)

    // 1단계 기준: 1.0 ~ 2.0초 (이전 0.5~1.0초에서 반절 느리게)
    val factor = 0.985.pow(expSteps.toDouble())
    val min = (1000L * factor).toLong().coerceAtLeast(120L)
    val max = (2000L * factor).toLong().coerceAtLeast(min + 120L)

    return min..max
}

private fun targetLifetimeMs(stage: Int): Long {
    val s = stage.coerceIn(0, 140)
    val expSteps = (s - 1).coerceAtLeast(0)

    // 1단계 기준: 1.3초 (이전 0.65초에서 반절 느리게), 고단계는 0.35초까지
    val factor = 0.993.pow(expSteps.toDouble())
    return (1300L * factor).toLong().coerceAtLeast(350L)
}

/**
 * 동시 등장 물방울이 늘어났을 때(점점 증가 / 수동 증가 사용 시)
 * 터치할 수 있는 시간을 아주 미세하게 늘려준다.
 * - 2개: +0.2초
 * - 3개: +0.3초
 * - 4개: +0.4초
 */
private fun extraLifetimeMsForTargetCount(targetCount: Int): Long {
    return when (targetCount.coerceIn(1, 4)) {
        2 -> 200L
        3 -> 300L
        4 -> 400L
        else -> 0L
    }
}

/**
 * 한 묶음 안에서 다음 물방울 묶음이 나타나기까지의 간격.
 * 낮은 단계에서는 리듬감이 느껴지도록 조금 여유를 두고,
 * 단계가 높아질수록 자연스럽게 간격도 짧아집니다.
 */
private fun targetStaggerDelayRangeMs(stage: Int): LongRange {
    val s = stage.coerceIn(0, 140)
    val expSteps = (s - 1).coerceAtLeast(0)
    val factor = 0.99.pow(expSteps.toDouble())

    // 1단계 기준 약 0.18 ~ 0.48초, 고단계에서는 최소 0.06초 수준 유지
    val min = (180L * factor).toLong().coerceAtLeast(60L)
    val max = (480L * factor).toLong().coerceAtLeast(min + 70L)
    return min..max
}

private fun randomTargetStaggerDelayMs(stage: Int): Long {
    val range = targetStaggerDelayRangeMs(stage)
    if (range.first == range.last) return range.first

    val base = Random.nextLong(range.first, range.last + 1)
    // 같은 박자로만 들리지 않도록 가끔 짧거나 긴 간격을 섞습니다.
    val varied = when (Random.nextInt(6)) {
        0 -> (base * 0.72).toLong()
        5 -> (base * 1.18).toLong()
        else -> base
    }
    return varied.coerceIn(50L, 650L)
}

/**
 * 한 번에 최대 4개가 모두 동시에 뜨지 않도록 등장 패턴을 만든다.
 * 예) 4개 설정: 1-1-1-1, 2-1-1, 1-2-1, 2-2, 3-1 등
 * 각 숫자는 그 순간 함께 나타나는 물방울 개수입니다.
 */
private fun buildTargetSpawnBatches(targetCount: Int): List<Int> {
    return when (targetCount.coerceIn(1, 4)) {
        1 -> listOf(1)
        2 -> listOf(1, 1)
        3 -> listOf(
            listOf(1, 1, 1),
            listOf(2, 1),
            listOf(1, 2),
            listOf(1, 1, 1)
        ).random()
        4 -> listOf(
            listOf(1, 1, 1, 1),
            listOf(2, 1, 1),
            listOf(1, 2, 1),
            listOf(1, 1, 2),
            listOf(2, 2),
            listOf(3, 1),
            listOf(1, 3),
            listOf(2, 1, 1)
        ).random()
        else -> listOf(1)
    }
}


/**
 * 단계(7단위) 증가에 따라 동시 등장 물방울 개수 증가.
 * - 1~6단계: 1개
 * - 7~13단계: 2개
 * - 14~20단계: 3개
 * - 21단계~: 4개(최대)
 */
private fun targetCountFromStage(stage: Int): Int {
    val s = stage.coerceAtLeast(0)
    return (1 + (s / 7)).coerceIn(1, 4)
}

/**
 * 동시 등장 타겟을 만들 때 너무 겹치지 않도록 간단한 거리 제약을 둔다.
 * 좌표는 0~1 정규화 값이며, minDistance는 정규화 거리 기준.
 */
private fun generateTargets(count: Int, minDistance: Float = 0.18f): List<TouchTarget> {
    if (count <= 0) return emptyList()

    val result = mutableListOf<TouchTarget>()
    val minDist2 = minDistance * minDistance
    val baseId = SystemClock.elapsedRealtimeNanos()

    repeat(count.coerceIn(1, 4)) { idx ->
        var x = Random.nextFloat()
        var y = Random.nextFloat()
        var attempt = 0
        while (attempt < 40 && result.any { t ->
                val dx = t.x - x
                val dy = t.y - y
                (dx * dx + dy * dy) < minDist2
            }
        ) {
            x = Random.nextFloat()
            y = Random.nextFloat()
            attempt += 1
        }

        result += TouchTarget(
            id = baseId + idx,
            x = x,
            y = y
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
    var showStageDialog by remember { mutableStateOf(false) }

    // 물방울(타겟) 동시 등장 개수 옵션
    // - 점점 증가: 단계가 올라가면 7단계마다 1개씩(최대 4개)
    // - 수동 증가: 현재 단계에서 2~4개로 고정(우선 적용)
    var isAutoIncreaseEnabled by rememberSaveable { mutableStateOf(false) } // "점점 증가"
    var isManualIncreaseEnabled by rememberSaveable { mutableStateOf(false) }
    var manualTargetCount by rememberSaveable { mutableIntStateOf(2) } // 수동 증가 시 2~4
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

    // 터치 터짐(스프라이트) 프레임들 - res/drawable-nodpi 에 touchimage1~5.png 를 둡니다.
    // (리소스 이름 규칙: 소문자/숫자/언더바만 가능)
    val popFrames = remember {
        listOf(
            R.drawable.touchimage1,
            R.drawable.touchimage2,
            R.drawable.touchimage3,
            R.drawable.touchimage4,
            R.drawable.touchimage5
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

    // 게임 진행 시간 업데이트 (일시정지 시 멈춤, 100ms 주기로 Recomposition 렉 최적화)
    LaunchedEffect(isGamePaused, resetToken) {
        if (!isGamePaused) {
            lastStartRealtimeMs = SystemClock.elapsedRealtime()
            while (!isGamePaused) {
                val now = SystemClock.elapsedRealtime()
                elapsedMs = accumulatedMs + (now - lastStartRealtimeMs)
                delay(100)
            }
            accumulatedMs = elapsedMs
        }
    }

    val autoStage = remember(isAutoDifficultyEnabled, elapsedMs / 1000L) {
        if (isAutoDifficultyEnabled) stageFromElapsedMs(elapsedMs) else 1
    }

    val effectiveStage: Int = remember(
        isManualStageEnabled,
        manualStage,
        isAutoDifficultyEnabled,
        autoStage,
    ) {
        when {
            isManualStageEnabled -> manualStage.coerceIn(0, 140)
            isAutoDifficultyEnabled -> autoStage
            else -> 1
        }
    }

    val effectiveTargetCount: Int = remember(
        isManualIncreaseEnabled,
        manualTargetCount,
        isAutoIncreaseEnabled,
        effectiveStage,
    ) {
        when {
            isManualIncreaseEnabled -> manualTargetCount.coerceIn(2, 4)
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
            val countForThisSpawn = currentEffectiveTargetCount.coerceIn(1, 4)

            // 물방울 하나가 실제로 화면에 나타난 시점부터 각자의 유지 시간을 계산합니다.
            val lifetimeForThisSpawn = targetLifetimeMs(stageForThisSpawn) +
                    extraLifetimeMsForTargetCount(countForThisSpawn)

            val delayRange = spawnDelayRangeMs(stageForThisSpawn)
            val nextDelay = if (delayRange.first == delayRange.last) delayRange.first
            else Random.nextLong(delayRange.first, delayRange.last + 1)

            delay(nextDelay)

            if (isGamePaused) break
            if (touchTargets.isNotEmpty()) continue

            // 좌표는 웨이브 시작 시 전부 미리 만들어 서로 너무 겹치지 않게 유지하되,
            // 화면에는 batch 계획에 따라 조금씩 늦춰서 공개합니다.
            val waveTargets = generateTargets(countForThisSpawn)
            val spawnBatches = buildTargetSpawnBatches(countForThisSpawn)
            var nextTargetIndex = 0

            for ((batchIndex, batchSize) in spawnBatches.withIndex()) {
                if (batchIndex > 0) {
                    delay(randomTargetStaggerDelayMs(stageForThisSpawn))
                }

                if (isGamePaused) break

                val batchEnd = (nextTargetIndex + batchSize).coerceAtMost(waveTargets.size)
                val targetsToReveal = waveTargets.subList(nextTargetIndex, batchEnd)
                nextTargetIndex = batchEnd

                if (targetsToReveal.isNotEmpty()) {
                    touchTargets = touchTargets + targetsToReveal

                    targetsToReveal.forEach { target ->
                        val targetId = target.id
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
        }
    }

    if (showStageDialog) {
        var pickerValue by remember { mutableIntStateOf(manualStage.coerceIn(0, 140)) }
        var manualEnabled by remember { mutableStateOf(isManualStageEnabled) }
        AlertDialog(
            onDismissRequest = { showStageDialog = false },
            title = { Text("단계 수동 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "0 ~ 140 단계 선택",
                        fontSize = 14.sp
                    )
                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx).apply {
                                minValue = 0
                                maxValue = 140
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
        var pickerValue by remember { mutableIntStateOf(manualTargetCount.coerceIn(2, 4)) }
        var manualEnabled by remember { mutableStateOf(isManualIncreaseEnabled) }

        AlertDialog(
            onDismissRequest = { showIncreaseDialog = false },
            title = { Text("수동 증가 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "동시에 등장할 물방울 개수 (2 ~ 4)",
                        fontSize = 14.sp
                    )

                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx).apply {
                                minValue = 2
                                maxValue = 4
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
                        manualTargetCount = pickerValue.coerceIn(2, 4)
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


    fun handleTargetHit(hitTarget: TouchTarget) {
        // 같은 타겟이 이미 처리된 뒤 들어온 중복 터치는 무시합니다.
        if (touchTargets.none { it.id == hitTarget.id }) return

        touchCount += 1
        comboCount += 1
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

    // 확대 상태에서 시스템 뒤로가기를 누르면 앱을 종료하지 않고 원래 화면으로 복귀합니다.
    BackHandler(enabled = isGameAreaExpanded) {
        isGameAreaExpanded = false
    }

    val bgRes = if (layoutMode == LayoutMode.TABLET_LANDSCAPE) {
        R.drawable.bg_landscape
    } else {
        R.drawable.bg_portrait
    }

    // 전체 화면 물결 파동 상태 관리자 (게임 보드 밖으로도 파동이 넘치도록 화면 레벨에서 관리)
    val rippleState = rememberWaterRippleState()
    var gameAreaOffsetOnScreen by remember { mutableStateOf(Offset.Zero) }

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
                                Text(
                                    text = formatElapsedTime(elapsedMs),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F325E)
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
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            gameAreaOffsetOnScreen = coordinates.positionInRoot()
                        }
                } else {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onGloballyPositioned { coordinates ->
                            gameAreaOffsetOnScreen = coordinates.positionInRoot()
                        }
                },
                effectiveStage = effectiveStage,
                comboCount = comboCount,
                touchTargets = touchTargets,
                popEffects = popEffects,
                isGamePaused = isGamePaused,
                popFrames = popFrames,
                isExpanded = isGameAreaExpanded,
                onToggleExpanded = { isGameAreaExpanded = !isGameAreaExpanded },
                onTargetHit = ::handleTargetHit,
                onPopFinished = { effectId ->
                    popEffects = popEffects.filterNot { it.id == effectId }
                },
                rippleState = rippleState,
                gameAreaOffsetOnScreen = gameAreaOffsetOnScreen,
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
                                isAutoIncreaseEnabled = false
                                isManualIncreaseEnabled = false
                                manualTargetCount = 2
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
                            text = "📊  단계 수동 $manualStage",
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
                            text = "🎵  수동 증가 ${if (isManualIncreaseEnabled) manualTargetCount else 0}",
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
    }


    DisposableEffect(Unit) {
        onDispose {
            bgmManager.release()
            soundPool.release()
        }
    }
}

@Composable
private fun TouchGameArea(
    modifier: Modifier = Modifier,
    effectiveStage: Int,
    comboCount: Int,
    touchTargets: List<TouchTarget>,
    popEffects: List<PopEffect>,
    isGamePaused: Boolean,
    popFrames: List<Int>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTargetHit: (TouchTarget) -> Unit,
    onPopFinished: (Long) -> Unit,
    rippleState: WaterRippleState,
    gameAreaOffsetOnScreen: Offset,
) {
    // 실제 터치 판정 박스는 이미지보다 크게 유지해 터치 민감도를 보존합니다.
    val hitBoxSize = 130f
    val imageSize = 80.dp
    var gameAreaSizePx by remember { mutableStateOf(IntSize.Zero) }

    val density = LocalDensity.current

    // 점수 팝업 애니메이션 상태 관리
    val scorePopupState = rememberScorePopupState()

    // pointerInput 코루틴이 재시작되지 않아도 항상 최신 게임 상태/콜백을 보도록 합니다.
    val currentTouchTargets by rememberUpdatedState(touchTargets)
    val currentIsGamePaused by rememberUpdatedState(isGamePaused)
    val currentOnTargetHit by rememberUpdatedState(onTargetHit)
    val currentComboCount by rememberUpdatedState(comboCount)

    BoxWithConstraints(
        modifier = modifier
            .shadow(
                elevation = if (isExpanded) 0.dp else 6.dp,
                shape = if (isExpanded) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp)
            )
            .background(
                color = Color(0xFFFFFDF5),
                shape = if (isExpanded) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp)
            )
            .border(
                width = if (isExpanded) 0.dp else 3.5.dp,
                color = Color(0xFFE2CFA5),
                shape = if (isExpanded) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp)
            )
            .padding(8.dp)
            .onSizeChanged { gameAreaSizePx = it }
    ) {
        val bottomControlReserveDp = if (isExpanded) 36f else 0f
        val gameAreaWidthDp = (this.maxWidth.value - (hitBoxSize + 16f)).coerceAtLeast(0f)
        val gameAreaHeightDp = (
                this.maxHeight.value - (hitBoxSize + 16f + bottomControlReserveDp)
                ).coerceAtLeast(0f)

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

        /*
         * 게임 입력 전용 레이어.
         * 전체 화면/축소 버튼은 별도 sibling으로 위에 그려서 버튼 터치가
         * 게임 영역의 pointerInput에 먹히지 않도록 분리합니다.
         *
         * 타겟 좌표는 0~1 정규화 값이라 화면이 커지거나 태블릿으로 바뀌어도
         * 현재 실제 영역 크기에 맞춰 자동으로 다시 배치됩니다.
         */
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val claimedTargetIds = mutableSetOf<Long>()

                            event.changes.forEach { change ->
                                if (change.pressed && !change.previousPressed) {
                                    if (currentIsGamePaused) return@forEach

                                    val xPx = change.position.x
                                    val yPx = change.position.y

                                    val hitBoxPx = hitBoxSize.dp.toPx()
                                    val marginPx = 16.dp.toPx()
                                    val bottomControlReservePx =
                                        if (isExpanded) 36.dp.toPx() else 0f

                                    val wPx = (
                                            gameAreaSizePx.width.toFloat() - (hitBoxPx + marginPx)
                                            ).coerceAtLeast(0f)
                                    val hPx = (
                                            gameAreaSizePx.height.toFloat() -
                                                    (hitBoxPx + marginPx + bottomControlReservePx)
                                            ).coerceAtLeast(0f)

                                    val hitTarget = currentTouchTargets.firstOrNull { target ->
                                        target.id !in claimedTargetIds && run {
                                            val tx = target.x * wPx
                                            val ty = target.y * hPx
                                            xPx >= tx && xPx <= (tx + hitBoxPx) &&
                                                    yPx >= ty && yPx <= (ty + hitBoxPx)
                                        }
                                    }

                                    if (hitTarget != null) {
                                        claimedTargetIds += hitTarget.id
                                        scorePopupState.spawnScorePopup(
                                            position = Offset(xPx, yPx),
                                            comboScore = currentComboCount + 1,
                                        )
                                        currentOnTargetHit(hitTarget)
                                    }

                                    change.consume()
                                }
                            }
                        }
                    }
                }
        )

        Text(
            text = "터치 하는 곳 둥근 원을 터치 하세요",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
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
                    .size(hitBoxSize.dp)
                    .offset(
                        x = (target.x * gameAreaWidthDp).dp,
                        y = (target.y * gameAreaHeightDp).dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.touch),
                    contentDescription = "터치 타겟",
                    modifier = Modifier.size(imageSize),
                    contentScale = ContentScale.Inside
                )
            }
        }

        popEffects.forEach { effect ->
            Box(
                modifier = Modifier
                    .size(hitBoxSize.dp)
                    .offset(
                        x = (effect.x * gameAreaWidthDp).dp,
                        y = (effect.y * gameAreaHeightDp).dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                PopBurst(
                    frames = popFrames,
                    baseSize = 96.dp,
                    onFinished = {
                        // 물방울 애니메이션이 종료될 때 전체 화면 좌표 기준 물결 파동 발생 (박스 바깥으로 자연스럽게 번져나감)
                        val effectCenterX = with(density) {
                            (effect.x * gameAreaWidthDp + hitBoxSize / 2f).dp.toPx()
                        }
                        val effectCenterY = with(density) {
                            (effect.y * gameAreaHeightDp + hitBoxSize / 2f).dp.toPx()
                        }

                        val globalCenter = gameAreaOffsetOnScreen + Offset(effectCenterX, effectCenterY)

                        rippleState.spawnRipple(
                            center = globalCenter,
                            maxRadius = 250.dp,
                            durationMs = 950,
                            waveColor = Color(0xFF0091EA),
                        )

                        onPopFinished(effect.id)
                    }
                )
            }
        }

        // 터치 위치 점수 팝업 레이어 (물방울 및 파동 상단에 표시)
        ScorePopupOverlay(
            state = scorePopupState,
            modifier = Modifier.matchParentSize(),
        )

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