package com.krdonon.metronome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetronomeScreen(
    state: MetronomeState,
    onPlayPauseClick: () -> Unit,
    onBpmChange: (Int) -> Unit,
    onBeatsChange: (Int) -> Unit,
    onBeatUnitChange: (Int) -> Unit,
    onSoundSetNext: () -> Unit,
    onSoundSetSelect: (Int) -> Unit,
    soundSetNames: List<String>,
    currentSoundSet: String,
    onSettingsClick: () -> Unit,
    onVibrationToggle: () -> Unit,
    onKeepScreenToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showBeatsDialog by remember { mutableStateOf(false) }
    var showBeatUnitDialog by remember { mutableStateOf(false) }
    var showSoundSetDialog by remember { mutableStateOf(false) }

    // 유지(화면 꺼짐 방지): 앱이 화면에 있을 때만 적용, 백그라운드/종료 시 자동 복구
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }
    DisposableEffect(lifecycleOwner, state.keepScreenOn) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                view.keepScreenOn = false
                // ✅ UI/서비스 상태도 함께 OFF로 리셋해야
                // 다음 실행/복귀 시 버튼이 "유지"로 돌아가서 한 번만 눌러도 다시 활성화됩니다.
                if (state.keepScreenOn) {
                    onKeepScreenToggle() // true -> false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "메트로놈",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // SpaceBetween + weight 조합은 기기별로 중앙 영역이 예상보다 커져서
            // 원형 비주얼라이저가 상/하단 카드와 겹쳐 보일 수 있습니다.
            // Top 정렬 + 중앙 영역(weight)로 남는 공간만 사용하도록 고정합니다.
            verticalArrangement = Arrangement.Top
        ) {
            // 박자 설정
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 분자 (박자 수)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showBeatsDialog = true }
                    ) {
                        Text(
                            text = state.beatsPerMeasure.toString(),
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "박자",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    Text(
                        text = "/",
                        fontSize = 40.sp,
                        color = Color.Gray
                    )

                    // 분모 (단위)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showBeatUnitDialog = true }
                    ) {
                        Text(
                            text = state.beatUnit.toString(),
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "단위",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    // 소리/진동 + 유지(화면 꺼짐 방지)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onVibrationToggle() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isVibrationMode) Color(0xFFAAAAAA) else Color(0xFF55FFFF),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (state.isVibrationMode) "진동" else "소리",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = { onKeepScreenToggle() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.keepScreenOn) Color(0xFF55FFFF) else Color(0xFFAAAAAA),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (state.keepScreenOn) "해제" else "유지",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 원형 비주얼라이저 (화면 남는 영역에 맞추어 자동으로 크기/비율 조절)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                val available = minOf(maxWidth, maxHeight)
                val visualSize = (available * 0.94f).coerceAtLeast(140.dp)

                CircularVisualizer(
                    beatsPerMeasure = state.beatsPerMeasure,
                    currentBeat = state.currentBeat,
                    isPlaying = state.isPlaying,
                    modifier = Modifier.size(visualSize)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // BPM 컨트롤 (슬림하고 균형잡힌 사이즈)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "BPM",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(
                            onClick = { onBpmChange(state.bpm - 1) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = "BPM 감소",
                                tint = Color.White
                            )
                        }

                        Text(
                            text = state.bpm.toString(),
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF55FFFF),
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )

                        IconButton(
                            onClick = { onBpmChange(state.bpm + 1) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "BPM 증가",
                                tint = Color.White
                            )
                        }
                    }

                    Slider(
                        value = state.bpm.toFloat(),
                        onValueChange = { onBpmChange(it.toInt()) },
                        valueRange = 40f..440f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF55FFFF),
                            activeTrackColor = Color(0xFF55FFFF),
                            inactiveTrackColor = Color(0xFF444444)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "40", color = Color.Gray, fontSize = 11.sp)
                        Text(text = "440", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 하단 컨트롤
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 사운드 전환 버튼
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color(0xFF2A2A2A))
                            .combinedClickable(
                                onClick = onSoundSetNext,
                                onLongClick = { showSoundSetDialog = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "사운드 변경",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentSoundSet,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                // 재생/정지 버튼
                FloatingActionButton(
                    onClick = onPlayPauseClick,
                    containerColor = if (state.isPlaying) Color(0xFF55FFFF) else Color(0xFF2A2A2A),
                    contentColor = if (state.isPlaying) Color.Black else Color.White,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "일시정지" else "재생",
                        modifier = Modifier.size(36.dp)
                    )
                }

                // 설정 버튼
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color(0xFF2A2A2A))
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "설정",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Settings",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }


    // 사운드셋 선택 다이얼로그 (롱프레스)
    if (showSoundSetDialog) {
        SoundSetPickerDialog(
            currentName = currentSoundSet,
            names = soundSetNames,
            onSelectIndex = {
                onSoundSetSelect(it)
                showSoundSetDialog = false
            },
            onDismiss = { showSoundSetDialog = false }
        )
    }

    // 박자 수 선택 다이얼로그
    if (showBeatsDialog) {
        AlertDialog(
            onDismissRequest = { showBeatsDialog = false },
            title = { Text("박자 수 선택") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    (1..16).forEach { beats ->
                        TextButton(
                            onClick = {
                                onBeatsChange(beats)
                                showBeatsDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$beats",
                                fontSize = 20.sp,
                                // ★ 여기 수정: 선택 = 빨강, 나머지 = 검정
                                color = if (beats == state.beatsPerMeasure)
                                    Color.Red else Color.Black
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBeatsDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }

    // 박자 단위 선택 다이얼로그
    if (showBeatUnitDialog) {
        AlertDialog(
            onDismissRequest = { showBeatUnitDialog = false },
            title = { Text("박자 단위 선택") },
            text = {
                Column {
                    listOf(1, 2, 4, 8, 16).forEach { unit ->
                        TextButton(
                            onClick = {
                                onBeatUnitChange(unit)
                                showBeatUnitDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$unit",
                                fontSize = 20.sp,
                                // ★ 여기도 동일하게 수정
                                color = if (unit == state.beatUnit)
                                    Color.Red else Color.Black
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBeatUnitDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }
}


@Composable
private fun SoundSetPickerDialog(
    currentName: String,
    names: List<String>,
    onSelectIndex: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val safeNames = remember(names, currentName) { if (names.isNotEmpty()) names else listOf(currentName) }
    val initialIndex = remember(currentName, safeNames) {
        safeNames.indexOf(currentName).takeIf { it >= 0 } ?: 0
    }
    var selectedIndex by remember { mutableIntStateOf(initialIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("사운드셋 선택") },
        text = {
            AndroidView(
                factory = { context ->
                    android.widget.NumberPicker(context).apply {
                        minValue = 0
                        maxValue = safeNames.lastIndex
                        displayedValues = safeNames.toTypedArray()
                        wrapSelectorWheel = true
                        value = selectedIndex.coerceIn(0, safeNames.lastIndex)
                        setOnValueChangedListener { _, _, newVal ->
                            selectedIndex = newVal
                        }
                    }
                },
                update = { picker ->
                    picker.minValue = 0
                    picker.maxValue = safeNames.lastIndex
                    picker.displayedValues = safeNames.toTypedArray()
                    picker.value = selectedIndex.coerceIn(0, safeNames.lastIndex)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = { onSelectIndex(selectedIndex) }) { Text("선택") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}
