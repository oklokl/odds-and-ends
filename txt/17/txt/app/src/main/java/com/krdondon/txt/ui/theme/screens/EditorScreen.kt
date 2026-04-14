package com.krdondon.txt.ui.screens

import android.animation.ObjectAnimator
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
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

private data class SearchMatch(val start: Int, val end: Int)

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
    var text by remember(initialText) { mutableStateOf(initialText.replace("\r", "")) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(initialFileName.substringBeforeLast(".", initialFileName)) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var pendingDraftText by remember { mutableStateOf<String?>(null) }
    var pendingDraftTs by remember { mutableLongStateOf(0L) }
    var showLargePasteDialog by remember { mutableStateOf(false) }
    var pendingClipboardText by remember { mutableStateOf<String?>(null) }

    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<SearchMatch>>(emptyList()) }
    var currentSearchIndex by remember { mutableIntStateOf(-1) }
    var editorRef by remember { mutableStateOf<EditText?>(null) }
    var isApplyingProgrammaticText by remember { mutableStateOf(false) }
    var lastExecutedSearchQuery by remember { mutableStateOf("") }
    var searchCursorPinned by remember { mutableStateOf(false) }

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
        text.split('\n').size.coerceAtLeast(1)
    }
    val effectiveLinesPerPage = 45
    val totalPages = remember(totalLines, effectiveLinesPerPage) {
        ceil(totalLines / effectiveLinesPerPage.toDouble()).toInt().coerceAtLeast(1)
    }

    val prefs = remember { context.getSharedPreferences("txt_drafts", Context.MODE_PRIVATE) }
    val draftDocId = remember(existingUri) { existingUri?.toString() ?: "NEW:${UUID.randomUUID()}" }

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
        val sanitized = clipText.replace("\u0000", "").replace("\r", "")
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

    fun refreshSearchResults(preserveSelection: Boolean = true) {
        val previousMatches = searchMatches
        val matches = findSearchMatches(text, searchQuery)
        searchMatches = matches

        if (matches.isEmpty()) {
            currentSearchIndex = -1
            editorRef?.let { clearSearchHighlight(it, text) }
            if (searchQuery.isNotBlank()) status = "검색 결과 없음"
            return
        }

        val nextIndex = if (
            preserveSelection &&
            currentSearchIndex in previousMatches.indices &&
            previousMatches.getOrNull(currentSearchIndex) == matches.getOrNull(currentSearchIndex)
        ) {
            currentSearchIndex
        } else {
            0
        }

        currentSearchIndex = nextIndex
        editorRef?.let {
            highlightSearchMatches(it, text, matches, nextIndex, moveToMatch = false, pinCursorToMatch = searchCursorPinned)
        }
    }

    fun startSearchFromCursorOrNext() {
        val query = searchQuery.trim()
        if (query.isEmpty()) return

        val editText = editorRef
        val matches = findSearchMatches(text, query)
        searchMatches = matches

        if (matches.isEmpty()) {
            currentSearchIndex = -1
            editText?.let { clearSearchHighlight(it, text) }
            status = "검색 결과 없음"
            lastExecutedSearchQuery = query
            return
        }

        val isSameQuery = lastExecutedSearchQuery.equals(query, ignoreCase = true)
        val targetIndex = if (isSameQuery && currentSearchIndex in matches.indices) {
            (currentSearchIndex + 1) % matches.size
        } else {
            val cursor = editText?.selectionEnd ?: 0
            firstMatchAtOrAfter(matches, cursor)
        }

        currentSearchIndex = targetIndex
        lastExecutedSearchQuery = query
        searchCursorPinned = true
        editText?.let {
            highlightSearchMatches(it, text, matches, targetIndex, moveToMatch = true, pinCursorToMatch = true)
        }
        status = "${targetIndex + 1} / ${matches.size}"
    }

    LaunchedEffect(Unit) {
        val draftText = readDraftText()
        val initTextNoCr = initialText.replace("\r", "")
        if (draftText != null && draftText != initTextNoCr) {
            pendingDraftText = draftText
            pendingDraftTs = readDraftTs()
            clearDraft()
            showRestoreDialog = true
        } else if (draftText != null && draftText == initTextNoCr) {
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

    LaunchedEffect(searchQuery, text) {
        if (searchQuery.isBlank()) {
            searchMatches = emptyList()
            currentSearchIndex = -1
            lastExecutedSearchQuery = ""
            editorRef?.let { clearSearchHighlight(it, text) }
        } else {
            refreshSearchResults(preserveSelection = true)
        }
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
                    TextButton(onClick = { handleBackAndClearDraft() }) {
                        Text("Back")
                    }
                },
                title = {
                    TextButton(onClick = {
                        renameValue = fileName.substringBeforeLast(".", fileName)
                        showRenameDialog = true
                    }) {
                        Text(text = topTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    TextButton(onClick = { pasteFromClipboard() }) { Text("Paste") }
                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
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
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .padding(16.dp)
        ) {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    SafeKineticEditText(ctx).apply {
                        editorRef = this
                        setText(text)
                        gravity = Gravity.TOP or Gravity.START
                        inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                        minLines = 20
                        maxLines = Int.MAX_VALUE
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        isScrollbarFadingEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        setHorizontallyScrolling(false)
                        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        isSaveEnabled = false
                        setTextIsSelectable(false)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setLineSpacing(0f, 1.08f)
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                if (isApplyingProgrammaticText) return
                                searchCursorPinned = false
                                val next = s?.toString().orEmpty()
                                if (next != text) text = next
                            }
                        })

                        setOnTouchListener { _, event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN,
                                android.view.MotionEvent.ACTION_MOVE,
                                android.view.MotionEvent.ACTION_UP -> {
                                    searchCursorPinned = false
                                }
                            }
                            false
                        }

                        if (searchQuery.isNotBlank()) {
                            post {
                                val matches = findSearchMatches(text, searchQuery)
                                searchMatches = matches
                                if (matches.isNotEmpty()) {
                                    val idx = currentSearchIndex.takeIf { it in matches.indices } ?: 0
                                    currentSearchIndex = idx
                                    highlightSearchMatches(this, text, matches, idx, moveToMatch = false, pinCursorToMatch = searchCursorPinned)
                                }
                            }
                        }
                    }
                },
                update = { editText ->
                    editorRef = editText
                    val current = editText.text?.toString().orEmpty()
                    if (current != text && !editText.hasActiveComposition()) {
                        val selStart = editText.selectionStart.coerceAtLeast(0)
                        val selEnd = editText.selectionEnd.coerceAtLeast(0)
                        isApplyingProgrammaticText = true
                        updateEditorDisplayedText(editText, text, selStart, selEnd)
                        isApplyingProgrammaticText = false
                    }

                    if (searchQuery.isNotBlank()) {
                        val safeMatches = sanitizeSearchMatches(searchMatches, text)
                        if (safeMatches !== searchMatches) {
                            searchMatches = safeMatches
                            if (safeMatches.isEmpty()) {
                                currentSearchIndex = -1
                                clearSearchHighlight(editText, text)
                                return@AndroidView
                            }
                        }
                        val idx = currentSearchIndex.takeIf { it in safeMatches.indices } ?: 0
                        highlightSearchMatches(
                            editText = editText,
                            rawText = text,
                            matches = safeMatches,
                            currentIndex = idx,
                            moveToMatch = false,
                            pinCursorToMatch = searchCursorPinned
                        )
                    }
                }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("문자수 ${text.length} / 페이지 약 ${totalPages}p")
                    if (searchQuery.isNotBlank()) {
                        append("  ·  검색 ${searchMatches.size}건")
                        if (currentSearchIndex in searchMatches.indices) {
                            append(" (${currentSearchIndex + 1}/${searchMatches.size})")
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall
            )
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("단어 검색") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        label = { Text("검색어") },
                        placeholder = { Text("단어 입력") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { startSearchFromCursorOrNext() }
                        )
                    )

                    if (searchQuery.isBlank()) {
                        Text("검색어를 입력하면 전체 문장에서 노란색으로 표시됩니다.")
                    } else {
                        Text("검색 결과 ${searchMatches.size}건")
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { startSearchFromCursorOrNext() }) {
                        Text(if (currentSearchIndex >= 0) "다음" else "찾기")
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        searchQuery = ""
                        searchMatches = emptyList()
                        currentSearchIndex = -1
                        lastExecutedSearchQuery = ""
                        editorRef?.let { clearSearchHighlight(it, text) }
                    }) {
                        Text("지우기")
                    }
                    TextButton(onClick = {
                        showSearchDialog = false
                        searchCursorPinned = true
                        editorRef?.let { editText ->
                            moveCursorToCurrentSearchMatchEnd(editText, text, searchMatches, currentSearchIndex)
                            editText.requestFocus()
                        }
                    }) {
                        Text("닫기")
                    }
                }
            }
        )
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
                    val restored = pendingDraftText ?: return@TextButton
                    text = restored
                    lastDraftSavedText = restored
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
                                        val result = FileManager.saveTxtAndPdfToDownloads(context, baseName, text)
                                        currentUri = result.first
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

private fun findSearchMatches(text: String, query: String): List<SearchMatch> {
    if (query.isBlank() || text.isEmpty()) return emptyList()

    val matches = mutableListOf<SearchMatch>()
    var searchStart = 0
    while (searchStart < text.length) {
        val index = text.indexOf(query, startIndex = searchStart, ignoreCase = true)
        if (index < 0) break
        matches += SearchMatch(index, index + query.length)
        searchStart = (index + query.length).coerceAtLeast(index + 1)
    }
    return matches
}

private fun clearSearchHighlight(editText: EditText, rawText: String) {
    if (editText.hasActiveComposition()) return
    val selectionStart = editText.selectionStart.coerceAtLeast(0).coerceAtMost(rawText.length)
    val selectionEnd = editText.selectionEnd.coerceAtLeast(0).coerceAtMost(rawText.length)
    updateEditorDisplayedText(editText, SpannableStringBuilder(rawText), selectionStart, selectionEnd)
}

private fun highlightSearchMatches(
    editText: EditText,
    rawText: String,
    matches: List<SearchMatch>,
    currentIndex: Int,
    moveToMatch: Boolean,
    pinCursorToMatch: Boolean
) {
    if (editText.hasActiveComposition()) return

    val safeMatches = sanitizeSearchMatches(matches, rawText)
    val spannable = SpannableStringBuilder(rawText)

    safeMatches.forEachIndexed { index, match ->
        val isCurrent = index == currentIndex
        val bgColor = if (isCurrent) AndroidColor.rgb(255, 193, 7) else AndroidColor.YELLOW
        val fgColor = AndroidColor.BLACK
        spannable.setSpan(
            BackgroundColorSpan(bgColor),
            match.start,
            match.end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            ForegroundColorSpan(fgColor),
            match.start,
            match.end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    val currentMatch = safeMatches.getOrNull(currentIndex)
    val selectionStart = editText.selectionStart.coerceIn(0, rawText.length)
    val selectionEnd = editText.selectionEnd.coerceIn(0, rawText.length)

    if (pinCursorToMatch && currentMatch != null) {
        updateEditorDisplayedText(editText, spannable, currentMatch.end, currentMatch.end)
    } else {
        updateEditorDisplayedText(editText, spannable, selectionStart, selectionEnd)
    }

    if (currentMatch != null && moveToMatch) {
        editText.requestFocus()
        editText.post {
            editText.bringPointIntoView(currentMatch.end)
            val layout = editText.layout
            if (layout != null) {
                val line = layout.getLineForOffset(currentMatch.start)
                val targetY = (layout.getLineTop(line) - editText.height / 3).coerceAtLeast(0)
                smoothScrollEditTextTo(editText, targetY)
            }
        }
    }
}

private fun sanitizeSearchMatches(matches: List<SearchMatch>, rawText: String): List<SearchMatch> {
    if (matches.isEmpty()) return matches
    val textLength = rawText.length
    return matches.filter { match ->
        match.start in 0 until textLength &&
                match.end in 1..textLength &&
                match.start < match.end
    }
}

private fun moveCursorToCurrentSearchMatchEnd(
    editText: EditText,
    rawText: String,
    matches: List<SearchMatch>,
    currentIndex: Int
) {
    if (editText.hasActiveComposition()) return

    val currentMatch = sanitizeSearchMatches(matches, rawText).getOrNull(currentIndex)
    val cursor = currentMatch?.end ?: editText.selectionEnd.coerceIn(0, rawText.length)
    updateEditorDisplayedText(editText, editText.text ?: SpannableStringBuilder(rawText), cursor, cursor)
}

private fun EditText.hasActiveComposition(): Boolean {
    val editable = text ?: return false
    val composingStart = BaseInputConnection.getComposingSpanStart(editable)
    val composingEnd = BaseInputConnection.getComposingSpanEnd(editable)
    return composingStart >= 0 && composingEnd >= 0 && composingStart != composingEnd
}

private fun updateEditorDisplayedText(
    editText: EditText,
    newText: CharSequence,
    selectionStart: Int,
    selectionEnd: Int
) {
    val oldScrollX = editText.scrollX
    val oldScrollY = editText.scrollY
    editText.setText(newText, TextView.BufferType.SPANNABLE)
    val length = editText.text?.length ?: 0
    val safeStart = selectionStart.coerceIn(0, length)
    val safeEnd = selectionEnd.coerceIn(safeStart, length)
    editText.setSelection(safeStart, safeEnd)
    editText.scrollTo(oldScrollX, oldScrollY)
}

private fun firstMatchAtOrAfter(matches: List<SearchMatch>, cursor: Int): Int {
    val index = matches.indexOfFirst { it.start >= cursor }
    return if (index >= 0) index else 0
}

private fun smoothScrollEditTextTo(editText: EditText, targetY: Int) {
    val safeTarget = targetY.coerceAtLeast(0)
    if (editText.scrollY == safeTarget) return
    ObjectAnimator.ofInt(editText, "scrollY", editText.scrollY, safeTarget).apply {
        duration = 180L
        start()
    }
}
