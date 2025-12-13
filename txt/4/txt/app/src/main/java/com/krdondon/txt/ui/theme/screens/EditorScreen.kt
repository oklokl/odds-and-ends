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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdondon.txt.utils.FileManager
import kotlinx.coroutines.launch
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
    var status by remember { mutableStateOf<String?>(null) }

    // A4 가이드 토글 (UI만 변경, 파일 내용은 그대로)
    var a4Guide by remember { mutableStateOf(true) }

    // PdfExporter 기본값과 동일한 A4 기준(줄바꿈 기준)
    val fontSizePt = 12f
    val lineSpacing = 1.4f
    val lineHeightSp = (fontSizePt * lineSpacing).sp          // 16.8sp
    val previewStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = fontSizePt.sp,
        lineHeight = lineHeightSp
    )

    val pageHeightPt = 842f
    val marginPt = 40f
    val maxLinesPerPage = (((pageHeightPt - marginPt * 2) / (fontSizePt * lineSpacing))
        .toInt())
        .coerceAtLeast(1) // 보통 45

    val totalLines = remember(tfv.text) {
        tfv.text.replace("\r\n", "\n").split("\n").size.coerceAtLeast(1)
    }
    val totalPages = remember(totalLines) {
        ceil(totalLines / maxLinesPerPage.toDouble()).toInt().coerceAtLeast(1)
    }

    LaunchedEffect(tfv.text) {
        val nearBottom = (scrollState.maxValue - scrollState.value) < 120
        if (nearBottom) scrollState.scrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextButton(onClick = {
                        renameValue = fileName.substringBeforeLast(".")
                        showRenameDialog = true
                    }) {
                        Column {
                            Text(fileName)
                            if (currentHasPdf) {
                                Text(
                                    "PDF linked",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    // A4 guide toggle
                    TextButton(onClick = { a4Guide = !a4Guide }) {
                        Text(if (a4Guide) "A4:ON" else "A4:OFF")
                    }
                    TextButton(onClick = { showSaveDialog = true }) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            // 편집 영역: A4 가이드 ON이면 페이지 경계선을 배경에 그려줌
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

                if (a4Guide) {
                    val lineHeightPx = with(density) { lineHeightSp.toPx() }
                    val topPadPx = with(density) { 16.dp.toPx() } // TextField 내부 기본 패딩에 맞춘 “대략” 보정
                    val pageBreakColor = MaterialTheme.colorScheme.outlineVariant

                    Canvas(modifier = Modifier.matchParentSize()) {
                        val h = size.height

                        // 2페이지 시작 = 45줄, 3페이지 시작 = 90줄...
                        for (page in 2..totalPages) {
                            val breakLineIndex = (page - 1) * maxLinesPerPage
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
                    text = "A4 미리보기: ${totalPages}p (1p당 약 ${maxLinesPerPage}줄, 줄바꿈 기준)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

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
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save to Downloads") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    // TXT only save (overwrite if exists)
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
                                        // If has PDF pair, update PDF too
                                        if (currentHasPdf) {
                                            val pdfUri = FileManager.findLinkedPdfUri(context, name)
                                            if (pdfUri != null) {
                                                FileManager.overwritePdfUri(context, pdfUri, tfv.text)
                                            }
                                        }
                                        uri
                                    } else {
                                        val newUri = FileManager.saveTxtToDownloads(context, name, tfv.text)
                                        currentUri = newUri
                                        newUri
                                    }
                                }.onSuccess {
                                    onSaveAndBack()
                                }.onFailure {
                                    status = "Save failed: ${it.message}"
                                }
                            }
                        }
                    ) {
                        Text(if (currentHasPdf) "Save TXT + PDF" else "Save TXT only")
                    }

                    // Save as TXT + PDF pair (only if not already paired)
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
                                        onSaveAndBack()
                                    }.onFailure {
                                        status = "Save failed: ${it.message}"
                                    }
                                }
                            }
                        ) { Text("Save TXT + PDF") }
                    }

                    // PDF only (export)
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
                                    onSaveAndBack()
                                }.onFailure {
                                    status = "PDF save failed: ${it.message}"
                                }
                            }
                        }
                    ) { Text("Export PDF only (new file)") }
                }
            },
            confirmButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Close") } }
        )
    }
}
