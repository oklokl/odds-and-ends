package com.krdonon.microphone.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.DeleteForever
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.krdonon.microphone.data.model.RecordingFile
import com.krdonon.microphone.service.PlaybackService
import com.krdonon.microphone.ui.MainViewModel
import com.krdonon.microphone.ui.theme.RecordRed
import java.text.SimpleDateFormat
import java.util.*
import com.krdonon.microphone.data.model.Category

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
    onNavigateToSettings: () -> Unit,
    onNavigateToCategoryManagement: () -> Unit
) {
    val trashRecordings by viewModel.trashRecordings.collectAsState()
    val recordings by viewModel.recordings.collectAsState()
    val categories by viewModel.categories.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("all") }
    var expandedRecordingId by remember { mutableStateOf<String?>(null) }


    // ViewModel 이 들고 있는 선택된 카테고리
    val selectedCategory by viewModel.selectedCategoryFilter.collectAsState()

    // ⏱ 화면 재진입 시 폴더 내용 동기화
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadRecordings()
            }
        }
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // 카테고리 선택 상태에 따라 selectedFilter 보정
    LaunchedEffect(selectedCategory) {
        if (selectedCategory != null) {
            selectedFilter = "category"
        } else if (selectedFilter == "category") {
            selectedFilter = "all"
        }
    }

    // 재생 상태
    val currentPlayingRecording by com.krdonon.microphone.service.PlaybackStateManager.currentRecording.collectAsState()
    val isPlaying by com.krdonon.microphone.service.PlaybackStateManager.isPlaying.collectAsState()
    val currentPlayingId = currentPlayingRecording?.id

    // 🔎 최종 필터링
    val filteredRecordings = when (selectedFilter) {
        "unassigned" -> recordings.filter { it.category == "미지정" }
        "trash" -> trashRecordings
        "category" -> selectedCategory?.let { cat ->
            recordings.filter { it.category == cat }
        } ?: recordings
        else -> recordings
    }

    // 🔒 id 기준 중복 제거
    val distinctRecordings = filteredRecordings.distinctBy { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedFilter) {
                            "all" -> "모든 녹음 파일"
                            "voice" -> "음성 녹음"
                            "trash" -> "휴지통"
                            "unassigned" -> "카테고리 미지정"
                            "category" -> selectedCategory ?: "카테고리"
                            else -> "모든 녹음 파일"
                        },
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    if (selectedFilter == "category" || selectedFilter == "trash") {
                        IconButton(
                            onClick = {
                                selectedFilter = "all"
                                viewModel.setSelectedCategoryFilter(null)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "뒤로가기"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { /* 검색 */ }) {
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
                                viewModel.setSelectedCategoryFilter(null)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.MusicNote, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("음성 녹음") },
                            onClick = {
                                selectedFilter = "voice"
                                viewModel.setSelectedCategoryFilter(null)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Mic, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("휴지통") },
                            onClick = {
                                selectedFilter = "trash"
                                viewModel.setSelectedCategoryFilter(null)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("카테고리 미지정") },
                            onClick = {
                                selectedFilter = "unassigned"
                                viewModel.setSelectedCategoryFilter(null)
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("카테고리 관리") },
                            onClick = {
                                showMenu = false
                                onNavigateToCategoryManagement()
                            }
                        )
                    }

                    if (selectedFilter == "trash" && trashRecordings.isNotEmpty()) {
                        TextButton(onClick = { viewModel.emptyTrash() }) {
                            Text("비우기", color = Color.Red)
                        }
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
        // 오디오 재생 팝업 (중앙 다이얼로그 + 딤드 배경)
        AudioPlayerPopup()

        if (distinctRecordings.isEmpty()) {
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
                items(distinctRecordings, key = { it.id }) { recording ->
                    RecordingItem(
                        recording = recording,
                        viewModel = viewModel,
                        categories = categories,
                        isCurrentlyPlaying = currentPlayingId == recording.id && isPlaying,
                        isExpanded = expandedRecordingId == recording.id,
                        isInTrash = (selectedFilter == "trash"),
                        onItemClick = { context ->
                            handlePlayPause(context, recording, currentPlayingId, isPlaying)
                        },
                        onItemLongClick = {
                            expandedRecordingId = if (expandedRecordingId == recording.id) {
                                null
                            } else {
                                recording.id
                            }
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
    categories: List<Category>,
    isCurrentlyPlaying: Boolean,
    isExpanded: Boolean,
    isInTrash: Boolean,
    onItemClick: (Context) -> Unit,
    onItemLongClick: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREAN)

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePermanently by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.combinedClickable(
            onClick = { onItemClick(context) },
            onLongClick = onItemLongClick
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 왼쪽: 파일 정보
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = recording.fileName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (isCurrentlyPlaying) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "재생 중",
                            modifier = Modifier.size(16.dp),
                            tint = RecordRed
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = dateFormat.format(Date(recording.dateCreated)),
                    fontSize = 14.sp,
                    color = Color.Gray
                )

                if (recording.category != "미지정") {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = recording.category,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 오른쪽: 길이 / 용량 / 재생 버튼
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = recording.durationFormatted,
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Text(
                    text = recording.fileSizeFormatted,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                // 재생/일시정지 아이콘 버튼은 시각적 피드백을 위해 유지합니다.
                // 전체 항목이 클릭 가능하므로 기능적으로는 필수가 아닙니다.
                IconButton(onClick = { onItemClick(context) }) {
                    Icon(
                        imageVector = if (isCurrentlyPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isCurrentlyPlaying) "일시정지" else "재생",
                        tint = if (isCurrentlyPlaying) RecordRed else Color.Unspecified
                    )
                }
            }
        }
        Divider()

        // ───────────── 옵션 영역 (확장되었을 때만 표시) ─────────────
        if (isExpanded) {
            if (isInTrash) {
                // 휴지통 화면: 복원 + 완전 삭제
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    // 1) 복원
                    TextButton(
                        onClick = {
                            // 재생 중이면 정지
                            if (isCurrentlyPlaying) {
                                stopPlayback(context)
                            }
                            viewModel.restoreRecording(recording)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = "복원"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("복원")
                    }

                    // 2) 완전 삭제
                    TextButton(
                        onClick = {
                            deletePermanently = true
                            showDeleteDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "삭제",
                            tint = Color.Red
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("삭제", color = Color.Red)
                    }
                }
            } else {
                // 메인 목록: 이름 변경 + 휴지통으로 이동 + 완전 삭제
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    // 1) 이름 변경
                    TextButton(
                        onClick = {
                            showRenameDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "이름 변경"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("이름 변경")
                    }

                    // 2) Download 폴더로 내보내기
                    TextButton(
                        onClick = {
                            viewModel.exportRecordingToDownloads(recording) { success ->
                                val msg = if (success) "Download 폴더로 내보냈습니다" else "내보내기에 실패했습니다"
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Download 폴더로 내보내기"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download 폴더로 내보내기")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 3) 카테고리 이동
                    TextButton(
                        onClick = {
                            showCategoryDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = "카테고리 이동"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("카테고리 이동")
                    }


                    // 4) 휴지통으로 이동
                    TextButton(
                        onClick = {
                            deletePermanently = false   // 휴지통으로 이동
                            showDeleteDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "휴지통으로 이동"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("휴지통으로 이동")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 5) 완전 삭제 (바로 삭제)
                    TextButton(
                        onClick = {
                            deletePermanently = true    // ★ 휴지통을 거치지 않고 삭제
                            showDeleteDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "완전 삭제",
                            tint = Color.Red
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("완전 삭제", color = Color.Red)
                    }
                }
            }
        }
    }

    // ───────────── 카테고리 선택 다이얼로그 ─────────────
    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("카테고리 선택") },
            text = {
                LazyColumn {
                    items(categories) { category ->
                        Text(
                            text = category.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.moveRecordingToCategory(recording.id, category.name)
                                    showCategoryDialog = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    // ───────────── 이름 변경 다이얼로그 ─────────────
    if (showRenameDialog) {
        var newName by remember {
            mutableStateOf(recording.fileName.substringBeforeLast("."))
        }

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

    // ───────────── 삭제 확인 다이얼로그 ─────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    if (isInTrash || deletePermanently) "파일 삭제" else "휴지통으로 이동"
                )
            },
            text = {
                Text(
                    if (isInTrash || deletePermanently)
                        "이 녹음 파일을 완전히 삭제하시겠습니까?"
                    else
                        "이 녹음 파일을 휴지통으로 이동하시겠습니까?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isCurrentlyPlaying) {
                        stopPlayback(context)
                    }

                    // 메인 목록 + 휴지통/완전삭제 여부에 따라 플래그 계산
                    val moveToTrashFlag = !isInTrash && !deletePermanently

                    viewModel.deleteRecording(
                        recording = recording,
                        moveToTrash = moveToTrashFlag
                    )

                    showDeleteDialog = false
                }) {
                    Text(
                        text = if (isInTrash || deletePermanently) "삭제" else "이동",
                        color = Color.Red
                    )
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
        }
        // 같은 파일이 재생 중인 경우 - 일시정지 (여기서 멈춤)
        currentPlayingId == recording.id && isPlaying -> {
            pausePlayback(context)
        }
        // 같은 파일이 일시정지 상태인 경우 - 재개 (멈춘 자리에서 재생)
        currentPlayingId == recording.id && !isPlaying -> {
            resumePlayback(context)
        }
        // 아무것도 재생 중이지 않은 경우 - 새 파일 재생
        else -> {
            playRecording(context, recording)
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
    com.krdonon.microphone.service.PlaybackStateManager.updateIsPlaying(false)
    PlaybackState.isPlaying.value = false
}

fun resumePlayback(context: Context) {
    val intent = Intent(context, PlaybackService::class.java).apply {
        action = PlaybackService.ACTION_RESUME
    }
    context.startService(intent)
    com.krdonon.microphone.service.PlaybackStateManager.updateIsPlaying(true)
    PlaybackState.isPlaying.value = true
}

fun stopPlayback(context: Context) {
    val intent = Intent(context, PlaybackService::class.java).apply {
        action = PlaybackService.ACTION_STOP
    }
    context.startService(intent)
    com.krdonon.microphone.service.PlaybackStateManager.reset()
    PlaybackState.currentPlayingId.value = null
    PlaybackState.isPlaying.value = false
}
