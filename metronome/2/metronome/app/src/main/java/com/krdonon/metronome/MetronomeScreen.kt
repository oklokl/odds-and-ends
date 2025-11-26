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
    onSettingsClick: () -> Unit,      // ← 추가
    onVibrationToggle: () -> Unit,   // ← 새로 추가
    modifier: Modifier = Modifier
) {
    var showBeatsDialog by remember { mutableStateOf(false) }
    var showBeatUnitDialog by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.SpaceBetween
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
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
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
                        fontSize = 48.sp,
                        color = Color.Gray
                    )

                    // 분모 (단위)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showBeatUnitDialog = true }
                    ) {
                        Text(
                            text = state.beatUnit.toString(),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "단위",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }

                    // OK 버튼
                    // OK 버튼 → 소리/진동 토글 버튼
                    Button(
                        onClick = { onVibrationToggle() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isVibrationMode) Color(0xFFAAAAAA) else Color(0xFF55FFFF),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (state.isVibrationMode) "진동" else "소리",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 원형 비주얼라이저
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularVisualizer(
                    beatsPerMeasure = state.beatsPerMeasure,
                    currentBeat = state.currentBeat,
                    isPlaying = state.isPlaying
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

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
                            fontWeight = FontWeight.Bold,
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

                // 설정 버튼 (향후 확장용)
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
                        .heightIn(max = 400.dp)                  // 너무 길어지지 않게 최대 높이 제한
                        .verticalScroll(rememberScrollState())   // 세로 스크롤 가능하게
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
                                color = if (beats == state.beatsPerMeasure)
                                    Color(0xFF55FFFF) else Color.White
                            )
                        }
                    }
                }
            }
            ,
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
                                color = if (unit == state.beatUnit)
                                    Color(0xFF55FFFF) else Color.White
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
