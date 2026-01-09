package com.krdonon.touch

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Bundle
import android.os.SystemClock
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TouchTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = Color(0xFF4CAF50)
                ) {
                    TouchGameScreen()
                }
            }
        }
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
fun TouchGameScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 게임 진행 시간 (일시정지 시 시간 정지)
    var elapsedMs by rememberSaveable { mutableLongStateOf(0L) }
    var accumulatedMs by rememberSaveable { mutableLongStateOf(0L) }
    var lastStartRealtimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    var touchCount by rememberSaveable { mutableIntStateOf(0) }
    var failCount by rememberSaveable { mutableIntStateOf(0) }
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

    val bgmPlayer = remember {
        try {
            MediaPlayer.create(context, R.raw.sstouch).apply {
                setVolume(0.5f, 0.5f)
                isLooping = true
                start()
            }
        } catch (_: Exception) {
            null
        }
    }

    val soundPool = remember {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(audioAttributes)
            .build()
    }

    val soundId = remember {
        try {
            soundPool.load(context, R.raw.sound, 1)
        } catch (_: Exception) {
            -1
        }
    }

    // 터치 터짐(스프라이트) 프레임들 - res/drawable 에 touchimage1~5.png 를 넣으면 됩니다.
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
        if (isBgmEnabled) {
            bgmPlayer?.start()
        } else {
            bgmPlayer?.pause()
        }
    }

    // 앱이 백그라운드로 가면 자동 일시정지 / 복귀 시 자동 재개(자동 일시정지였던 경우만)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    bgmPlayer?.pause()
                    // 백그라운드로 나갈 때 현재까지 진행된 시간을 고정(복귀 시 0으로 리셋되는 현상 방지)
                    accumulatedMs = elapsedMs
                    wasAutoPaused = !isGamePaused
                    isGamePaused = true
                }
                Lifecycle.Event.ON_STOP -> {
                    // 프로세스가 정리되더라도 진행 시간/카운터가 복원될 수 있도록 저장값을 고정
                    accumulatedMs = elapsedMs
                }

                Lifecycle.Event.ON_RESUME -> {
                    if (isBgmEnabled) bgmPlayer?.start()
                    if (wasAutoPaused) {
                        isGamePaused = false
                        wasAutoPaused = false
                    }
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 게임 진행 시간 업데이트 (일시정지 시 멈춤)
    LaunchedEffect(isGamePaused, resetToken) {
        if (!isGamePaused) {
            lastStartRealtimeMs = SystemClock.elapsedRealtime()
            while (!isGamePaused) {
                val now = SystemClock.elapsedRealtime()
                elapsedMs = accumulatedMs + (now - lastStartRealtimeMs)
                delay(50)
            }
            accumulatedMs = elapsedMs
        }
    }

    val effectiveStage: Int = remember(
        isManualStageEnabled,
        manualStage,
        isAutoDifficultyEnabled,
        elapsedMs
    ) {
        when {
            isManualStageEnabled -> manualStage.coerceIn(0, 140)
            isAutoDifficultyEnabled -> stageFromElapsedMs(elapsedMs)
            else -> 1
        }
    }

    val effectiveTargetCount: Int = remember(
        isManualIncreaseEnabled,
        manualTargetCount,
        isAutoIncreaseEnabled,
        effectiveStage
    ) {
        when {
            isManualIncreaseEnabled -> manualTargetCount.coerceIn(2, 4)
            isAutoIncreaseEnabled -> targetCountFromStage(effectiveStage)
            else -> 1
        }
    }

    // 타겟 생성 (현재 동시 등장 개수만큼 한 번에 생성)
    LaunchedEffect(isGamePaused, resetToken, effectiveStage, effectiveTargetCount) {
        while (!isGamePaused) {
            // 이미 화면에 물방울이 있으면 새로 만들지 않음 (모두 없어질 때까지 대기)
            if (touchTargets.isNotEmpty()) {
                delay(20)
                continue
            }

            val stageForThisSpawn = effectiveStage
            val countForThisSpawn = effectiveTargetCount.coerceIn(1, 4)

            // 동시 등장 개수(점점 증가/수동 증가)에 따라 사라지는 시간을 아주 미세하게 보정
            val lifetimeForThisSpawn = targetLifetimeMs(stageForThisSpawn) +
                    extraLifetimeMsForTargetCount(countForThisSpawn)

            val delayRange = spawnDelayRangeMs(stageForThisSpawn)
            val nextDelay = if (delayRange.first == delayRange.last) delayRange.first
            else Random.nextLong(delayRange.first, delayRange.last + 1)

            delay(nextDelay)

            if (isGamePaused) break
            if (touchTargets.isNotEmpty()) continue

            val newTargets = generateTargets(countForThisSpawn)
            touchTargets = newTargets

            newTargets.forEach { target ->
                val targetId = target.id
                launch {
                    delay(lifetimeForThisSpawn)
                    if (!isGamePaused && touchTargets.any { it.id == targetId }) {
                        touchTargets = touchTargets.filterNot { it.id == targetId }
                        failCount++
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


    Column(
        modifier = Modifier
            .fillMaxSize()
            // 하단 버튼이 네비게이션 바 바로 위에 딱 맞게 오도록
            // (불필요한 하단 여백을 제거하여 터치 영역을 최대화)
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp)
    ) {
        // 상단: 게임 진행 시간 + BGM 스위치
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xFFB3E5FC),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "게임 진행 시간",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = formatElapsedTime(elapsedMs),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                )
            }

            Switch(
                checked = isBgmEnabled,
                onCheckedChange = { isBgmEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF4CAF50),
                    checkedTrackColor = Color(0xFFA5D6A7),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.LightGray
                )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            InfoBox(
                title = "버튼 누른 횟수",
                value = touchCount.toString(),
                backgroundColor = Color(0xFFFFEB3B),
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            InfoBox(
                title = "실패 횟수",
                value = failCount.toString(),
                backgroundColor = Color.White,
                modifier = Modifier.weight(1f)
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    color = Color(0xFFB2FBA5),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            // 터치 민감도 향상: 실제 터치 판정 박스를 이미지보다 크게
            val hitBoxSize = 130f   // dp
            val imageSize = 80.dp

            val gameAreaWidthDp = (this.maxWidth.value - (hitBoxSize + 16f)).coerceAtLeast(0f)
            val gameAreaHeightDp = (this.maxHeight.value - (hitBoxSize + 16f)).coerceAtLeast(0f)

            Text(
                text = "터치 하는 곳 둥근 원을 터치 하세요",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            // 현재 단계 표시 (하단 우측)
            Text(
                text = effectiveStage.toString(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp)
                    .background(
                        color = Color(0x66000000),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold
            )

            touchTargets.forEach { target ->
                // "스치기만 해도" 잡히도록:
                // - 히트박스를 크게(130dp)
                // - onTap이 아니라 onPress에서 즉시 처리(손 떼기 전에 등록)
                Box(
                    modifier = Modifier
                        .size(hitBoxSize.dp)
                        .offset(
                            x = (target.x * gameAreaWidthDp).dp,
                            y = (target.y * gameAreaHeightDp).dp
                        )
                        .pointerInput(target.id) {
                            detectTapGestures(
                                onPress = {
                                    // 이미 사라졌으면 무시
                                    if (touchTargets.any { it.id == target.id }) {
                                        touchCount++
                                        touchTargets = touchTargets.filterNot { it.id == target.id }

                                        // 터짐 이펙트(프레임 + 간단한 스케일/알파/리플)
                                        popEffects = popEffects + PopEffect(
                                            id = SystemClock.elapsedRealtimeNanos(),
                                            x = target.x,
                                            y = target.y
                                        )

                                        if (soundId != -1) {
                                            soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
                                        }
                                    }
                                    // 제스처 종료까지 기다리되, 판정은 이미 끝
                                    tryAwaitRelease()
                                }
                            )
                        },
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

            // 터치 터짐 이펙트(프레임 + 간단한 이펙트)
            popEffects.forEach { effect ->
                val effectId = effect.id
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
                            popEffects = popEffects.filterNot { it.id == effectId }
                        }
                    )
                }
            }

        }

        // 기존: 일시정지 / 초기화
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { isGamePaused = !isGamePaused },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800)
                )
            ) {
                Text(
                    text = if (isGamePaused) "재생" else "일시 정지",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = {
                    touchCount = 0
                    failCount = 0
                    touchTargets = emptyList()
                    isGamePaused = false

                    accumulatedMs = 0L
                    elapsedMs = 0L
                    lastStartRealtimeMs = SystemClock.elapsedRealtime()

                    // 요청: 시작은 자동 난이도 OFF(회색)
                    isAutoDifficultyEnabled = false
                    isManualStageEnabled = false
                    manualStage = 0

                    // 시작은 동시 등장 증가 OFF
                    isAutoIncreaseEnabled = false
                    isManualIncreaseEnabled = false
                    manualTargetCount = 2
                    showIncreaseDialog = false

                    resetToken++
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196F3)
                )
            ) {
                Text(
                    text = "초기화",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 신규: 점점 어렵게 / 단계 수동
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { isAutoDifficultyEnabled = !isAutoDifficultyEnabled },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAutoDifficultyEnabled) Color(0xFF8E24AA) else Color(0xFFBDBDBD)
                )
            ) {
                Text(
                    text = "점점 어렵게",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAutoDifficultyEnabled) Color.White else Color.Black
                )
            }

            Button(
                onClick = { showStageDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isManualStageEnabled) Color(0xFF00796B) else Color(0xFF90A4AE)
                )
            ) {
                Text(
                    text = "단계 수동 $manualStage",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // 신규: 점점 증가 / 수동 증가
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { isAutoIncreaseEnabled = !isAutoIncreaseEnabled },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAutoIncreaseEnabled) Color(0xFF2E7D32) else Color(0xFFBDBDBD)
                )
            ) {
                Text(
                    text = "점점 증가",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAutoIncreaseEnabled) Color.White else Color.Black
                )
            }

            Button(
                onClick = { showIncreaseDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isManualIncreaseEnabled) Color(0xFF5E35B1) else Color(0xFFBDBDBD)
                )
            ) {
                Text(
                    text = "수동 증가 ${if (isManualIncreaseEnabled) manualTargetCount else 0}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isManualIncreaseEnabled) Color.White else Color.Black
                )
            }
        }

    }

    DisposableEffect(Unit) {
        onDispose {
            bgmPlayer?.stop()
            bgmPlayer?.release()
            soundPool.release()
        }
    }
}

@Composable
fun InfoBox(
    title: String,
    value: String,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
    }
}


@Composable
fun PopBurst(
    frames: List<Int>,
    baseSize: androidx.compose.ui.unit.Dp,
    frameDurationMs: Long = 35L,
    onFinished: () -> Unit
) {
    // 프레임 애니메이션 + 간단한 스케일/알파/리플을 한 번에 재생
    var frameIndex by remember { mutableIntStateOf(0) }

    val scale = remember { Animatable(0.85f) }
    val alpha = remember { Animatable(1f) }
    val ripple = remember { Animatable(0f) }

    val totalDurationMs = (frames.size * frameDurationMs).coerceAtLeast(1L)

    val density = LocalDensity.current
    val strokeWidthPx = with(density) { 3.dp.toPx() }

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

        // 3) 리플: 원형 확산
        launch {
            ripple.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = totalDurationMs.toInt(), easing = FastOutSlowInEasing)
            )
        }

        // 4) 프레임 전환
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
        // Ripple (원형 외곽선)
        Canvas(modifier = Modifier.matchParentSize()) {
            val p = ripple.value.coerceIn(0f, 1f)
            val r = (size.minDimension / 2f) * (0.4f + 0.8f * p)
            val a = (0.35f * (1f - p)).coerceIn(0f, 0.35f)
            drawCircle(
                color = Color.White.copy(alpha = a),
                radius = r,
                style = Stroke(width = strokeWidthPx)
            )
        }

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