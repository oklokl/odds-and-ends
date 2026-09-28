package com.krdonon.microphone.ui.screens

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import android.app.Activity
import com.krdonon.microphone.service.RecordingService
import com.krdonon.microphone.service.RecordingStateManager
import com.krdonon.microphone.ui.MainViewModel
import com.krdonon.microphone.ui.theme.RecordRed
import com.krdonon.microphone.utils.CacheCleaner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()   // 🔹 추가

    // 서비스에서 내려주는 녹음 상태
    val isRecording by RecordingStateManager
        .isRecording
        .collectAsState(initial = false)

    val isPaused by RecordingStateManager
        .isPaused
        .collectAsState(initial = false)

// 알림바에서 정지되었는지 여부
    val stoppedFromNotification by RecordingStateManager
        .stoppedFromNotification
        .collectAsState(initial = false)

// 🔊 현재 진폭 (0 ~ 32767)
    val amplitude by RecordingStateManager
        .amplitude
        .collectAsState(initial = 0)

// UI 로컬 상태
    // 변경: 서비스에서 내려주는 값 사용
    val elapsedTime by RecordingStateManager
        .elapsedTime
        .collectAsState(initial = 0L)
    var currentFile by remember { mutableStateOf<File?>(null) }

    // 저장 다이얼로그 표시 여부
    var showSaveDialog by remember { mutableStateOf(false) }


    // 미리보기 재생용 MediaPlayer
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlayingPreview by remember { mutableStateOf(false) }

    /**
     * 타이머: 녹음 중 & 일시정지 아님일 때만 0.1초마다 증가
     */

    /**
     * 알림바에서 정지된 경우:
     * - UI 상태 초기화
     * - 플래그 소비 후 목록 화면(뒤로가기)으로 이동
     */
    // 🔹 녹음을 안 하고 있는 상태가 일정 시간 이상 계속되면 화면 자동 종료
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            // 예: 60초 동안 녹음을 시작하지 않으면 종료
            kotlinx.coroutines.delay(60_000)

            // 60초 뒤에도 여전히 녹음이 안 시작됐을 때만 종료
            val stillNotRecording = !RecordingStateManager.isRecording.value
            if (stillNotRecording) {
                (context as? Activity)?.finish()
            }
        }
    }


    // 미리보기 재생 리소스 정리
    DisposableEffect(Unit) {
        onDispose {
            previewPlayer?.release()
            previewPlayer = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isRecording) "일반" else "녹음 준비") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // 미리보기 플레이어 정리
                            previewPlayer?.release()
                            previewPlayer = null

                            if (isRecording) {
                                // 🔴 녹음 중이면 → 녹음 취소(정지)
                                stopRecordingService(context)

                                // UI 상태도 초기화
                                // elapsedTime = 0L
                                currentFile = null
                                showSaveDialog = false
                            }

                            // 화면 뒤로가기 (홈으로)
                            onNavigateBack()
                        }
                    ) {
                        Icon(Icons.Default.ArrowBack, "뒤로")
                    }
                }
            )

        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // 타이머 표시
            Text(
                formatTime(elapsedTime),
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                color = if (isRecording) RecordRed else Color.Gray
            )

            Spacer(modifier = Modifier.height(64.dp))

            // 오디오 진폭 시각화
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                // 중앙 선
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(2.dp)
                ) {
                    drawLine(
                        color = Color.Gray,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                        strokeWidth = 2f
                    )
                }

                // 진폭 바 표시 (녹음 중 & 일시정지 아님일 때만)
                if (isRecording && !isPaused) {
                    RealtimeWaveform(
                        amplitude = amplitude,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                } else {
                    // 녹음 안 할 때는 중앙 선만
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(2.dp)
                    ) {
                        drawLine(
                            color = Color.Gray,
                            start = Offset(0f, size.height / 2),
                            end = Offset(size.width, size.height / 2),
                            strokeWidth = 2f
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 컨트롤 버튼들
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 좌측: (예전 미리보기 자리, 현재는 공간 유지용)
                Spacer(modifier = Modifier.size(56.dp))

                // 중앙: 녹음/일시정지/재개 버튼
                FloatingActionButton(
                    onClick = {
                        if (!isRecording) {

                            // 🔧 새 녹음 시작 전에 캐시 정리를 백그라운드에서 실행
                            scope.launch(Dispatchers.IO) {
                                CacheCleaner.cleanRecordingCache(context)
                            }

                            // 🔴 UI는 바로 녹음 시작 (캐시 정리는 뒤에서 천천히)
                            val outputFile = File(
                                context.cacheDir,
                                "temp_recording_${System.currentTimeMillis()}.m4a"
                            )
                            currentFile = outputFile
                            startRecordingService(context, outputFile)
                            // isRecording / isPaused 는 서비스에서 업데이트
                        } else if (isPaused) {
                            // 재개
                            resumeRecordingService(context)
                        } else {
                            // 일시정지
                            pauseRecordingService(context)
                        }
                    },
                    containerColor = if (!isRecording || isPaused) RecordRed else Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(
                        imageVector =
                            if (!isRecording) Icons.Default.FiberManualRecord
                            else if (isPaused) Icons.Default.FiberManualRecord
                            else Icons.Default.Pause,
                        contentDescription =
                            if (!isRecording) "녹음"
                            else if (isPaused) "재개"
                            else "일시정지",
                        tint = if (!isRecording || isPaused) Color.White else RecordRed,
                        modifier = Modifier.size(40.dp)
                    )
                }

                // 우측: 정지 버튼 (녹음 중일 때만 보이도록)
                if (isRecording) {
                    FloatingActionButton(
                        onClick = {
                            stopRecordingService(context)
                            showSaveDialog = true
                        },
                        containerColor = Color.Gray,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Stop, "정지")
                    }
                } else {
                    // 공간 유지를 위한 투명 박스
                    Spacer(modifier = Modifier.size(56.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 저장 다이얼로그 (앱 안에서 정지 버튼 눌렀을 때만 사용)
    if (showSaveDialog) {
        SaveRecordingDialog(
            viewModel = viewModel,
            file = currentFile,
            onDismiss = {
                showSaveDialog = false
                RecordingStateManager.updateElapsedTime(0L)
                currentFile = null
                onNavigateBack()
            }
        )
    }

}

@Composable
fun SaveRecordingDialog(
    viewModel: MainViewModel,
    file: File?,
    onDismiss: () -> Unit
) {
    var fileName by remember { mutableStateOf(viewModel.generateFileName()) }
    var selectedCategory by remember { mutableStateOf("미지정") }
    val categories by viewModel.categories.collectAsState()
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var expandedCategoryMenu by remember { mutableStateOf(false) }
    val conversionProgress by viewModel.conversionProgress.collectAsState()

    AlertDialog(
        onDismissRequest = {
            if (conversionProgress == null) onDismiss()
        },
        title = { Text("녹음 파일 저장") },
        text = {
            Column {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("파일 이름") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = conversionProgress == null
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 카테고리 선택
                Text("카테고리", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))

                Box {
                    OutlinedButton(
                        onClick = { if (conversionProgress == null) expandedCategoryMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = conversionProgress == null
                    ) {
                        Text(selectedCategory)
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }

                    DropdownMenu(
                        expanded = expandedCategoryMenu,
                        onDismissRequest = { expandedCategoryMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("미지정") },
                            onClick = {
                                selectedCategory = "미지정"
                                expandedCategoryMenu = false
                            }
                        )
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategory = category.name
                                    expandedCategoryMenu = false
                                }
                            )
                        }
                        Divider()
                        DropdownMenuItem(
                            text = { Text("+ 새 카테고리 추가") },
                            onClick = {
                                expandedCategoryMenu = false
                                showAddCategoryDialog = true
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = conversionProgress == null,
                onClick = {
                    file?.let {
                        viewModel.saveRecording(it, fileName, selectedCategory) {
                            onDismiss()
                        }
                    } ?: onDismiss()
                }
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(
                enabled = conversionProgress == null,
                onClick = {
                    file?.delete()
                    onDismiss()
                }
            ) {
                Text("취소")
            }
        }
    )

    // MP3 변환 진행률 팝업 다이얼로그 (게이지바)
    if (conversionProgress != null) {
        AlertDialog(
            onDismissRequest = { /* 변환 중 닫기 방지 */ },
            title = { Text("MP3 변환 및 저장 중...") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { conversionProgress ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${((conversionProgress ?: 0f) * 100).toInt()}%",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {}
        )
    }

    // 카테고리 추가 다이얼로그
    if (showAddCategoryDialog) {
        var newCategoryName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("새 카테고리") },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("카테고리 이름") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newCategoryName.isNotBlank()) {
                            viewModel.addCategory(newCategoryName)
                            selectedCategory = newCategoryName
                        }
                        showAddCategoryDialog = false
                    }
                ) {
                    Text("추가")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

fun formatTime(millis: Long): String {
    // 전체 초
    val totalSeconds = millis / 1000

    // 시간/분/초 계산 (99시간까지만 표시, 그 이상은 99로 고정)
    val hours = (totalSeconds / 3600).coerceAtMost(99)
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    // 0.1초 단위(지금 타이머가 100ms마다 증가하므로 소수 첫째 자리까지만 정확함)
    val tenths = (millis / 100) % 10

    // 항상 HH:MM:SS.d 형식으로 반환
    return String.format("%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
}


fun startRecordingService(context: Context, outputFile: File) {
    val intent = Intent(context, RecordingService::class.java).apply {
        action = RecordingService.ACTION_START_RECORDING
        putExtra(RecordingService.EXTRA_OUTPUT_FILE, outputFile.absolutePath)
    }
    context.startForegroundService(intent)
}

fun pauseRecordingService(context: Context) {
    val intent = Intent(context, RecordingService::class.java).apply {
        action = RecordingService.ACTION_PAUSE_RECORDING
    }
    context.startService(intent)
}

fun resumeRecordingService(context: Context) {
    val intent = Intent(context, RecordingService::class.java).apply {
        action = RecordingService.ACTION_RESUME_RECORDING
    }
    context.startService(intent)
}

fun stopRecordingService(context: Context) {
    val intent = Intent(context, RecordingService::class.java).apply {
        action = RecordingService.ACTION_STOP_RECORDING
    }
    context.startService(intent)
}

@Composable
fun RealtimeWaveform(
    amplitude: Int,
    modifier: Modifier = Modifier,
    historySize: Int = 80        // 화면에 표시할 막대 개수 (개수 늘리면 더 촘촘)
) {
    // 0.0 ~ 1.0 사이로 정규화
    val normalized = (amplitude / 32767f).coerceIn(0f, 1f)

    // 🔹 고정 크기 버퍼 + 현재 인덱스
    val buffer = remember { FloatArray(historySize) { 0f } }
    var index by remember { mutableStateOf(0) }

    // 🔹 새 amplitude 가 들어올 때마다 버퍼에 덮어쓰기
    LaunchedEffect(amplitude) {
        buffer[index] = normalized
        index = (index + 1) % historySize
    }

    Canvas(modifier = modifier) {
        val barWidth = size.width / historySize
        val centerY = size.height / 2f

        // 🔹 가장 오래된 값부터 순서대로 그리기
        for (i in 0 until historySize) {
            val value = buffer[(index + i) % historySize]
            val barHeight = value * size.height

            drawLine(
                color = Color.Red,
                start = Offset(
                    x = i * barWidth + barWidth / 2f,
                    y = centerY - barHeight / 2f
                ),
                end = Offset(
                    x = i * barWidth + barWidth / 2f,
                    y = centerY + barHeight / 2f
                ),
                strokeWidth = barWidth * 0.6f
            )
        }
    }
}


