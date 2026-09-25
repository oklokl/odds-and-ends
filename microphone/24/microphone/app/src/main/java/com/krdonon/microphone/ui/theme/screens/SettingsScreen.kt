package com.krdonon.microphone.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.krdonon.microphone.data.model.*
import com.krdonon.microphone.ui.MainViewModel
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    var showQualityDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }

    // NOTE:
    // 기존 구현은 "권한이 이미 허용되어 있으면" SettingsScreen에 진입할 때마다
    // blockCallsWhileRecording을 강제로 true로 덮어써서,
    // 1) 끄고 다시 켤 때 UI가 켜지지 않거나
    // 2) 화면을 나갔다 들어오면 무조건 ON으로 바뀌는
    // 문제가 생겼습니다.
    //
    // 해결: 스위치를 켤 때만 권한을 요청하고, 권한 결과에 따라 설정 값을 갱신합니다.
    val phonePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        viewModel.updateBlockCalls(allGranted)
    }

    // 설정이 ON인데 권한이 없으면(사용자가 시스템에서 권한을 철회한 경우) 자동으로 OFF로 정합성 맞춤.
    val hasPhonePermissions by remember {
        derivedStateOf {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        }
    }
    LaunchedEffect(settings.blockCallsWhileRecording, hasPhonePermissions) {
        if (settings.blockCallsWhileRecording && !hasPhonePermissions) {
            viewModel.updateBlockCalls(false)
        }
    }


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
                        onCheckedChange = { checked ->
                            if (checked) {
                                phonePermissionsLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_PHONE_STATE,
                                        Manifest.permission.ANSWER_PHONE_CALLS
                                    )
                                )
                            } else {
                                viewModel.updateBlockCalls(false)
                            }
                        }
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
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showStorageDialog = true }
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
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPrivacyPolicyDialog = true }
                            .padding(vertical = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "이 앱이 사용하는 권한",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 앱 설정 페이지로 이동
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            }
                            .padding(vertical = 8.dp)
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

    // 저장위치 선택 다이얼로그
    if (showStorageDialog) {
        AlertDialog(
            onDismissRequest = { showStorageDialog = false },
            title = { Text("저장위치") },
            text = {
                Column {
                    StorageLocation.values().forEach { location ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateStorageLocation(location)
                                    showStorageDialog = false
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                when (location) {
                                    StorageLocation.INTERNAL -> "내장 저장공간"
                                    StorageLocation.SD_CARD -> "SD 카드"
                                    StorageLocation.OTG -> "OTG"
                                }
                            )
                            RadioButton(
                                selected = settings.storageLocation == location,
                                onClick = {
                                    viewModel.updateStorageLocation(location)
                                    showStorageDialog = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStorageDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }

    // 개인정보 처리방침 다이얼로그
    if (showPrivacyPolicyDialog) {
        Dialog(
            onDismissRequest = { showPrivacyPolicyDialog = false }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
            ) {
                Column {
                    // 헤더
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "개인정보 처리방침",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showPrivacyPolicyDialog = false }) {
                            Icon(Icons.Default.ArrowBack, "닫기")
                        }
                    }
                    Divider()

                    // WebView로 HTML 표시
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewClient = WebViewClient()
                                // WebSettings 설정
                                val webSettings = this.settings
                                webSettings.javaScriptEnabled = false
                                webSettings.loadWithOverviewMode = true
                                webSettings.useWideViewPort = true
                                loadUrl("file:///android_asset/microphone_updated.html")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
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
