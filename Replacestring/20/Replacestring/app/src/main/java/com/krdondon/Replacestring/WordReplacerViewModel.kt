package com.krdondon.Replacestring

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExpandedEditor {
    ORIGINAL,
    RESULT
}

@Immutable
data class WordReplacerUiState(
    val targetWord: String = "",
    val replacement: String = "",
    val originalText: String = "",
    val replacedText: String = "",
    val expandedEditor: ExpandedEditor? = null,
    val isReplacing: Boolean = false
) {
    val hasAnyInput: Boolean
        get() = targetWord.isNotEmpty() ||
            replacement.isNotEmpty() ||
            originalText.isNotEmpty() ||
            replacedText.isNotEmpty()
}

sealed interface WordReplacerEvent {
    data object EmptyTargetWord : WordReplacerEvent
    data object EmptyOriginalText : WordReplacerEvent
    data class Replaced(val matchCount: Int) : WordReplacerEvent
    data object ClearedAll : WordReplacerEvent
}

internal data class ReplaceResult(
    val text: String,
    val matchCount: Int
)

internal fun replaceTextAndCount(
    original: String,
    target: String,
    replacement: String
): ReplaceResult {
    if (target.isEmpty() || original.isEmpty()) {
        return ReplaceResult(text = original, matchCount = 0)
    }

    var occurrenceIndex = original.indexOf(target)
    if (occurrenceIndex < 0) {
        return ReplaceResult(text = original, matchCount = 0)
    }

    val targetLength = target.length
    val builder = StringBuilder(original.length)
    var currentStart = 0
    var count = 0

    while (occurrenceIndex >= 0) {
        builder.append(original, currentStart, occurrenceIndex)
        builder.append(replacement)
        count++
        currentStart = occurrenceIndex + targetLength
        occurrenceIndex = original.indexOf(target, startIndex = currentStart)
    }
    builder.append(original, currentStart, original.length)

    return ReplaceResult(text = builder.toString(), matchCount = count)
}

class WordReplacerViewModel(
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _uiState = MutableStateFlow(WordReplacerUiState())
    val uiState: StateFlow<WordReplacerUiState> = _uiState.asStateFlow()

    private val _events = Channel<WordReplacerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var replaceJob: Job? = null
    private var lastClearedState: WordReplacerUiState? = null

    fun onTargetWordChange(value: String) {
        _uiState.update { it.copy(targetWord = value) }
    }

    fun onReplacementChange(value: String) {
        _uiState.update { it.copy(replacement = value) }
    }

    fun onOriginalTextChange(value: String) {
        replaceJob?.cancel()
        _uiState.update { it.copy(originalText = value, isReplacing = false) }
    }

    fun onReplacedTextChange(value: String) {
        _uiState.update { it.copy(replacedText = value) }
    }

    fun setExpandedEditor(editor: ExpandedEditor?) {
        _uiState.update { it.copy(expandedEditor = editor) }
    }

    fun replaceWords() {
        val currentState = _uiState.value
        if (currentState.targetWord.isEmpty()) {
            _events.trySend(WordReplacerEvent.EmptyTargetWord)
            return
        }
        if (currentState.originalText.isEmpty()) {
            _events.trySend(WordReplacerEvent.EmptyOriginalText)
            return
        }

        replaceJob?.cancel()
        replaceJob = viewModelScope.launch {
            _uiState.update { it.copy(isReplacing = true) }
            val result = withContext(defaultDispatcher) {
                replaceTextAndCount(
                    original = currentState.originalText,
                    target = currentState.targetWord,
                    replacement = currentState.replacement
                )
            }
            _uiState.update {
                it.copy(
                    replacedText = result.text,
                    isReplacing = false
                )
            }
            _events.trySend(WordReplacerEvent.Replaced(result.matchCount))
        }
    }

    fun clearAll() {
        val currentState = _uiState.value
        if (!currentState.hasAnyInput) return

        replaceJob?.cancel()
        lastClearedState = currentState.copy(isReplacing = false)
        _uiState.update {
            it.copy(
                targetWord = "",
                replacement = "",
                originalText = "",
                replacedText = "",
                isReplacing = false
            )
        }
        _events.trySend(WordReplacerEvent.ClearedAll)
    }

    fun undoClear() {
        val snapshot = lastClearedState ?: return
        lastClearedState = null
        _uiState.update {
            snapshot.copy(expandedEditor = it.expandedEditor)
        }
    }
}
