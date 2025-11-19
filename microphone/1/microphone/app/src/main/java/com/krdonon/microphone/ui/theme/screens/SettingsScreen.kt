package com.krdonon.microphone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdonon.microphone.data.model.*
import com.krdonon.microphone.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    var showQualityDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("음성 녹음 설정") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "뒤로")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // 녹음 음질
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("녹음 음질", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        settings.audioQuality.label,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showQualityDialog = true }
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 스테레오 녹음
                    SettingSwitch(
                        title = "스테레오로 녹음",
                        checked = settings.stereoRecording,
                        onCheckedChange = { viewModel.updateStereoRecording(it) }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 녹음 중 전화 차단
                    SettingSwitch(
                        title = "녹음 중 전화 차단",
                        checked = settings.blockCallsWhileRecording,
                        onCheckedChange = { viewModel.updateBlockCalls(it) }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 다음 녹음 자동재생
                    SettingSwitch(
                        title = "다음 녹음 자동재생",
                        checked = settings.autoPlayNext,
                        onCheckedChange = { viewModel.updateAutoPlayNext(it) }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 저장위치
                    Text("저장위치", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        when (settings.storageLocation) {
                            StorageLocation.INTERNAL -> "내장 저장공간"
                            StorageLocation.SD_CARD -> "SD 카드"
                            StorageLocation.OTG -> "OTG"
                        },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // 마이크 녹음 위치 (핵심 기능)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "마이크 녹음 위치",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                viewModel.updateMicrophonePosition(MicrophonePosition.TOP)
                            }
                        ) {
                            RadioButton(
                                selected = settings.microphonePosition == MicrophonePosition.TOP,
                                onClick = { viewModel.updateMicrophonePosition(MicrophonePosition.TOP) }
                            )
                            Text("상단", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                        
                        Switch(
                            checked = settings.microphonePosition == MicrophonePosition.BOTTOM,
                            onCheckedChange = {
                                val newPosition = if (it) MicrophonePosition.BOTTOM else MicrophonePosition.TOP
                                viewModel.updateMicrophonePosition(newPosition)
                            }
                        )
                        
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                viewModel.updateMicrophonePosition(MicrophonePosition.BOTTOM)
                            }
                        ) {
                            RadioButton(
                                selected = settings.microphonePosition == MicrophonePosition.BOTTOM,
                                onClick = { viewModel.updateMicrophonePosition(MicrophonePosition.BOTTOM) }
                            )
                            Text("하단", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            
            // 오디오 재생
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("오디오 재생", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("M4A 확장자")
                        RadioButton(
                            selected = settings.audioFormat == AudioFormat.M4A,
                            onClick = { viewModel.updateAudioFormat(AudioFormat.M4A) }
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MP3 확장자")
                        RadioButton(
                            selected = settings.audioFormat == AudioFormat.MP3,
                            onClick = { viewModel.updateAudioFormat(AudioFormat.MP3) }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 블루투스 마이크
                    SettingSwitch(
                        title = "사용 가능할 때 블루투스 마이크 사용",
                        checked = settings.useBluetoothMic,
                        onCheckedChange = { viewModel.updateUseBluetoothMic(it) }
                    )
                }
            }
            
            // 개인정보
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("개인정보", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        "개인정보 처리방침",
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { /* 개인정보 처리방침 열기 */ }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        "이 앱이 사용하는 권한",
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { /* 권한 정보 보기 */ }
                    )
                }
            }
        }
    }
    
    // 음질 선택 다이얼로그
    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("녹음 음질") },
            text = {
                Column {
                    AudioQuality.values().forEach { quality ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateAudioQuality(quality)
                                    showQualityDialog = false
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(quality.label)
                            RadioButton(
                                selected = settings.audioQuality == quality,
                                onClick = {
                                    viewModel.updateAudioQuality(quality)
                                    showQualityDialog = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }
}

@Composable
fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
