package com.krdonon.microphone.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdonon.microphone.data.model.RecordingFile
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "모든 녹음 파일",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "녹음 파일 ${recordings.size}개",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
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
                            onClick = { showMenu = false },
                            leadingIcon = { Icon(Icons.Default.MusicNote, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("음성 녹음") },
                            onClick = { showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Mic, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("휴지통") },
                            onClick = { showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Delete, null) }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("카테고리 미지정") },
                            onClick = { showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Folder, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("카테고리 관리") },
                            onClick = { showMenu = false }
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            items(recordings) { recording ->
                RecordingItem(recording, viewModel)
            }
        }
    }
}

@Composable
fun RecordingItem(recording: RecordingFile, viewModel: MainViewModel) {
    val dateFormat = SimpleDateFormat("M월 d일 오전 h:mm", Locale.KOREAN)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* 재생 */ }
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
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                recording.durationFormatted,
                fontSize = 14.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = { /* 재생 */ }) {
                Icon(Icons.Default.PlayArrow, "재생")
            }
        }
    }
    Divider()
}
