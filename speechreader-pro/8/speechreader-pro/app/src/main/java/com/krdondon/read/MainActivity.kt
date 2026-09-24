package com.krdondon.read

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krdondon.read.compliance.AgeSignalsCompliance
import com.krdondon.read.compliance.UserAgeCategory
import com.krdondon.read.data.model.TxtDocument
import com.krdondon.read.service.PlaybackStatus
import com.krdondon.read.ui.BatteryExemptionDialog
import com.krdondon.read.ui.CreateEditDocumentDialog
import com.krdondon.read.ui.DocumentListScreen
import com.krdondon.read.ui.DocumentViewModel
import com.krdondon.read.ui.ReaderScreen
import com.krdondon.read.ui.TtsLogDialog
import com.krdondon.read.ui.TtsSettingsDialog
import com.krdondon.read.ui.theme.MyApplicationTheme
import com.krdondon.read.util.TtsLogManager

sealed class Screen {
    data object List : Screen()
    data class Reader(val docId: Long) : Screen()
}

class MainActivity : ComponentActivity() {

    private val viewModel: DocumentViewModel by viewModels()
    private lateinit var ageSignalsCompliance: AgeSignalsCompliance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // TTS 진단 로그 초기화 (기존 로그 덮어쓰기)
        TtsLogManager.initOnAppStart(this)

        // Google Play Age Signals API - 런타임 연령 신호 요청
        ageSignalsCompliance = AgeSignalsCompliance(this)
        ageSignalsCompliance.checkAgeSignals(this) { category ->
            when (category) {
                UserAgeCategory.MINOR -> {
                    // 미성년자(Child/Minor): 연령 적합 경험 보장 (개인정보 보호 강화)
                    android.util.Log.i("MainActivity", "Age Signal: MINOR (보호 정책 모드 적용)")
                }
                UserAgeCategory.ADULT -> {
                    // 성인: 일반 모드
                    android.util.Log.i("MainActivity", "Age Signal: ADULT")
                }
                UserAgeCategory.UNKNOWN -> {
                    // 확인 불가 / 오류 / 미공유: 안전한 기본 상태로 처리
                    android.util.Log.i("MainActivity", "Age Signal: UNKNOWN (안전 상태 적용)")
                }
            }
        }

        val incomingDocId = intent?.getLongExtra("doc_id", -1L) ?: -1L

        setContent {
            MyApplicationTheme {
                MainAppContent(
                    viewModel = viewModel,
                    initialDocId = incomingDocId,
                    onKeepScreenOnChange = { keep ->
                        if (keep) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val docId = intent.getLongExtra("doc_id", -1L)
        if (docId != -1L) {
            viewModel.selectDocumentById(docId)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // Hint system to reclaim memory when UI is hidden or memory is low
            System.gc()
        }
    }

    override fun onStart() {
        super.onStart()
        TtsLogManager.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        TtsLogManager.onAppBackgrounded(this)
    }
}

@Composable
fun MainAppContent(
    viewModel: DocumentViewModel,
    initialDocId: Long = -1L,
    onKeepScreenOnChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val documents by viewModel.allDocuments.collectAsStateWithLifecycle()
    val activeDocument by viewModel.activeDocument.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle()
    val messageEvent by viewModel.messageEvent.collectAsStateWithLifecycle()

    var currentScreen by remember {
        mutableStateOf(
            if (initialDocId != -1L) Screen.Reader(initialDocId) else Screen.List
        )
    }

    // Dialog States
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingDocument by remember { mutableStateOf<TxtDocument?>(null) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var showTtsSettingsDialog by remember { mutableStateOf(false) }
    var showTtsLogDialog by remember { mutableStateOf(false) }

    // Screen Keep-On Window Flag listener
    LaunchedEffect(ttsState.keepScreenOn) {
        onKeepScreenOnChange(ttsState.keepScreenOn)
    }

    // Notification permission request for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        viewModel.checkBatteryOptimization(context)
    }

    // Show Snackbar messages
    LaunchedEffect(messageEvent) {
        messageEvent?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessage()
        }
    }

    // Document Picker Launcher (Open .txt file)
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importTxtFile(context, it) { newId ->
                currentScreen = Screen.Reader(newId)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val screen = currentScreen) {
                is Screen.List -> {
                    DocumentListScreen(
                        documents = documents,
                        ttsState = ttsState,
                        onCreateClick = { showCreateDialog = true },
                        onImportClick = {
                            openDocumentLauncher.launch(
                                arrayOf("text/plain", "text/*", "*/*")
                            )
                        },
                        onReadClick = { doc ->
                            viewModel.selectDocument(doc)
                            currentScreen = Screen.Reader(doc.id)
                        },
                        onEditClick = { doc ->
                            editingDocument = doc
                        },
                        onDeleteClick = { id ->
                            viewModel.deleteDocument(id)
                        },
                        onBatteryClick = {
                            showBatteryDialog = true
                        },
                        onPlayPauseTts = {
                            if (ttsState.status == PlaybackStatus.PLAYING) {
                                viewModel.pauseTts(context)
                            } else {
                                viewModel.resumeTts(context)
                            }
                        },
                        onStopTts = {
                            viewModel.stopTts(context)
                        }
                    )
                }

                is Screen.Reader -> {
                    val docToRead = activeDocument ?: documents.find { it.id == screen.docId }
                    if (docToRead != null) {
                        BackHandler {
                            currentScreen = Screen.List
                        }

                        ReaderScreen(
                            document = docToRead,
                            ttsState = ttsState,
                            onBackClick = {
                                currentScreen = Screen.List
                            },
                            onPlayTts = { startIndex ->
                                viewModel.playTts(context, docToRead, startIndex)
                            },
                            onPauseTts = {
                                viewModel.pauseTts(context)
                            },
                            onResumeTts = {
                                viewModel.resumeTts(context)
                            },
                            onStopTts = {
                                viewModel.stopTts(context)
                            },
                            onSeekToSentence = { index ->
                                viewModel.seekToSentence(context, index)
                            },
                            onSaveBookmark = { sentenceIndex, charOffset ->
                                viewModel.saveBookmark(docToRead.id, charOffset, sentenceIndex)
                            },
                            onToggleKeepScreenOn = {
                                viewModel.toggleKeepScreenOn()
                            },
                            onOpenTtsSettings = {
                                showTtsSettingsDialog = true
                            },
                            onToggleLoopMode = {
                                viewModel.toggleLoopMode(context)
                            }
                        )
                    } else {
                        // Document not found or was deleted
                        LaunchedEffect(Unit) {
                            currentScreen = Screen.List
                        }
                    }
                }
            }

            // Create TXT Dialog
            if (showCreateDialog) {
                CreateEditDocumentDialog(
                    initialTitle = "",
                    initialContent = "",
                    isEditing = false,
                    onDismiss = { showCreateDialog = false },
                    onConfirm = { title, content ->
                        viewModel.createDocument(title, content) { newId ->
                            currentScreen = Screen.Reader(newId)
                        }
                        showCreateDialog = false
                    }
                )
            }

            // Edit TXT Dialog
            editingDocument?.let { doc ->
                CreateEditDocumentDialog(
                    initialTitle = doc.title,
                    initialContent = doc.content,
                    isEditing = true,
                    onDismiss = { editingDocument = null },
                    onConfirm = { title, content ->
                        viewModel.updateDocument(doc.id, title, content)
                        editingDocument = null
                    }
                )
            }

            // Battery Exemption Dialog
            if (showBatteryDialog) {
                BatteryExemptionDialog(
                    isIgnored = ttsState.isBatteryOptimizationIgnored,
                    onDismiss = { showBatteryDialog = false },
                    onRequestExemption = {
                        viewModel.requestBatteryOptimizationExemption(context)
                    }
                )
            }

            // TTS Settings Dialog
            if (showTtsSettingsDialog) {
                TtsSettingsDialog(
                    initialRate = ttsState.speechRate,
                    initialPitch = ttsState.speechPitch,
                    initialEngine = ttsState.engineType,
                    onDismiss = { showTtsSettingsDialog = false },
                    onOpenLog = { showTtsLogDialog = true },
                    onApply = { rate, pitch, engine ->
                        viewModel.applyTtsSettings(context, rate, pitch, engine)
                    }
                )
            }

            // TTS 진단 및 에러 로그 다이얼로그
            if (showTtsLogDialog) {
                TtsLogDialog(
                    onDismiss = { showTtsLogDialog = false }
                )
            }
        }
    }
}

// Retained for compatibility with greeting tests
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
