package com.krdonon.metronome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
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
    onSoundSetChange: () -> Unit,
    currentSoundSet: String,
    onSettingsClick: () -> Unit,
    onVibrationToggle: () -> Unit,
    onKeepScreenToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showBeatsDialog by remember { mutableStateOf(false) }
    var showBeatUnitDialog by remember { mutableStateOf(false) }

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
                        .padding(16.dp),
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
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "박자",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }

                    Text(
                        text = "/",
                        fontSize = 46.sp,
                        color = Color.Gray
                    )

                    // 분모 (단위)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showBeatUnitDialog = true }
                    ) {
                        Text(
                            text = state.beatUnit.toString(),
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "단위",
                            fontSize = 14.sp,
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
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (state.isVibrationMode) "진동" else "소리",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = { onKeepScreenToggle() },
                            colors = ButtonDefaults.buttonColors(
                                // 유지(비활성) = 회색, 해제(활성) = 파랑
                                containerColor = if (state.keepScreenOn) Color(0xFF55FFFF) else Color(0xFFAAAAAA),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (state.keepScreenOn) "해제" else "유지",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 원형 비주얼라이저 (기기별 화면 크기에 맞춰 자동으로 크기/간격 조절)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // 중앙 영역(남는 공간) 안에서만 커지도록: "영역의 정사각형" 기준으로 크기 산정
                // - 너무 작게 쪼그라들지 않도록 min
                // - 너무 커져 상/하단과 시각적으로 충돌하지 않도록 max
                val available = minOf(maxWidth, maxHeight)
                val maxCap = if (maxWidth > 600.dp) 720.dp else 520.dp
                val visualSize = (available * 0.90f).coerceIn(220.dp, maxCap)

                CircularVisualizer(
                    beatsPerMeasure = state.beatsPerMeasure,
                    currentBeat = state.currentBeat,
                    isPlaying = state.isPlaying,
                    modifier = Modifier
                        .size(visualSize)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // BPM 컨트롤
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "BPM",
                        fontSize = 16.sp,
                        color = Color.Gray
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(
                            onClick = { onBpmChange(state.bpm - 1) }
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = "BPM 감소",
                                tint = Color.White
                            )
                        }

                        Text(
                            text = state.bpm.toString(),
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF55FFFF)
                        )

                        IconButton(
                            onClick = { onBpmChange(state.bpm + 1) }
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
                        valueRange = 40f..240f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF55FFFF),
                            activeTrackColor = Color(0xFF55FFFF),
                            inactiveTrackColor = Color(0xFF444444)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "40", color = Color.Gray, fontSize = 12.sp)
                        Text(text = "240", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                    IconButton(
                        onClick = onSoundSetChange,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xFF2A2A2A))
                    ) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "사운드 변경",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
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
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "일시정지" else "재생",
                        modifier = Modifier.size(40.dp)
                    )
                }

                // 설정 버튼
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xFF2A2A2A))
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "설정",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
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
