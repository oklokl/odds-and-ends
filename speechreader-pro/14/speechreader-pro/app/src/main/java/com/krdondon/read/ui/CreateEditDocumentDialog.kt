package com.krdondon.read.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

@Composable
fun CreateEditDocumentDialog(
    initialTitle: String = "",
    initialContent: String = "",
    isEditing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String) -> Unit,
    onAutoSave: ((title: String, content: String) -> Unit)? = null
) {
    var title by remember { mutableStateOf(initialTitle) }
    var currentContent by remember { mutableStateOf(initialContent) }
    var isTitleError by remember { mutableStateOf(false) }

    // Line and Char counts
    var charCount by remember { mutableIntStateOf(initialContent.length) }
    var lineCount by remember { mutableIntStateOf(initialContent.count { it == '\n' } + 1) }

    // Reference to native LinedEditText for high-performance operations and search
    var linedEditTextRef by remember { mutableStateOf<LinedEditText?>(null) }

    // Auto-save state
    var isAutoSaveEnabled by remember { mutableStateOf(true) }
    var remainingSeconds by remember { mutableIntStateOf(40) }
    var activityTrigger by remember { mutableIntStateOf(0) }
    var autoSaveStatusMessage by remember { mutableStateOf<String?>(null) }
    var lastSavedContent by remember { mutableStateOf(initialContent) }
    var lastSavedTitle by remember { mutableStateOf(initialTitle) }

    // Search popup state
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResultInfo by remember { mutableStateOf<String?>(null) }

    // 40-second inactivity countdown timer for Auto-Save
    LaunchedEffect(isAutoSaveEnabled, isEditing, activityTrigger) {
        if (!isEditing || !isAutoSaveEnabled) return@LaunchedEffect
        remainingSeconds = 40
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        // 40 seconds passed with no user input
        if (title.isNotBlank() && (title != lastSavedTitle || currentContent != lastSavedContent)) {
            onAutoSave?.invoke(title, currentContent)
            lastSavedTitle = title
            lastSavedContent = currentContent
            autoSaveStatusMessage = "40초 동안 입력이 없어 자동 저장되었습니다."
        }
        // Reset countdown to 40 so it doesn't get stuck at 0s
        remainingSeconds = 40
    }

    LaunchedEffect(autoSaveStatusMessage) {
        if (autoSaveStatusMessage != null) {
            delay(3500L)
            autoSaveStatusMessage = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isEditing) Icons.Default.Edit else Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEditing) "게시물 수정" else "새 TXT 게시물 생성",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Auto-Save Toggle Buttons on top right (as requested by user)
                    if (isEditing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // "자동 저장" Button (shows countdown seconds when active)
                            FilledTonalButton(
                                onClick = {
                                    isAutoSaveEnabled = true
                                    activityTrigger++
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isAutoSaveEnabled)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isAutoSaveEnabled)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = if (isAutoSaveEnabled) "자동 저장 (${remainingSeconds}초)" else "자동 저장",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isAutoSaveEnabled) FontWeight.Bold else FontWeight.Normal
                                )
                            }

                            // "해제" Button
                            OutlinedButton(
                                onClick = {
                                    isAutoSaveEnabled = false
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (!isAutoSaveEnabled)
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                    else
                                        Color.Transparent,
                                    contentColor = if (!isAutoSaveEnabled)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    text = "해제",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (!isAutoSaveEnabled) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Auto-save notification banner
                AnimatedVisibility(
                    visible = autoSaveStatusMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    autoSaveStatusMessage?.let { msg ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Title Input Field
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (it.isNotBlank()) isTitleError = false
                        activityTrigger++
                    },
                    label = { Text("제목") },
                    placeholder = { Text("문서 제목을 입력하세요") },
                    singleLine = true,
                    isError = isTitleError,
                    supportingText = if (isTitleError) {
                        { Text("제목을 입력해주세요.") }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("document_title_input")
                )

                // Label and content hint
                Text(
                    text = "내용 (텍스트 편집 - 좌측 파란색 번호: 줄 번호)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // High-performance Native Editor with Line Numbers (LinedEditText)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("document_content_editor_container")
                ) {
                    AndroidView(
                        factory = { ctx ->
                            LinedEditText(ctx).apply {
                                setInitialContent(initialContent)
                                onContentChanged = { updatedText ->
                                    currentContent = updatedText
                                    charCount = updatedText.length
                                    lineCount = getTotalLineCount()
                                }
                                onUserActivity = {
                                    activityTrigger++
                                }
                                linedEditTextRef = this
                            }
                        },
                        update = { view ->
                            linedEditTextRef = view
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .testTag("document_content_input")
                    )
                }

                // Bottom Footer: Line & Char Count on top row, Buttons on bottom row
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. 글자 수 및 총 줄 수 (독립된 상단 행)
                    Text(
                        text = "글자 수: ${charCount}자  |  총 ${lineCount}줄",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )

                    // 2. 버튼 영역: [ 🔍 검색 ]  [ 취소 ]  [ 수정 완료 / 생성 하기 ] (가로로 안정적이고 깔끔하게 배치)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Search Button (Left of Cancel button)
                        OutlinedButton(
                            onClick = {
                                showSearchDialog = true
                                searchResultInfo = null
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dialog_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("검색", maxLines = 1)
                        }

                        // Cancel Button
                        OutlinedButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dialog_dismiss_button")
                        ) {
                            Text("취소", maxLines = 1)
                        }

                        // Confirm Button
                        Button(
                            onClick = {
                                if (title.isBlank()) {
                                    isTitleError = true
                                    return@Button
                                }
                                onConfirm(title, currentContent)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("dialog_confirm_button")
                        ) {
                            Text(if (isEditing) "수정 완료" else "생성 하기", maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    // Search Popup Dialog (Search downward from current cursor & jump to text)
    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("본문 검색", style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "현재 커서 위치 아래로 검색합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            searchResultInfo = null
                            if (it.isNotBlank()) {
                                val count = linedEditTextRef?.countMatches(it) ?: 0
                                searchResultInfo = if (count > 0) "총 ${count}개 일치" else "일치하는 결과 없음"
                            }
                        },
                        label = { Text("검색어") },
                        placeholder = { Text("찾을 단어를 입력하세요") },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = ""; searchResultInfo = null }) {
                                    Icon(Icons.Default.Close, contentDescription = "지우기")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    searchResultInfo?.let { info ->
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Previous (Search Up)
                    OutlinedButton(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                val result = linedEditTextRef?.searchPrevious(searchQuery)
                                if (result != null && result.isFound) {
                                    searchResultInfo = "이전 찾기: ${result.matchIndex} / ${result.totalMatches}번째 위치로 이동함" +
                                            if (result.didWrap) " (문서 끝에서 순환)" else ""
                                } else {
                                    searchResultInfo = "일치하는 단어를 찾을 수 없습니다."
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("이전")
                    }

                    // Next (Search Downward from cursor)
                    Button(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                val result = linedEditTextRef?.searchNext(searchQuery)
                                if (result != null && result.isFound) {
                                    searchResultInfo = "다음 찾기: ${result.matchIndex} / ${result.totalMatches}번째 위치로 이동함" +
                                            if (result.didWrap) " (처음부터 다시 순환)" else ""
                                } else {
                                    searchResultInfo = "일치하는 단어를 찾을 수 없습니다."
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("다음 찾기")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSearchDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }
}
