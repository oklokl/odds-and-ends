package com.krdonon.ratio

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
import java.io.File

class RatioViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(RatioUiState())
    val uiState: StateFlow<RatioUiState> = _uiState.asStateFlow()

    private val _events = Channel<RatioUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var processingJob: Job? = null
    private var savingJob: Job? = null
    private var isAppVisible: Boolean = true

    init {
        viewModelScope.launch(Dispatchers.IO) {
            cleanupStaleCacheFiles(getApplication<Application>().cacheDir)
        }
    }

    fun selectImage(uri: Uri) {
        processingJob?.cancel()
        val previousFile = _uiState.value.selectedImageFile

        _uiState.update {
            it.copy(
                selectedImageUri = uri,
                selectedImageFile = null,
                originalBitmap = null,
                editedBitmap = null,
                selectedRatio = null,
                currentRotation = 0f,
                isFormatOrQualityModified = false,
                isProcessing = true,
            )
        }

        processingJob = viewModelScope.launch {
            try {
                val (cachedFile, previewBitmap) = withContext(Dispatchers.IO) {
                    previousFile?.delete()
                    val appContext = getApplication<Application>()
                    val cached = copySelectedImageToCache(appContext, uri)
                    try {
                        val preview = loadBitmapWithOrientation(
                            sourceFile = cached,
                            maxDimension = PREVIEW_MAX_DIMENSION,
                        )
                        cached to preview
                    } catch (e: Exception) {
                        cached.delete()
                        throw e
                    }
                }

                _uiState.update {
                    it.copy(
                        selectedImageFile = cachedFile,
                        originalBitmap = previewBitmap,
                        isProcessing = false,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false) }
                _events.trySend(
                    RatioUiEvent.ShowMessage(
                        "이미지를 불러올 수 없습니다: ${e.message ?: "알 수 없는 오류"}",
                    ),
                )
            }
        }
    }

    fun rotate90() {
        val current = _uiState.value
        val source = current.originalBitmap ?: return
        if (current.isProcessing || current.isSaving) return

        val ratio = current.selectedRatio
        val background = current.selectedBackgroundColor

        processingJob?.cancel()
        _uiState.update { it.copy(isProcessing = true) }

        processingJob = viewModelScope.launch {
            var rotated: Bitmap? = null
            var ratioResult: Bitmap? = null
            try {
                val resultPair = withContext(Dispatchers.Default) {
                    val newOriginal = rotateBitmap(source, 90f)
                    rotated = newOriginal
                    val newEdited = ratio?.let {
                        resizeToAspectRatio(newOriginal, it.ratio, background.colorValue)
                    }
                    ratioResult = newEdited
                    newOriginal to newEdited
                }

                _uiState.update { state ->
                    state.copy(
                        originalBitmap = resultPair.first,
                        editedBitmap = resultPair.second,
                        currentRotation = normalizeRotation(state.currentRotation + 90f),
                        isProcessing = false,
                    )
                }
            } catch (ce: CancellationException) {
                val created = rotated
                if (created != null && created !== source && !created.isRecycled) {
                    created.recycle()
                }
                if (ratioResult?.isRecycled == false) {
                    ratioResult.recycle()
                }
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false) }
                _events.trySend(
                    RatioUiEvent.ShowMessage("이미지 회전 중 오류가 발생했습니다: ${e.message}"),
                )
            }
        }
    }

    fun selectRatio(ratio: AspectRatioOption) {
        val current = _uiState.value
        if (current.isSaving) return
        _uiState.update { it.copy(selectedRatio = ratio) }
        rebuildEditedBitmap(ratio, current.selectedBackgroundColor)
    }

    fun selectBackgroundColor(backgroundColor: BackgroundColor) {
        val current = _uiState.value
        if (current.isSaving) return

        val switchedToPng =
            backgroundColor == BackgroundColor.TRANSPARENT && !current.selectedFormat.supportsTransparency
        val nextFormat = if (switchedToPng) ImageFormat.PNG else current.selectedFormat

        _uiState.update {
            it.copy(
                selectedBackgroundColor = backgroundColor,
                selectedFormat = nextFormat,
                isFormatOrQualityModified = it.isFormatOrQualityModified || switchedToPng,
            )
        }

        if (switchedToPng) {
            _events.trySend(
                RatioUiEvent.ShowMessage("투명 배경은 PNG/WebP만 지원하여 PNG로 전환했습니다"),
            )
        }

        current.selectedRatio?.let { ratio ->
            rebuildEditedBitmap(ratio, backgroundColor)
        }
    }

    fun selectFormat(format: ImageFormat) {
        val current = _uiState.value
        if (current.isSaving) return
        if (current.selectedBackgroundColor == BackgroundColor.TRANSPARENT && !format.supportsTransparency) {
            _events.trySend(RatioUiEvent.ShowMessage("투명 배경에서는 PNG 또는 WebP 포맷만 선택할 수 있습니다"))
            return
        }
        _uiState.update {
            it.copy(
                selectedFormat = format,
                isFormatOrQualityModified = true,
            )
        }
    }

    fun updateQuality(newQuality: Int) {
        _uiState.update {
            it.copy(
                quality = newQuality.coerceIn(1, 100),
                isFormatOrQualityModified = true,
            )
        }
    }

    fun saveEditedImage() {
        val snapshot = _uiState.value
        if (!snapshot.canSave) return

        val uri = snapshot.selectedImageUri ?: return
        val sourceFile = snapshot.selectedImageFile ?: return

        savingJob?.cancel()
        _uiState.update { it.copy(isSaving = true) }

        savingJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    exportEditedImage(
                        context = getApplication(),
                        sourceFile = sourceFile,
                        originalUri = uri,
                        targetRatio = snapshot.selectedRatio?.ratio,
                        backgroundColor = snapshot.selectedBackgroundColor.colorValue,
                        additionalRotation = snapshot.currentRotation,
                        format = snapshot.selectedFormat,
                        quality = snapshot.quality,
                    )
                }
                _events.trySend(RatioUiEvent.ShowMessage("이미지가 저장되었습니다"))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _events.trySend(
                    RatioUiEvent.ShowMessage("저장 실패: ${e.message ?: "알 수 없는 오류"}"),
                )
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun onAppVisibilityChanged(visible: Boolean) {
        isAppVisible = visible
        if (!visible) {
            processingJob?.cancel()
            _uiState.update {
                it.copy(
                    editedBitmap = null,
                    isProcessing = false,
                )
            }
        } else {
            val state = _uiState.value
            if (state.editedBitmap == null && state.originalBitmap != null && state.selectedRatio != null) {
                rebuildEditedBitmap(state.selectedRatio, state.selectedBackgroundColor)
            }
        }
    }

    fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        val isHiddenOrSevere = !isAppVisible || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        if (isHiddenOrSevere) {
            processingJob?.cancel()
            _uiState.update {
                it.copy(
                    editedBitmap = null,
                    isProcessing = false,
                )
            }
        }
    }

    private fun rebuildEditedBitmap(
        ratio: AspectRatioOption,
        backgroundColor: BackgroundColor,
    ) {
        val source = _uiState.value.originalBitmap ?: return
        processingJob?.cancel()
        _uiState.update { it.copy(isProcessing = true) }

        processingJob = viewModelScope.launch {
            var result: Bitmap? = null
            try {
                val created = withContext(Dispatchers.Default) {
                    resizeToAspectRatio(source, ratio.ratio, backgroundColor.colorValue)
                }
                result = created

                _uiState.update { state ->
                    if (isAppVisible && state.originalBitmap === source && state.selectedRatio == ratio) {
                        state.copy(editedBitmap = created, isProcessing = false)
                    } else {
                        if (!created.isRecycled) created.recycle()
                        state.copy(isProcessing = false)
                    }
                }
            } catch (ce: CancellationException) {
                if (result?.isRecycled == false) {
                    result?.recycle()
                }
                throw ce
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false) }
                _events.trySend(
                    RatioUiEvent.ShowMessage("이미지 편집 중 오류가 발생했습니다: ${e.message}"),
                )
            }
        }
    }

    override fun onCleared() {
        val file: File? = _uiState.value.selectedImageFile
        file?.delete()
        super.onCleared()
    }
}
