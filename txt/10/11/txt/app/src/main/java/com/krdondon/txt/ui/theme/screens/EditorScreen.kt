package com.krdondon.txt.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdondon.txt.utils.FileManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    context: Context,
    existingUri: Uri?,
    initialFileName: String,
    initialText: String,
    hasPdfPair: Boolean,
    onSaveAndBack: () -> Unit,
    onBack: () -> Unit
) {
    var currentUri by remember { mutableStateOf(existingUri) }
    var currentHasPdf by remember { mutableStateOf(hasPdfPair) }

    var fileName by remember { mutableStateOf(initialFileName) }
    var tfv by remember(initialText) { mutableStateOf(TextFieldValue(initialText)) }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(fileName.substringBeforeLast(".")) }

    var showSaveDialog by remember { mutableStateOf(false) }

    // ✅ 상태 문구(임시저장됨 등)
    var status by remember { mutableStateOf<String?>(null) }

    // A4 guide (UI only)
    var a4Guide by remember { mutableStateOf(true) }
    var guideAdjustLines by remember { mutableIntStateOf(4) }

    // TopBar 메뉴(⋮)로 숨기기
    var showTopMenu by remember { mutableStateOf(false) }

    // ✅ 화면(문서) 들어올 때마다 status 초기화 (재진입 시 이전 문구 남는 문제 해결)
    LaunchedEffect(existingUri, initialFileName) {
        status = null
    }

    // ✅ status는 잠깐 보여주고 자동으로 사라지게(원치 않으면 이 블록 삭제 가능)
    LaunchedEffect(status) {
        if (status != null) {
            delay(2500)
            status = null
        }
    }

    // PdfExporter와 동일한 A4 기준(줄바꿈 기준)
    val fontSizePt = 12f
    val lineSpacing = 1.4f
    val lineHeightSp = (fontSizePt * lineSpacing).sp
    val previewStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = fontSizePt.sp,
        lineHeight = lineHeightSp
    )

    val pageHeightPt = 842f
    val marginPt = 40f
    val maxLinesPerPage = (((pageHeightPt - marginPt * 2) / (fontSizePt * lineSpacing)).toInt())
        .coerceAtLeast(1)

    val effectiveLinesPerPage = (maxLinesPerPage + guideAdjustLines).coerceAtLeast(1)

    val totalLines = remember(tfv.text) {
        tfv.text.replace("\r\n", "\n").split("\n").size.coerceAtLeast(1)
    }
    val totalPages = remember(totalLines, effectiveLinesPerPage) {
        ceil(totalLines / effectiveLinesPerPage.toDouble()).toInt().coerceAtLeast(1)
    }

    // -------------------------
    // Draft(임시저장)
    // -------------------------
    val prefs = remember { context.getSharedPreferences("txt_drafts", Context.MODE_PRIVATE) }

    val draftDocId = remember {
        existingUri?.toString() ?: "NEW:${UUID.randomUUID()}"
    }

    fun draftKey(suffix: String) = "${draftDocId}:$suffix"

    fun readDraftText(): String? = prefs.getString(draftKey("text"), null)
    fun readDraftTs(): Long = prefs.getLong(draftKey("ts"), 0L)

    fun clearDraft() {
        prefs.edit()
            .remove(draftKey("text"))
            .remove(draftKey("ts"))
            .remove(draftKey("name"))
            .apply()
    }

    var lastDraftSavedText by remember { mutableStateOf(readDraftText() ?: "") }
    var lastDraftSavedPageCount by remember { mutableIntStateOf(1) }

    fun saveDraft(reason: String) {
        val text = tfv.text
        if (text.isBlank()) return
        if (text == lastDraftSavedText) return

        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(draftKey("text"), text)
            .putLong(draftKey("ts"), now)
            .putString(draftKey("name"), fileName)
            .apply()

        lastDraftSavedText = text
        lastDraftSavedPageCount = totalPages

        status = when (reason) {
            "idle" -> "임시저장됨(40초)"
            "page" -> "임시저장됨(페이지 넘어감)"
            else -> "임시저장됨"
        }
    }

    // -------------------------
    // 재진입 시 드래프트 처리: 발견 즉시 정리(쌓임 방지) + 복원/삭제 선택
    // -------------------------
    var showRestoreDialog by remember { mutableStateOf(false) }
    var pendingDraftText by remember { mutableStateOf<String?>(null) }
    var pendingDraftTs by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val draftText = readDraftText()
        if (draftText != null && draftText != initialText) {
            pendingDraftText = draftText
            pendingDraftTs = readDraftTs()
            clearDraft() // ✅ 다시 켰을 때 발견 즉시 정리
            showRestoreDialog = true
        } else if (draftText != null && draftText == initialText) {
            clearDraft()
        }
    }

    // 40초 무입력 → 임시저장 (텍스트 변경 시마다 타이머 리셋)
    LaunchedEffect(tfv.text) {
        delay(40_000)
        saveDraft("idle")
    }

    // A4 페이지 증가 순간 → 임시저장
    LaunchedEffect(totalPages) {
        if (totalPages > lastDraftSavedPageCount) {
            saveDraft("page")
        }
    }

    // 하단 따라가기(기존)
    LaunchedEffect(tfv.text) {
        val nearBottom = (scrollState.maxValue - scrollState.value) < 120
        if (nearBottom) scrollState.scrollTo(scrollState.maxValue)
    }

    // Back = 드래프트 정리 후 나가기
    fun handleBackAndClearDraft() {
        clearDraft()
        status = null
        onBack()
    }

    // 상단바에 표시할 파일명(확장자 유지 + 말줄임)
    val topTitle = remember(fileName) { keepExtensionShort(fileName, 26) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = { handleBackAndClearDraft() }) { Text("Back") }
                },
                title = {
                    TextButton(onClick = {
                        renameValue = fileName.substringBeforeLast(".")
                        showRenameDialog = true
                    }) {
                        Text(
                            text = topTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showSaveDialog = true }) { Text("Save") }

                    TextButton(onClick = { showTopMenu = true }) { Text("⋮") }

                    DropdownMenu(
                        expanded = showTopMenu,
                        onDismissRequest = { showTopMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (a4Guide) "A4 guide: ON" else "A4 guide: OFF") },
                            onClick = {
                                a4Guide = !a4Guide
                                showTopMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Adjust lines: ${if (guideAdjustLines >= 0) "+$guideAdjustLines" else "$guideAdjustLines"}") },
                            onClick = { /* 표시용 */ }
                        )
                        DropdownMenuItem(
                            text = { Text("Adj -1") },
                            onClick = {
                                guideAdjustLines = (guideAdjustLines - 1).coerceIn(-20, 20)
                                showTopMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Adj +1") },
                            onClick = {
                                guideAdjustLines = (guideAdjustLines + 1).coerceIn(-20, 20)
                                showTopMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Adj reset (+4)") },
                            onClick = {
                                guideAdjustLines = 4
                                showTopMenu = false
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

                if (a4Guide) {
                    val lineHeightPx = with(density) { lineHeightSp.toPx() }
                    val topPadPx = with(density) { 16.dp.toPx() }
                    val pageBreakColor = MaterialTheme.colorScheme.outlineVariant

                    Canvas(modifier = Modifier.matchParentSize()) {
                        val h = size.height
                        for (page in 2..totalPages) {
                            val breakLineIndex = (page - 1) * effectiveLinesPerPage
                            val y = topPadPx + (breakLineIndex * lineHeightPx) - scrollState.value
                            if (y > -50f && y < h + 50f) {
                                drawLine(
                                    color = pageBreakColor,
                                    start = androidx.compose.ui.geometry.Offset(0f, y),
                                    end = androidx.compose.ui.geometry.Offset(size.width, y),
                                    strokeWidth = 2f
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = tfv,
                    onValueChange = { tfv = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    placeholder = { Text("Write here...") },
                    textStyle = if (a4Guide) previewStyle else LocalTextStyle.current,
                    colors = if (a4Guide) {
                        OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    }
                )
            }

            if (a4Guide) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A4 미리보기: ${totalPages}p (1p당 약 ${effectiveLinesPerPage}줄, 보정 ${if (guideAdjustLines >= 0) "+$guideAdjustLines" else "$guideAdjustLines"}줄)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // Restore dialog (드래프트는 이미 정리됨)
    if (showRestoreDialog && pendingDraftText != null) {
        AlertDialog(
            onDismissRequest = { /* 강제 선택 */ },
            title = { Text("임시저장본이 있습니다") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("이전에 저장하지 못한 내용이 있습니다. 복원하시겠습니까?")
                    if (pendingDraftTs > 0L) {
                        Text(
                            "임시저장 시간: ${
                                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                    .format(java.util.Date(pendingDraftTs))
                            }",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    tfv = TextFieldValue(pendingDraftText!!)
                    lastDraftSavedText = pendingDraftText!!
                    lastDraftSavedPageCount = totalPages
                    status = null
                    showRestoreDialog = false
                    pendingDraftText = null
                }) { Text("복원") }
            },
            dismissButton = {
                TextButton(onClick = {
                    status = null
                    showRestoreDialog = false
                    pendingDraftText = null
                }) { Text("삭제") }
            }
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("File Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = renameValue.trim().ifEmpty { "doc" }
                    val newName = FileManager.ensureExtension(trimmed, "txt")
                    fileName = newName
                    showRenameDialog = false

                    val uri = currentUri
                    if (uri != null) {
                        scope.launch {
                            val ok = FileManager.renameInDownloads(context, uri, newName)
                            status = if (ok) "Renamed" else "Rename failed"
                        }
                    } else {
                        status = "Renamed: $fileName"
                    }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Save dialog (Close = 드래프트 정리)
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = {
                clearDraft()
                status = null
                showSaveDialog = false
            },
            title = { Text("Save to Downloads") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showSaveDialog = false
                            focusManager.clearFocus()

                            scope.launch {
                                runCatching {
                                    val name = FileManager.ensureExtension(fileName, "txt")
                                    fileName = name

                                    val uri = currentUri
                                    if (uri != null) {
                                        FileManager.overwriteTxtUri(context, uri, tfv.text)
                                        if (currentHasPdf) {
                                            val pdfUri = FileManager.findLinkedPdfUri(context, name)
                                            if (pdfUri != null) {
                                                FileManager.overwritePdfUri(context, pdfUri, tfv.text)
                                            }
                                        }
                                    } else {
                                        val newUri = FileManager.saveTxtToDownloads(context, name, tfv.text)
                                        currentUri = newUri
                                    }
                                }.onSuccess {
                                    // ✅ 저장 성공 시: 드래프트 + 상태문구 모두 정리
                                    clearDraft()
                                    status = null
                                    onSaveAndBack()
                                }.onFailure {
                                    status = "Save failed: ${it.message}"
                                }
                            }
                        }
                    ) {
                        Text(if (currentHasPdf) "Save TXT + PDF" else "Save TXT only")
                    }

                    if (!currentHasPdf) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showSaveDialog = false
                                focusManager.clearFocus()

                                scope.launch {
                                    runCatching {
                                        val baseName = fileName.substringBeforeLast(".")
                                        val (txtUri, _) = FileManager.saveTxtAndPdfToDownloads(
                                            context, baseName, tfv.text
                                        )
                                        currentUri = txtUri
                                        fileName = FileManager.ensureExtension(baseName, "txt")
                                        currentHasPdf = true
                                    }.onSuccess {
                                        clearDraft()
                                        status = null
                                        onSaveAndBack()
                                    }.onFailure {
                                        status = "Save failed: ${it.message}"
                                    }
                                }
                            }
                        ) { Text("Save TXT + PDF") }
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ),
                        onClick = {
                            showSaveDialog = false
                            focusManager.clearFocus()

                            scope.launch {
                                runCatching {
                                    FileManager.savePdfToDownloads(context, fileName, tfv.text)
                                }.onSuccess {
                                    clearDraft()
                                    status = null
                                    onSaveAndBack()
                                }.onFailure {
                                    status = "PDF save failed: ${it.message}"
                                }
                            }
                        }
                    ) { Text("Export PDF only (new file)") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clearDraft()
                    status = null
                    showSaveDialog = false
                }) { Text("Close") }
            }
        )
    }
}

/**
 * 파일명이 길어도 확장자(.txt)는 최대한 유지하면서 짧게 표시하기
 */
private fun keepExtensionShort(name: String, maxTotal: Int): String {
    if (name.length <= maxTotal) return name

    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) {
        return name.take(maxTotal - 1) + "…"
    }

    val ext = name.substring(dot) // ".txt"
    val baseMax = (maxTotal - ext.length - 1).coerceAtLeast(1)
    val base = name.substring(0, dot)

    return base.take(baseMax) + "…" + ext
}
