package com.krdondon.txt.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.krdondon.txt.utils.FileManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

private const val LARGE_PASTE_WARNING_THRESHOLD = 200_000
private const val HARD_TEXT_LIMIT = 1_500_000

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
    var text by remember(initialText) { mutableStateOf(initialText) }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(fileName.substringBeforeLast(".")) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var pendingDraftText by remember { mutableStateOf<String?>(null) }
    var pendingDraftTs by remember { mutableLongStateOf(0L) }
    var showLargePasteDialog by remember { mutableStateOf(false) }
    var pendingClipboardText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(existingUri, initialFileName) {
        status = null
    }

    LaunchedEffect(status) {
        if (status != null) {
            delay(2500)
            status = null
        }
    }

    val totalLines = remember(text) {
        text.replace("\r\n", "\n").split("\n").size.coerceAtLeast(1)
    }
    val effectiveLinesPerPage = 45
    val totalPages = remember(totalLines, effectiveLinesPerPage) {
        ceil(totalLines / effectiveLinesPerPage.toDouble()).toInt().coerceAtLeast(1)
    }

    val prefs = remember { context.getSharedPreferences("txt_drafts", Context.MODE_PRIVATE) }
    val draftDocId = remember { existingUri?.toString() ?: "NEW:${UUID.randomUUID()}" }

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

    fun appendClipboardText(clipText: String) {
        val sanitized = clipText.replace("\u0000", "")
        val newLength = text.length + sanitized.length
        if (newLength > HARD_TEXT_LIMIT) {
            status = "붙여넣기 취소: ${HARD_TEXT_LIMIT / 1000}KB 이상은 한 번에 넣지 않도록 제한했습니다"
            return
        }
        text += sanitized
        status = "붙여넣기 완료 (${sanitized.length}자)"
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val clipText = item?.coerceToText(context)?.toString().orEmpty()

        if (clipText.isEmpty()) {
            status = "클립보드에 텍스트가 없습니다"
            return
        }

        if (clipText.length >= LARGE_PASTE_WARNING_THRESHOLD) {
            pendingClipboardText = clipText
            showLargePasteDialog = true
        } else {
            appendClipboardText(clipText)
        }
    }

    LaunchedEffect(Unit) {
        val draftText = readDraftText()
        if (draftText != null && draftText != initialText) {
            pendingDraftText = draftText
            pendingDraftTs = readDraftTs()
            clearDraft()
            showRestoreDialog = true
        } else if (draftText != null && draftText == initialText) {
            clearDraft()
        }
    }

    LaunchedEffect(text) {
        delay(40_000)
        saveDraft("idle")
    }

    LaunchedEffect(totalPages) {
        if (totalPages > lastDraftSavedPageCount) saveDraft("page")
    }

    fun handleBackAndClearDraft() {
        clearDraft()
        status = null
        onBack()
    }

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
                        Text(text = topTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    TextButton(onClick = { pasteFromClipboard() }) { Text("Paste") }
                    TextButton(onClick = { showSaveDialog = true }) { Text("Save") }
                    TextButton(onClick = { showTopMenu = true }) { Text("⋮") }
                    DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("클립보드 붙여넣기 사용 권장") },
                            onClick = { showTopMenu = false }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp)
        ) {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    EditText(ctx).apply {
                        setText(text)
                        gravity = Gravity.TOP or Gravity.START
                        inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        minLines = 20
                        maxLines = Int.MAX_VALUE
                        isVerticalScrollBarEnabled = true
                        overScrollMode = EditText.OVER_SCROLL_IF_CONTENT_SCROLLS
                        setHorizontallyScrolling(false)
                        imeOptions = imeOptions or
                                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        setRawInputType(inputType)
                        importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        isSaveEnabled = false
                        setTextIsSelectable(true)

                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                val next = s?.toString().orEmpty()
                                if (next != text) text = next
                            }
                        })
                    }
                },
                update = { editText ->
                    val current = editText.text?.toString().orEmpty()
                    if (current != text) {
                        val sel = editText.selectionStart.coerceAtLeast(0)
                        editText.setText(text)
                        val newSel = sel.coerceAtMost(text.length)
                        editText.setSelection(newSel)
                    }
                }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "문자수 ${text.length} / 페이지 약 ${totalPages}p",
                style = MaterialTheme.typography.bodySmall
            )
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showLargePasteDialog && pendingClipboardText != null) {
        AlertDialog(
            onDismissRequest = {
                showLargePasteDialog = false
                pendingClipboardText = null
            },
            title = { Text("큰 텍스트 붙여넣기") },
            text = {
                Text("클립보드 텍스트가 매우 큽니다. 시스템 키보드 붙여넣기 대신 앱 내부 붙여넣기로 넣으면 갤럭시 기기에서 더 안정적입니다. 계속할까요?")
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipText = pendingClipboardText
                    showLargePasteDialog = false
                    pendingClipboardText = null
                    if (clipText != null) appendClipboardText(clipText)
                }) { Text("붙여넣기") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLargePasteDialog = false
                    pendingClipboardText = null
                }) { Text("취소") }
            }
        )
    }

    if (showRestoreDialog && pendingDraftText != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("임시저장본이 있습니다") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("이전에 저장하지 못한 내용이 있습니다. 복원하시겠습니까?")
                    if (pendingDraftTs > 0L) {
                        Text(
                            "임시저장 시간: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(pendingDraftTs))}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    text = pendingDraftText!!
                    lastDraftSavedText = pendingDraftText!!
                    lastDraftSavedPageCount = totalPages
                    showRestoreDialog = false
                    pendingDraftText = null
                }) { Text("복원") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreDialog = false
                    pendingDraftText = null
                }) { Text("삭제") }
            }
        )
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
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

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
                                        FileManager.overwriteTxtUri(context, uri, text)
                                        if (currentHasPdf) {
                                            val pdfUri = FileManager.findLinkedPdfUri(context, name)
                                            if (pdfUri != null) FileManager.overwritePdfUri(context, pdfUri, text)
                                        }
                                    } else {
                                        currentUri = FileManager.saveTxtToDownloads(context, name, text)
                                    }
                                }.onSuccess {
                                    clearDraft()
                                    status = null
                                    onSaveAndBack()
                                }.onFailure {
                                    status = "Save failed: ${it.message}"
                                }
                            }
                        }
                    ) { Text(if (currentHasPdf) "Save TXT + PDF" else "Save TXT only") }

                    if (!currentHasPdf) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showSaveDialog = false
                                focusManager.clearFocus()
                                scope.launch {
                                    runCatching {
                                        val baseName = fileName.substringBeforeLast(".")
                                        val (txtUri, _) = FileManager.saveTxtAndPdfToDownloads(context, baseName, text)
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
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        onClick = {
                            showSaveDialog = false
                            focusManager.clearFocus()
                            scope.launch {
                                runCatching {
                                    FileManager.savePdfToDownloads(context, fileName, text)
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

private fun keepExtensionShort(name: String, maxTotal: Int): String {
    if (name.length <= maxTotal) return name

    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) {
        return name.take(maxTotal - 1) + "…"
    }

    val ext = name.substring(dot)
    val baseMax = (maxTotal - ext.length - 1).coerceAtLeast(1)
    return name.take(baseMax) + "…" + ext
}
