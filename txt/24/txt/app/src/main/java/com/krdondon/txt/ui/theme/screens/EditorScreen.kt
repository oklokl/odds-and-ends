package com.krdondon.txt.ui.screens

import android.animation.ObjectAnimator
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.krdondon.txt.utils.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

private const val LARGE_PASTE_WARNING_THRESHOLD = 200_000
private const val HARD_TEXT_LIMIT = 1_500_000
private const val EFFECTIVE_LINES_PER_PAGE = 45

private data class SearchMatch(val start: Int, val end: Int)

private class EditorStatsState(
    initialLength: Int,
    initialNewlines: Int
) {
    var charCount by mutableIntStateOf(initialLength)
    var newlineCount by mutableIntStateOf(initialNewlines)
    var editRevision by mutableIntStateOf(0)
}

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
    val normalizedInitialText = remember(initialText) { initialText.replace("\r", "") }
    var programmaticText by remember(initialText) { mutableStateOf(normalizedInitialText) }
    var programmaticTextVersion by remember(initialText) { mutableIntStateOf(0) }
    val stats = remember(initialText) {
        EditorStatsState(
            initialLength = normalizedInitialText.length,
            initialNewlines = normalizedInitialText.count { it == '\n' }
        )
    }

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
    var editorRef by remember { mutableStateOf<SafeKineticEditText?>(null) }
    var isApplyingProgrammaticText by remember { mutableStateOf(false) }
    var lastExecutedSearchQuery by remember { mutableStateOf("") }
    var searchCursorPinned by remember { mutableStateOf(false) }

    fun currentEditorText(): String = editorRef?.text?.toString() ?: programmaticText

    fun replaceEditorTextProgrammatically(newText: String) {
        val normalized = newText.replace("\r", "")
        programmaticText = normalized
        stats.charCount = normalized.length
        stats.newlineCount = normalized.count { it == '\n' }
        programmaticTextVersion++
        stats.editRevision++
    }

    LaunchedEffect(existingUri, initialFileName) {
        status = null
    }

    LaunchedEffect(status) {
        if (status != null) {
            delay(2500)
            status = null
        }
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
    var lastDraftSavedPageCount by remember(initialText) {
        val initialPages = ceil((normalizedInitialText.count { it == '\n' } + 1) / EFFECTIVE_LINES_PER_PAGE.toDouble())
            .toInt()
            .coerceAtLeast(1)
        mutableIntStateOf(initialPages)
    }

    suspend fun saveDraft(reason: String) {
        val snapshotText = currentEditorText()
        val snapshotName = fileName
        if (snapshotText.isBlank()) return
        if (snapshotText == lastDraftSavedText) return

        val now = System.currentTimeMillis()
        val lines = withContext(Dispatchers.Default) {
            snapshotText.count { it == '\n' } + 1
        }
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(draftKey("text"), snapshotText)
                .putLong(draftKey("ts"), now)
                .putString(draftKey("name"), snapshotName)
                .apply()
        }

        lastDraftSavedText = snapshotText
        lastDraftSavedPageCount = ceil(lines / EFFECTIVE_LINES_PER_PAGE.toDouble()).toInt().coerceAtLeast(1)
        status = when (reason) {
            "idle" -> "임시저장됨(40초)"
            "page" -> "임시저장됨(페이지 넘어감)"
            else -> "임시저장됨"
        }
    }

    fun appendClipboardText(clipText: String) {
        val sanitized = clipText.replace("\u0000", "").replace("\r", "")
        val currentLength = editorRef?.text?.length ?: stats.charCount
        val newLength = currentLength + sanitized.length
        if (newLength > HARD_TEXT_LIMIT) {
            status = "붙여넣기 취소: ${HARD_TEXT_LIMIT / 1000}KB 이상은 한 번에 넣지 않도록 제한했습니다"
            return
        }
        val editText = editorRef
        val editable = editText?.text
        if (editText != null && editable != null) {
            isApplyingProgrammaticText = true
            editable.append(sanitized)
            isApplyingProgrammaticText = false
            stats.charCount = editable.length
            stats.newlineCount += sanitized.count { it == '\n' }
            stats.editRevision++
        } else {
            replaceEditorTextProgrammatically(programmaticText + sanitized)
        }
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
        val rawText = currentEditorText()
        val previousMatches = searchMatches
        val matches = findSearchMatches(rawText, searchQuery)
        searchMatches = matches

        if (matches.isEmpty()) {
            currentSearchIndex = -1
            editorRef?.let { clearSearchHighlight(it) }
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
            highlightSearchMatches(it, rawText, matches, nextIndex, moveToMatch = false, pinCursorToMatch = searchCursorPinned)
        }
    }

    fun startSearchFromCursorOrNext() {
        val query = searchQuery.trim()
        if (query.isEmpty()) return

        val rawText = currentEditorText()
        val editText = editorRef
        val matches = findSearchMatches(rawText, query)
        searchMatches = matches

        if (matches.isEmpty()) {
            currentSearchIndex = -1
            editText?.let { clearSearchHighlight(it) }
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
            highlightSearchMatches(it, rawText, matches, targetIndex, moveToMatch = true, pinCursorToMatch = true)
        }
        status = "${targetIndex + 1} / ${matches.size}"
    }

    LaunchedEffect(Unit) {
        val draftText = readDraftText()
        if (draftText != null && draftText != normalizedInitialText) {
            pendingDraftText = draftText
            pendingDraftTs = readDraftTs()
            clearDraft()
            showRestoreDialog = true
        } else if (draftText != null) {
            clearDraft()
        }
    }

    LaunchedEffect(stats) {
        snapshotFlow { stats.editRevision }
            .drop(1)
            .collectLatest {
                delay(40_000)
                saveDraft("idle")
            }
    }

    LaunchedEffect(stats) {
        snapshotFlow {
            val lines = (stats.newlineCount + 1).coerceAtLeast(1)
            ceil(lines / EFFECTIVE_LINES_PER_PAGE.toDouble()).toInt().coerceAtLeast(1)
        }
            .distinctUntilChanged()
            .collectLatest { pages ->
                if (pages > lastDraftSavedPageCount) {
                    delay(1_000)
                    saveDraft("page")
                }
            }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchMatches = emptyList()
            currentSearchIndex = -1
            lastExecutedSearchQuery = ""
            editorRef?.let(::clearSearchHighlight)
        } else {
            refreshSearchResults(preserveSelection = true)
        }
    }

    LaunchedEffect(stats, searchQuery) {
        if (searchQuery.isBlank()) return@LaunchedEffect
        snapshotFlow { stats.editRevision }
            .drop(1)
            .collectLatest {
                delay(150)
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
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        editorRef = this
                        isApplyingProgrammaticText = true
                        setText(programmaticText, TextView.BufferType.SPANNABLE)
                        appliedTextVersion = programmaticTextVersion
                        isApplyingProgrammaticText = false
                        gravity = Gravity.TOP or Gravity.START
                        inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                        maxLines = Int.MAX_VALUE
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        isScrollbarFadingEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        setHorizontallyScrolling(false)
                        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        isSaveEnabled = false
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setLineSpacing(0f, 1.08f)
                        addTextChangedListener(object : TextWatcher {
                            private var pendingRemovedNewlines = 0

                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                                if (isApplyingProgrammaticText || s == null || count <= 0) {
                                    pendingRemovedNewlines = 0
                                    return
                                }
                                var removed = 0
                                val end = (start + count).coerceAtMost(s.length)
                                for (i in start until end) {
                                    if (s[i] == '\n') removed++
                                }
                                pendingRemovedNewlines = removed
                            }

                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                if (isApplyingProgrammaticText || s == null) return
                                var added = 0
                                val end = (start + count).coerceAtMost(s.length)
                                for (i in start until end) {
                                    if (s[i] == '\n') added++
                                }
                                val delta = added - pendingRemovedNewlines
                                pendingRemovedNewlines = 0
                                if (delta != 0) {
                                    stats.newlineCount = (stats.newlineCount + delta).coerceAtLeast(0)
                                }
                                stats.charCount = s.length
                                stats.editRevision++
                            }

                            override fun afterTextChanged(s: Editable?) {
                                if (isApplyingProgrammaticText) return
                                searchCursorPinned = false
                            }
                        })

                        setOnTouchListener { _, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN,
                                MotionEvent.ACTION_MOVE,
                                MotionEvent.ACTION_UP -> {
                                    searchCursorPinned = false
                                }
                            }
                            false
                        }

                        if (searchQuery.isNotBlank()) {
                            post {
                                val raw = text?.toString().orEmpty()
                                val matches = findSearchMatches(raw, searchQuery)
                                searchMatches = matches
                                if (matches.isNotEmpty()) {
                                    val idx = currentSearchIndex.takeIf { it in matches.indices } ?: 0
                                    currentSearchIndex = idx
                                    highlightSearchMatches(this, raw, matches, idx, moveToMatch = false, pinCursorToMatch = searchCursorPinned)
                                }
                            }
                        }
                    }
                },
                update = { editText ->
                    editorRef = editText
                    if (editText.appliedTextVersion != programmaticTextVersion) {
                        val selStart = editText.selectionStart.coerceAtLeast(0)
                        val selEnd = editText.selectionEnd.coerceAtLeast(0)
                        isApplyingProgrammaticText = true
                        updateEditorDisplayedText(editText, programmaticText, selStart, selEnd)
                        editText.appliedTextVersion = programmaticTextVersion
                        isApplyingProgrammaticText = false
                    }
                }
            )

            Spacer(Modifier.height(8.dp))
            EditorFooter(
                stats = stats,
                searchQuery = searchQuery,
                searchMatchCount = searchMatches.size,
                currentSearchIndex = currentSearchIndex,
                status = status
            )
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
                        editorRef?.let { clearSearchHighlight(it) }
                    }) {
                        Text("지우기")
                    }
                    TextButton(onClick = {
                        showSearchDialog = false
                        searchCursorPinned = true
                        editorRef?.let { editText ->
                            moveCursorToCurrentSearchMatchEnd(editText, currentEditorText(), searchMatches, currentSearchIndex)
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
                    replaceEditorTextProgrammatically(restored)
                    lastDraftSavedText = restored
                    lastDraftSavedPageCount = ceil(
                        (restored.count { it == '\n' } + 1) / EFFECTIVE_LINES_PER_PAGE.toDouble()
                    ).toInt().coerceAtLeast(1)
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
                            val textToSave = currentEditorText()
                            scope.launch {
                                runCatching {
                                    val name = FileManager.ensureExtension(fileName, "txt")
                                    fileName = name
                                    val uri = currentUri
                                    if (uri != null) {
                                        FileManager.overwriteTxtUri(context, uri, textToSave)
                                        if (currentHasPdf) {
                                            val pdfUri = FileManager.findLinkedPdfUri(context, name)
                                            if (pdfUri != null) FileManager.overwritePdfUri(context, pdfUri, textToSave)
                                        }
                                    } else {
                                        currentUri = FileManager.saveTxtToDownloads(context, name, textToSave)
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
                                val textToSave = currentEditorText()
                                scope.launch {
                                    runCatching {
                                        val baseName = fileName.substringBeforeLast(".")
                                        val result = FileManager.saveTxtAndPdfToDownloads(context, baseName, textToSave)
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
                            val textToSave = currentEditorText()
                            scope.launch {
                                runCatching {
                                    FileManager.savePdfToDownloads(context, fileName, textToSave)
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

@Composable
private fun EditorFooter(
    stats: EditorStatsState,
    searchQuery: String,
    searchMatchCount: Int,
    currentSearchIndex: Int,
    status: String?
) {
    val totalLines = (stats.newlineCount + 1).coerceAtLeast(1)
    val totalPages = ceil(totalLines / EFFECTIVE_LINES_PER_PAGE.toDouble()).toInt().coerceAtLeast(1)
    Text(
        text = buildString {
            append("문자수 ${stats.charCount} / 페이지 약 ${totalPages}p")
            if (searchQuery.isNotBlank()) {
                append("  ·  검색 ${searchMatchCount}건")
                if (currentSearchIndex in 0 until searchMatchCount) {
                    append(" (${currentSearchIndex + 1}/$searchMatchCount)")
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = status.orEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
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

private fun clearSearchHighlight(editText: EditText) {
    if (editText.hasActiveComposition()) return

    // Remove only the temporary search spans in place. Do not call setText(): replacing the
    // whole Editable resets layout/composition work and delays rapid Enter input.
    val editable = editText.text ?: return
    editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
        .forEach { editable.removeSpan(it) }
    editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        .forEach { editable.removeSpan(it) }
    editText.invalidate()
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
    val editable = editText.text ?: return

    editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
        .forEach { editable.removeSpan(it) }
    editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        .forEach { editable.removeSpan(it) }

    val safeMatches = sanitizeSearchMatches(matches, rawText, editable.length)
    safeMatches.forEachIndexed { index, match ->
        val isCurrent = index == currentIndex
        val bgColor = if (isCurrent) AndroidColor.rgb(255, 193, 7) else AndroidColor.YELLOW
        val fgColor = AndroidColor.BLACK
        editable.setSpan(
            BackgroundColorSpan(bgColor),
            match.start,
            match.end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        editable.setSpan(
            ForegroundColorSpan(fgColor),
            match.start,
            match.end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    val currentMatch = safeMatches.getOrNull(currentIndex)
    if (pinCursorToMatch && currentMatch != null) {
        editText.setSelection(currentMatch.end.coerceIn(0, editable.length))
    }

    if (currentMatch != null && moveToMatch) {
        editText.requestFocus()
        editText.post {
            val len = editText.length()
            editText.bringPointIntoView(currentMatch.end.coerceIn(0, len))
            val layout = editText.layout
            if (layout != null && len > 0) {
                val line = layout.getLineForOffset(currentMatch.start.coerceIn(0, len))
                val targetY = (layout.getLineTop(line) - editText.height / 3).coerceAtLeast(0)
                smoothScrollEditTextTo(editText, targetY)
            }
        }
    }
}

private fun sanitizeSearchMatches(
    matches: List<SearchMatch>,
    rawText: String,
    maxLength: Int = rawText.length
): List<SearchMatch> {
    if (matches.isEmpty()) return matches
    val textLength = minOf(rawText.length, maxLength)
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

    val len = editText.text?.length ?: 0
    val currentMatch = sanitizeSearchMatches(matches, rawText, len).getOrNull(currentIndex)
    val cursor = (currentMatch?.end ?: editText.selectionEnd).coerceIn(0, len)
    editText.setSelection(cursor, cursor)
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
