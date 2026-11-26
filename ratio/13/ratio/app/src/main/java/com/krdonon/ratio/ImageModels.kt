package com.krdonon.ratio

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import java.io.File

@Immutable
data class AspectRatioOption(
    val name: String,
    val ratio: Float,
)

enum class ImageFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val supportsTransparency: Boolean,
    val supportsQuality: Boolean,
) {
    JPG(
        label = "JPG",
        extension = "jpg",
        mimeType = "image/jpeg",
        supportsTransparency = false,
        supportsQuality = true,
    ),
    PNG(
        label = "PNG",
        extension = "png",
        mimeType = "image/png",
        supportsTransparency = true,
        supportsQuality = false,
    ),
    WEBP(
        label = "WebP",
        extension = "webp",
        mimeType = "image/webp",
        supportsTransparency = true,
        supportsQuality = true,
    ),
}

enum class BackgroundColor(val colorValue: Int, val displayName: String) {
    WHITE(android.graphics.Color.WHITE, "흰색"),
    BLACK(android.graphics.Color.BLACK, "검은색"),
    TRANSPARENT(android.graphics.Color.TRANSPARENT, "투명"),
}

data class RatioUiState(
    val selectedImageUri: Uri? = null,
    val selectedImageFile: File? = null,
    val originalBitmap: Bitmap? = null,
    val editedBitmap: Bitmap? = null,
    val selectedRatio: AspectRatioOption? = null,
    val selectedFormat: ImageFormat = ImageFormat.JPG,
    val selectedBackgroundColor: BackgroundColor = BackgroundColor.WHITE,
    val quality: Int = 100,
    val currentRotation: Float = 0f,
    val isFormatOrQualityModified: Boolean = false,
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
) {
    val hasImage: Boolean get() = originalBitmap != null
    val hasEdits: Boolean
        get() = (selectedRatio != null) || (currentRotation != 0f) || isFormatOrQualityModified
    val canSave: Boolean
        get() = hasEdits &&
            (selectedImageUri != null) &&
            (selectedImageFile != null) &&
            !isProcessing &&
            !isSaving &&
            ((selectedRatio == null) || (editedBitmap != null))
}

sealed interface RatioUiEvent {
    data class ShowMessage(val message: String) : RatioUiEvent
}
