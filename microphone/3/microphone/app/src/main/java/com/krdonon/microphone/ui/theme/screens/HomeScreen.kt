package com.krdonon.microphone.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.krdonon.microphone.data.model.RecordingFile
import com.krdonon.microphone.service.PlaybackService
import com.krdonon.microphone.ui.MainViewModel
import com.krdonon.microphone.ui.theme.RecordRed
import java.text.SimpleDateFormat
import java.util.*

// 전역 재생 상태 관리
object PlaybackState {
    var currentPlayingId = mutableStateOf<String?>(null)
    var isPlaying = mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToRecording: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val recordings by viewModel.recordings.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("all") }

    // 재생 상태 관찰
    val currentPlayingId by PlaybackState.currentPlayingId
    val isPlaying by PlaybackState.isPlaying

    // 필터링된 녹음 목록
    val filteredRecordings = when (selectedFilter) {
        "unassigned" -> recordings.filter { it.category == "미지정" }
        "trash" -> emptyList()
        else -> recordings
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (selectedFilter) {
                                "all" -> "모든 녹음 파일"
                                "voice" -> "음성 녹음"
                                "trash" -> "휴지통"
                                "unassigned" -> "카테고리 미지정"
                                else -> "모든 녹음 파일"
                            },
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "녹음 파일 ${filteredRecordings.size}개",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { /* 검색 기능 */ }) {
                        Icon(Icons.Default.Search, "검색")
                    }
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.Menu, "메뉴")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("모든 녹음 파일") },
                            onClick = {
                                selectedFilter = "all"
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.MusicNote, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("음성 녹음") },
                            onClick = {
                                selectedFilter = "voice"
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Mic, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("휴지통") },
                            onClick = {
                                selectedFilter = "trash"
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null) }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("카테고리 미지정") },
                            onClick = {
                                selectedFilter = "unassigned"
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Folder, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("카테고리 관리") },
                            onClick = {
                                showMenu = false
                            }
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "설정")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToRecording,
                shape = CircleShape,
                containerColor = RecordRed,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "녹음",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    ) { paddingValues ->
        if (filteredRecordings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when (selectedFilter) {
                        "trash" -> "휴지통이 비어 있습니다"
                        else -> "녹음 파일이 없습니다"
                    },
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(filteredRecordings, key = { it.id }) { recording ->
                    RecordingItem(
                        recording = recording,
                        viewModel = viewModel,
                        isCurrentlyPlaying = currentPlayingId == recording.id && isPlaying,
                        onPlayPauseClick = { context ->
                            handlePlayPause(context, recording, currentPlayingId, isPlaying)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingItem(
    recording: RecordingFile,
    viewModel: MainViewModel,
    isCurrentlyPlaying: Boolean,
    onPlayPauseClick: (Context) -> Unit
) {
    val context = LocalContext.current
    val dateFormat = SimpleDateFormat("M월 d일 오전 h:mm", Locale.KOREAN)
    var showOptionsDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    // 단순 클릭: 재생/일시정지
                    onPlayPauseClick(context)
                },
                onLongClick = {
                    // 길게 누르기: 옵션 메뉴
                    showOptionsDialog = true
                }
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    recording.fileName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                // 현재 재생 중인 파일 표시
                if (isCurrentlyPlaying) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "재생 중",
                        modifier = Modifier.size(16.dp),
                        tint = RecordRed
                    )
                }
            }
            Text(
                dateFormat.format(Date(recording.dateCreated)),
                fontSize = 14.sp,
                color = Color.Gray
            )
            if (recording.category != "미지정") {
                Text(
                    recording.category,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                recording.durationFormatted,
                fontSize = 14.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = { onPlayPauseClick(context) }) {
                Icon(
                    if (isCurrentlyPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isCurrentlyPlaying) "일시정지" else "재생",
                    tint = if (isCurrentlyPlaying) RecordRed else Color.Unspecified
                )
            }
        }
    }
    Divider()

    // 옵션 다이얼로그
    if (showOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showOptionsDialog = false },
            title = { Text(recording.fileName) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showOptionsDialog = false
                            showRenameDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, "이름 변경")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("이름 변경")
                    }
                    TextButton(
                        onClick = {
                            showOptionsDialog = false
                            showDeleteDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, "삭제", tint = Color.Red)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("삭제", color = Color.Red)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOptionsDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }

    // 이름 변경 다이얼로그
    if (showRenameDialog) {
        var newName by remember { mutableStateOf(recording.fileName.substringBeforeLast(".")) }

        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("이름 변경") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("파일 이름") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameRecording(recording, newName)
                    showRenameDialog = false
                }) {
                    Text("변경")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    // 삭제 확인 다이얼로그
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("파일 삭제") },
            text = { Text("이 녹음 파일을 삭제하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    // 재생 중인 파일 삭제 시 재생 정지
                    if (isCurrentlyPlaying) {
                        stopPlayback(context)
                    }
                    viewModel.deleteRecording(recording)
                    showDeleteDialog = false
                }) {
                    Text("삭제", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

fun handlePlayPause(
    context: Context,
    recording: RecordingFile,
    currentPlayingId: String?,
    isPlaying: Boolean
) {
    when {
        // 다른 파일이 재생 중인 경우 - 정지하고 새 파일 재생
        currentPlayingId != null && currentPlayingId != recording.id -> {
            stopPlayback(context)
            playRecording(context, recording)
            PlaybackState.currentPlayingId.value = recording.id
            PlaybackState.isPlaying.value = true
        }
        // 같은 파일이 재생 중인 경우 - 일시정지
        currentPlayingId == recording.id && isPlaying -> {
            pausePlayback(context)
            PlaybackState.isPlaying.value = false
        }
        // 같은 파일이 일시정지 상태인 경우 - 재개
        currentPlayingId == recording.id && !isPlaying -> {
            resumePlayback(context)
            PlaybackState.isPlaying.value = true
        }
        // 아무것도 재생 중이지 않은 경우 - 새 파일 재생
        else -> {
            playRecording(context, recording)
            PlaybackState.currentPlayingId.value = recording.id
            PlaybackState.isPlaying.value = true
        }
    }
}

fun playRecording(context: Context, recording: RecordingFile) {
    try {
        val file = java.io.File(recording.filePath)

        Log.d("PlayRecording", "Attempting to play: ${recording.filePath}")
        Log.d("PlayRecording", "File exists: ${file.exists()}")
        Log.d("PlayRecording", "File size: ${file.length()} bytes")

        if (!file.exists()) {
            android.widget.Toast.makeText(
                context,
                "파일을 찾을 수 없습니다: ${recording.fileName}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (file.length() == 0L) {
            android.widget.Toast.makeText(
                context,
                "파일이 비어있습니다: ${recording.fileName}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY
            putExtra(PlaybackService.EXTRA_FILE_PATH, recording.filePath)
            putExtra(PlaybackService.EXTRA_FILE_NAME, recording.fileName)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        Log.d("PlayRecording", "PlaybackService started successfully")

    } catch (e: Exception) {
        Log.e("PlayRecording", "Failed to play recording", e)
        android.widget.Toast.makeText(
            context,
            "재생 실패: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

fun pausePlayback(context: Context) {
    val intent = Intent(context, PlaybackService::class.java).apply {
        action = PlaybackService.ACTION_PAUSE
    }
    context.startService(intent)
}

fun resumePlayback(context: Context) {
    val intent = Intent(context, PlaybackService::class.java).apply {
        action = PlaybackService.ACTION_RESUME
    }
    context.startService(intent)
}

fun stopPlayback(context: Context) {
    val intent = Intent(context, PlaybackService::class.java).apply {
        action = PlaybackService.ACTION_STOP
    }
    context.startService(intent)
    // 상태 초기화
    PlaybackState.currentPlayingId.value = null
    PlaybackState.isPlaying.value = false
}