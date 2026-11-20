package com.krdonon.metronome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetronomeScreen(
    state: MetronomeState,
    beatStateProvider: () -> BeatFrameState?,
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
    val currentKeepScreenOn by rememberUpdatedState(state.keepScreenOn)
    val currentOnKeepScreenToggle by rememberUpdatedState(onKeepScreenToggle)

    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                view.keepScreenOn = false
                if (currentKeepScreenOn) {
                    currentOnKeepScreenToggle() // true -> false
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
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            val isLandscapeOrWide = maxWidth > maxHeight && (maxHeight < 580.dp || maxWidth >= 600.dp)

            if (isLandscapeOrWide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VisualizerPane(
                        beatsPerMeasure = state.beatsPerMeasure,
                        isPlaying = state.isPlaying,
                        beatStateProvider = beatStateProvider,
                        onPlayPauseClick = onPlayPauseClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TimeSignatureAndModeCard(
                            beatsPerMeasure = state.beatsPerMeasure,
                            beatUnit = state.beatUnit,
                            isVibrationMode = state.isVibrationMode,
                            keepScreenOn = state.keepScreenOn,
                            onBeatsClick = { showBeatsDialog = true },
                            onBeatUnitClick = { showBeatUnitDialog = true },
                            onVibrationToggle = onVibrationToggle,
                            onKeepScreenToggle = onKeepScreenToggle
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        BpmControlCard(
                            bpm = state.bpm,
                            onBpmChange = onBpmChange
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        BottomPlaybackControls(
                            isPlaying = state.isPlaying,
                            currentSoundSet = currentSoundSet,
                            onPlayPauseClick = onPlayPauseClick,
                            onSoundSetNext = onSoundSetNext,
                            onSoundSetLongClick = { showSoundSetDialog = true },
                            onSettingsClick = onSettingsClick
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    TimeSignatureAndModeCard(
                        beatsPerMeasure = state.beatsPerMeasure,
                        beatUnit = state.beatUnit,
                        isVibrationMode = state.isVibrationMode,
                        keepScreenOn = state.keepScreenOn,
                        onBeatsClick = { showBeatsDialog = true },
                        onBeatUnitClick = { showBeatUnitDialog = true },
                        onVibrationToggle = onVibrationToggle,
                        onKeepScreenToggle = onKeepScreenToggle
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    VisualizerPane(
                        beatsPerMeasure = state.beatsPerMeasure,
                        isPlaying = state.isPlaying,
                        beatStateProvider = beatStateProvider,
                        onPlayPauseClick = onPlayPauseClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    BpmControlCard(
                        bpm = state.bpm,
                        onBpmChange = onBpmChange
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    BottomPlaybackControls(
                        isPlaying = state.isPlaying,
                        currentSoundSet = currentSoundSet,
                        onPlayPauseClick = onPlayPauseClick,
                        onSoundSetNext = onSoundSetNext,
                        onSoundSetLongClick = { showSoundSetDialog = true },
                        onSettingsClick = onSettingsClick
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
                                color = if (beats == state.beatsPerMeasure) Color.Red else Color.Black
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
                                color = if (unit == state.beatUnit) Color.Red else Color.Black
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
private fun TimeSignatureAndModeCard(
    beatsPerMeasure: Int,
    beatUnit: Int,
    isVibrationMode: Boolean,
    keepScreenOn: Boolean,
    onBeatsClick: () -> Unit,
    onBeatUnitClick: () -> Unit,
    onVibrationToggle: () -> Unit,
    onKeepScreenToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = onBeatsClick)
            ) {
                Text(
                    text = beatsPerMeasure.toString(),
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

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = onBeatUnitClick)
            ) {
                Text(
                    text = beatUnit.toString(),
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

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onVibrationToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isVibrationMode) Color(0xFFAAAAAA) else Color(0xFF55FFFF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isVibrationMode) "진동" else "소리",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = onKeepScreenToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (keepScreenOn) Color(0xFF55FFFF) else Color(0xFFAAAAAA),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (keepScreenOn) "해제" else "유지",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun VisualizerPane(
    beatsPerMeasure: Int,
    isPlaying: Boolean,
    beatStateProvider: () -> BeatFrameState?,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onPlayPauseClick
        ),
        contentAlignment = Alignment.Center
    ) {
        val available = minOf(maxWidth, maxHeight)
        val visualSize = (available * 0.94f).coerceAtLeast(140.dp)

        CircularVisualizer(
            beatsPerMeasure = beatsPerMeasure,
            isPlaying = isPlaying,
            beatStateProvider = beatStateProvider,
            modifier = Modifier.size(visualSize)
        )
    }
}

@Composable
private fun BpmControlCard(
    bpm: Int,
    onBpmChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
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
                    onClick = { onBpmChange(bpm - 1) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "BPM 감소",
                        tint = Color.White
                    )
                }

                Text(
                    text = bpm.toString(),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF55FFFF),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                IconButton(
                    onClick = { onBpmChange(bpm + 1) },
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
                value = bpm.toFloat(),
                onValueChange = { onBpmChange(it.roundToInt()) },
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
}

@Composable
private fun BottomPlaybackControls(
    isPlaying: Boolean,
    currentSoundSet: String,
    onPlayPauseClick: () -> Unit,
    onSoundSetNext: () -> Unit,
    onSoundSetLongClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF2A2A2A))
                    .combinedClickable(
                        onClick = onSoundSetNext,
                        onLongClick = onSoundSetLongClick
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

        FloatingActionButton(
            onClick = onPlayPauseClick,
            containerColor = if (isPlaying) Color(0xFF55FFFF) else Color(0xFF2A2A2A),
            contentColor = if (isPlaying) Color.Black else Color.White,
            modifier = Modifier.size(72.dp)
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "일시정지" else "재생",
                modifier = Modifier.size(36.dp)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
