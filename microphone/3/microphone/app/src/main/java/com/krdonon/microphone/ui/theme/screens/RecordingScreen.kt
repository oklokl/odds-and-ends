package com.krdonon.microphone.ui.screens

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdonon.microphone.service.RecordingService
import com.krdonon.microphone.ui.MainViewModel
import com.krdonon.microphone.ui.theme.RecordRed
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var elapsedTime by remember { mutableStateOf(0L) }
    var amplitude by remember { mutableStateOf(0) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }

    // 미리보기 재생용 MediaPlayer
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlayingPreview by remember { mutableStateOf(false) }

    LaunchedEffect(isRecording, isPaused) {
        if (isRecording && !isPaused) {
            while (true) {
                elapsedTime += 100
                delay(100)
            }
        }
    }

    // 미리보기 재생 정리
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
                    IconButton(onClick = {
                        previewPlayer?.release()
                        onNavigateBack()
                    }) {
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

                // 진폭 바 표시
                if (isRecording && !isPaused) {
                    Canvas(
                        modifier = Modifier
                            .width(4.dp)
                            .height(200.dp)
                    ) {
                        val normalizedAmplitude = (amplitude.toFloat() / 32767f).coerceIn(0f, 1f)
                        val barHeight = normalizedAmplitude * size.height

                        drawRect(
                            color = RecordRed,
                            topLeft = androidx.compose.ui.geometry.Offset(
                                x = (size.width - 4f) / 2,
                                y = (size.height - barHeight) / 2
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                width = 4f,
                                height = barHeight
                            )
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
                // 좌측: 공간 유지 (미리보기 기능 제거)
                Spacer(modifier = Modifier.size(56.dp))

                // 중앙: 녹음/일시정지/재개 버튼
                FloatingActionButton(
                    onClick = {
                        if (!isRecording) {
                            // 녹음 시작
                            val outputFile = File(context.cacheDir, "temp_recording_${System.currentTimeMillis()}.m4a")
                            currentFile = outputFile
                            startRecordingService(context, outputFile)
                            isRecording = true
                            isPaused = false
                        } else if (isPaused) {
                            // 재개
                            resumeRecordingService(context)
                            isPaused = false
                        } else {
                            // 일시정지
                            pauseRecordingService(context)
                            isPaused = true
                        }
                    },
                    containerColor = if (!isRecording || isPaused) RecordRed else Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(
                        if (!isRecording) Icons.Default.FiberManualRecord
                        else if (isPaused) Icons.Default.FiberManualRecord
                        else Icons.Default.Pause,
                        contentDescription = if (!isRecording) "녹음" else if (isPaused) "재개" else "일시정지",
                        tint = if (!isRecording || isPaused) Color.White else RecordRed,
                        modifier = Modifier.size(40.dp)
                    )
                }

                // 우측: 정지 버튼 (녹음 중일 때만)
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

    // 저장 다이얼로그
    if (showSaveDialog) {
        SaveRecordingDialog(
            viewModel = viewModel,
            file = currentFile,
            onDismiss = {
                showSaveDialog = false
                isRecording = false
                isPaused = false
                elapsedTime = 0
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("녹음 파일 저장") },
        text = {
            Column {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("파일 이름") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 카테고리 선택
                Text("카테고리", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))

                Box {
                    OutlinedButton(
                        onClick = { expandedCategoryMenu = true },
                        modifier = Modifier.fillMaxWidth()
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
                onClick = {
                    file?.let {
                        viewModel.saveRecording(it, fileName, selectedCategory)
                    }
                    onDismiss()
                }
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                file?.delete()
                onDismiss()
            }) {
                Text("취소")
            }
        }
    )

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
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val tenths = (millis / 100) % 10

    return if (hours > 0) {
        String.format("%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
    } else {
        String.format("%02d:%02d.%d", minutes, seconds, tenths)
    }
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