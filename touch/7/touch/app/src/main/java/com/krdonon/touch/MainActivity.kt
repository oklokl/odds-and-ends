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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchGameScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 게임 진행 시간 (일시정지 시 시간 정지)
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var accumulatedMs by remember { mutableLongStateOf(0L) }
    var lastStartRealtimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    var touchCount by remember { mutableIntStateOf(0) }
    var failCount by remember { mutableIntStateOf(0) }
    var isGamePaused by remember { mutableStateOf(false) }
    var touchTargets by remember { mutableStateOf<List<TouchTarget>>(emptyList()) }
    var isBgmEnabled by remember { mutableStateOf(true) }

    // 난이도 옵션
    // 요청: 시작부터 활성화 X -> 기본은 회색(OFF)
    var isAutoDifficultyEnabled by remember { mutableStateOf(false) } // “점점 어렵게”
    var isManualStageEnabled by remember { mutableStateOf(false) }
    var manualStage by remember { mutableIntStateOf(0) }
    var showStageDialog by remember { mutableStateOf(false) }

    // 앱 백그라운드 -> 복귀 시 자동으로 재개(풍선 안 뜨는 문제 대응)
    var wasAutoPaused by remember { mutableStateOf(false) }

    // Reset을 위해 효과 재시작 트리거
    var resetToken by remember { mutableIntStateOf(0) }

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
                    wasAutoPaused = !isGamePaused
                    isGamePaused = true
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

    // 타겟 생성 (겹치지 않게 1개만 유지)
    LaunchedEffect(isGamePaused, resetToken, effectiveStage) {
        while (!isGamePaused) {
            // 이미 화면에 물방울이 있으면 새로 만들지 않음
            if (touchTargets.isNotEmpty()) {
                delay(20)
                continue
            }

            val stageForThisSpawn = effectiveStage
            val delayRange = spawnDelayRangeMs(stageForThisSpawn)
            val nextDelay = if (delayRange.first == delayRange.last) delayRange.first
            else Random.nextLong(delayRange.first, delayRange.last + 1)

            delay(nextDelay)

            if (isGamePaused) break
            if (touchTargets.isNotEmpty()) continue

            val newTarget = TouchTarget(
                id = System.currentTimeMillis(),
                x = Random.nextFloat(),
                y = Random.nextFloat()
            )
            touchTargets = listOf(newTarget)

            val targetId = newTarget.id
            coroutineScope.launch {
                delay(targetLifetimeMs(stageForThisSpawn))
                if (!isGamePaused && touchTargets.any { it.id == targetId }) {
                    touchTargets = emptyList()
                    failCount++
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .padding(bottom = 38.dp)
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

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
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

            val interactionSource = remember { MutableInteractionSource() }

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
                                        touchTargets = emptyList()
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
        }

        // 기존: 일시정지 / 초기화
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
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
                .padding(top = 10.dp),
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
