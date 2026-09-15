package com.krdondon.read.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdondon.read.data.model.TxtDocument
import com.krdondon.read.service.PlaybackStatus
import com.krdondon.read.service.TtsUiState
import com.krdondon.read.util.SentenceChunk
import com.krdondon.read.util.SentenceParser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    document: TxtDocument,
    ttsState: TtsUiState,
    onBackClick: () -> Unit,
    onPlayTts: (startIndex: Int) -> Unit,
    onPauseTts: () -> Unit,
    onResumeTts: () -> Unit,
    onStopTts: () -> Unit,
    onSeekToSentence: (Int) -> Unit,
    onSaveBookmark: (sentenceIndex: Int, charOffset: Int) -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onToggleLoopMode: () -> Unit = {}
) {
    val sentences = remember(document.content) {
        SentenceParser.parseSentences(document.content)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Determine current highlighted sentence
    val isPlayingThisDoc = ttsState.documentId == document.id
    val activeIndex = if (isPlayingThisDoc) {
        ttsState.currentSentenceIndex
    } else {
        document.lastReadSentenceIndex
    }

    // Selected cursor position by tapping
    var selectedCursorIndex by remember { mutableIntStateOf(activeIndex) }

    // Auto-scroll to active sentence during playback
    LaunchedEffect(activeIndex, ttsState.status) {
        if (isPlayingThisDoc && (ttsState.status == PlaybackStatus.PLAYING || ttsState.status == PlaybackStatus.PAUSED)) {
            if (activeIndex in sentences.indices) {
                listState.animateScrollToItem(activeIndex)
                selectedCursorIndex = activeIndex
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = document.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val total = sentences.size
                        val current = (activeIndex + 1).coerceAtMost(total)
                        val percent = if (total > 0) (current * 100 / total) else 0
                        Text(
                            text = if (total > 0) "문장 $current / $total ($percent%)" else "내용 없음",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("reader_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기"
                        )
                    }
                },
                actions = {
                    // Bookmark Jump Button (if bookmark exists)
                    if (document.bookmarkSentenceIndex in sentences.indices) {
                        IconButton(
                            onClick = {
                                val target = document.bookmarkSentenceIndex
                                selectedCursorIndex = target
                                scope.launch {
                                    listState.animateScrollToItem(target)
                                }
                            },
                            modifier = Modifier.testTag("jump_to_bookmark_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = "책갈피로 이동",
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    // Save Bookmark Button
                    IconButton(
                        onClick = {
                            val targetIndex = if (isPlayingThisDoc) ttsState.currentSentenceIndex else selectedCursorIndex
                            val chunk = sentences.getOrNull(targetIndex)
                            val charOffset = chunk?.startCharOffset ?: 0
                            onSaveBookmark(targetIndex, charOffset)
                        },
                        modifier = Modifier.testTag("save_bookmark_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "현재 위치 책갈피 저장",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // TTS Voice Settings
                    IconButton(
                        onClick = onOpenTtsSettings,
                        modifier = Modifier.testTag("tts_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "음성 설정"
                        )
                    }

                    // Infinite Loop Playback Toggle
                    IconButton(
                        onClick = onToggleLoopMode,
                        modifier = Modifier.testTag("loop_mode_button")
                    ) {
                        Icon(
                            imageVector = if (ttsState.isLoopEnabled) Icons.Default.RepeatOn else Icons.Default.Repeat,
                            contentDescription = if (ttsState.isLoopEnabled) "처음부터 다시 읽기 (무한 반복 켜짐)" else "무한 반복 꺼짐",
                            tint = if (ttsState.isLoopEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            ReaderBottomControls(
                sentencesCount = sentences.size,
                currentIndex = activeIndex,
                playbackStatus = if (isPlayingThisDoc) ttsState.status else PlaybackStatus.STOPPED,
                keepScreenOn = ttsState.keepScreenOn,
                onPlay = {
                    val start = if (selectedCursorIndex in sentences.indices) selectedCursorIndex else 0
                    onPlayTts(start)
                },
                onPause = onPauseTts,
                onResume = onResumeTts,
                onStop = onStopTts,
                onPrevSentence = {
                    val newIndex = (activeIndex - 1).coerceAtLeast(0)
                    selectedCursorIndex = newIndex
                    if (isPlayingThisDoc && ttsState.status == PlaybackStatus.PLAYING) {
                        onSeekToSentence(newIndex)
                    }
                    scope.launch { listState.animateScrollToItem(newIndex) }
                },
                onNextSentence = {
                    val newIndex = (activeIndex + 1).coerceAtMost((sentences.size - 1).coerceAtLeast(0))
                    selectedCursorIndex = newIndex
                    if (isPlayingThisDoc && ttsState.status == PlaybackStatus.PLAYING) {
                        onSeekToSentence(newIndex)
                    }
                    scope.launch { listState.animateScrollToItem(newIndex) }
                },
                onToggleKeepScreenOn = onToggleKeepScreenOn
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Reading Progress Indicator
            val progress = if (sentences.isNotEmpty()) {
                (activeIndex + 1).toFloat() / sentences.size.toFloat()
            } else 0f

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .testTag("reading_progress_bar"),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Cursor jump guide notice when cursor differs from active playback
            AnimatedVisibility(
                visible = selectedCursorIndex != activeIndex && selectedCursorIndex in sentences.indices
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${selectedCursorIndex + 1}번째 문장 선택됨",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                onPlayTts(selectedCursorIndex)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("play_from_selected_cursor_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("여기서부터 읽기", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Sentences List with Visual Highlighting Effect
            if (sentences.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "표시할 텍스트 내용이 없습니다.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("sentences_lazy_column")
                ) {
                    itemsIndexed(sentences, key = { _, chunk -> chunk.index }) { index, chunk ->
                        val isCurrentReading = isPlayingThisDoc && index == activeIndex &&
                                (ttsState.status == PlaybackStatus.PLAYING || ttsState.status == PlaybackStatus.PAUSED)
                        val isCursorSelected = index == selectedCursorIndex
                        val isBookmarked = index == document.bookmarkSentenceIndex

                        SentenceItemView(
                            chunk = chunk,
                            isCurrentReading = isCurrentReading,
                            isCursorSelected = isCursorSelected,
                            isBookmarked = isBookmarked,
                            onItemClick = {
                                selectedCursorIndex = index
                            },
                            onPlayFromHere = {
                                selectedCursorIndex = index
                                onPlayTts(index)
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "— 문서의 끝 —",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SentenceItemView(
    chunk: SentenceChunk,
    isCurrentReading: Boolean,
    isCursorSelected: Boolean,
    isBookmarked: Boolean,
    onItemClick: () -> Unit,
    onPlayFromHere: () -> Unit
) {
    // Dynamic animated background for visual effect
    val targetBackgroundColor = when {
        isCurrentReading -> MaterialTheme.colorScheme.primaryContainer
        isCursorSelected -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    val animatedColor by animateColorAsState(
        targetValue = targetBackgroundColor,
        animationSpec = tween(durationMillis = 300),
        label = "sentence_bg_animation"
    )

    val borderColor = when {
        isCurrentReading -> MaterialTheme.colorScheme.primary
        isCursorSelected -> MaterialTheme.colorScheme.outline
        else -> Color.Transparent
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = animatedColor,
        tonalElevation = if (isCurrentReading) 4.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isCurrentReading) 2.dp else if (isCursorSelected) 1.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .clip(RoundedCornerShape(14.dp))
            .clickable { onItemClick() }
            .testTag("sentence_item_${chunk.index}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrentReading) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "읽는 중",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "현재 읽는 문장 (${chunk.index + 1})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "${chunk.index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    if (isBookmarked) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "책갈피",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (isCursorSelected && !isCurrentReading) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onPlayFromHere() }
                            .testTag("play_here_badge_${chunk.index}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "여기부터 읽기",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Text content
            Text(
                text = chunk.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    fontWeight = if (isCurrentReading) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isCurrentReading)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ReaderBottomControls(
    sentencesCount: Int,
    currentIndex: Int,
    playbackStatus: PlaybackStatus,
    keepScreenOn: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onPrevSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onToggleKeepScreenOn: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Main Playback Controls: Previous, Play/Pause, Stop, Next
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Prev Sentence
                IconButton(
                    onClick = onPrevSentence,
                    enabled = sentencesCount > 0 && currentIndex > 0,
                    modifier = Modifier.size(48.dp).testTag("button_prev_sentence")
                ) {
                    Icon(
                        imageVector = Icons.Default.NavigateBefore,
                        contentDescription = "이전 문장",
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Stop Button (정지)
                FilledTonalIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(52.dp).testTag("button_stop")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "정지",
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                // Play / Pause / Resume Button (재생 / 멈춤)
                when (playbackStatus) {
                    PlaybackStatus.PLAYING -> {
                        FloatingActionButton(
                            onClick = onPause,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                            modifier = Modifier.size(64.dp).testTag("button_pause")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "멈춤",
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    PlaybackStatus.PAUSED -> {
                        FloatingActionButton(
                            onClick = onResume,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                            modifier = Modifier.size(64.dp).testTag("button_resume")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "재생",
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    else -> {
                        FloatingActionButton(
                            onClick = onPlay,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                            modifier = Modifier.size(64.dp).testTag("button_play")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "재생",
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                }

                // Next Sentence
                IconButton(
                    onClick = onNextSentence,
                    enabled = sentencesCount > 0 && currentIndex < sentencesCount - 1,
                    modifier = Modifier.size(48.dp).testTag("button_next_sentence")
                ) {
                    Icon(
                        imageVector = Icons.Default.NavigateNext,
                        contentDescription = "다음 문장",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // User requested: "그리고 유지 해제 버튼이 추가로 있어서 화면 꺼짐을 방지 하거나 하는 기능도 있으면 좋겠어요. 하단에"
            // Bottom Screen Keep-On Toggle Row
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (keepScreenOn)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleKeepScreenOn() }
                    .testTag("keep_screen_on_bottom_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (keepScreenOn) Icons.Default.BrightnessHigh else Icons.Default.BrightnessAuto,
                            contentDescription = null,
                            tint = if (keepScreenOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "화면 꺼짐 방지",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (keepScreenOn) "독서 중 화면이 꺼지지 않습니다" else "시스템 화면 꺼짐 시간 적용",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Explicit [유지] / [해제] button requested by user
                    FilledTonalButton(
                        onClick = onToggleKeepScreenOn,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (keepScreenOn)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outlineVariant,
                            contentColor = if (keepScreenOn)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.testTag("toggle_screen_keep_button")
                    ) {
                        Icon(
                            imageVector = if (keepScreenOn) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (keepScreenOn) "유지 중 (해제하기)" else "유지 켜기",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
