package com.krdonon.microphone.ui.screens

import android.content.Context
import android.content.Intent
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToRecording: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val recordings by viewModel.recordings.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("all") } // all, voice, trash, unassigned

    // 필터링된 녹음 목록
    val filteredRecordings = when (selectedFilter) {
        "unassigned" -> recordings.filter { it.category == "미지정" }
        "trash" -> emptyList() // 휴지통 기능은 별도 구현 필요
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
                                // TODO: 카테고리 관리 화면으로 이동
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
                    RecordingItem(recording, viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingItem(recording: RecordingFile, viewModel: MainViewModel) {
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
                    // 단순 클릭: 재생
                    playRecording(context, recording)
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
            Text(
                recording.fileName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
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
            IconButton(onClick = { playRecording(context, recording) }) {
                Icon(Icons.Default.PlayArrow, "재생")
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

fun playRecording(context: Context, recording: RecordingFile) {
    try {
        // 파일 존재 여부 확인
        val file = java.io.File(recording.filePath)
        if (!file.exists()) {
            android.widget.Toast.makeText(
                context,
                "파일을 찾을 수 없습니다: ${recording.fileName}",
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
    } catch (e: Exception) {
        e.printStackTrace()
        android.widget.Toast.makeText(
            context,
            "재생 실패: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}