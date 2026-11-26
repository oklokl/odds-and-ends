package com.krdonon.ratio

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal const val PREVIEW_MAX_DIMENSION = 2048
private const val BYTES_PER_PIXEL = 4L
private const val MIN_EXPORT_MEMORY_BUDGET = 48L * 1024L * 1024L
private const val MAX_EXPORT_MEMORY_BUDGET = 160L * 1024L * 1024L
private const val EXPORT_MEMORY_FRACTION = 0.30
private const val CACHE_FILE_PREFIX = "ratio_selected_"
private const val CACHE_FILE_SUFFIX = ".img"

internal data class ExifTransform(
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
) {
    val isIdentity: Boolean
        get() = rotationDegrees == 0f && !flipHorizontal && !flipVertical

    val swapsDimensions: Boolean
        get() = rotationDegrees == 90f || rotationDegrees == 270f
}

internal fun cleanupStaleCacheFiles(cacheDir: File, keepFile: File? = null) {
    val files = cacheDir.listFiles { file ->
        file.isFile &&
            file.name.startsWith(CACHE_FILE_PREFIX) &&
            file.name.endsWith(CACHE_FILE_SUFFIX)
    } ?: return

    for (file in files) {
        if (keepFile == null || file.absolutePath != keepFile.absolutePath) {
            file.delete()
        }
    }
}

/**
 * Photo Picker/Document Provider가 돌려준 URI는 공급자에 따라 재오픈 동작이 다를 수 있습니다.
 * 선택 직후 앱 전용 cache 파일로 한 번만 복사한 뒤 모든 디코딩/EXIF 처리는 로컬 파일에서 수행합니다.
 */
internal fun copySelectedImageToCache(context: Context, uri: Uri): File {
    val cacheFile = File.createTempFile(CACHE_FILE_PREFIX, CACHE_FILE_SUFFIX, context.cacheDir)

    try {
        openImageInputStream(context, uri).use { input ->
            cacheFile.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }

        if (cacheFile.length() <= 0L) {
            throw IOException("선택한 이미지 데이터가 비어 있습니다")
        }

        return cacheFile
    } catch (e: Exception) {
        cacheFile.delete()
        if (e is IOException) throw e
        throw IOException(e.message ?: "선택한 이미지 데이터를 읽을 수 없습니다", e)
    }
}

/**
 * URI 공급자 호환성을 높이기 위해 일반 InputStream -> FileDescriptor -> typed asset 순서로 시도합니다.
 */
private fun openImageInputStream(context: Context, uri: Uri): InputStream {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        val path = uri.path ?: throw IOException("이미지 파일 경로가 없습니다")
        return File(path).inputStream()
    }

    val resolver = context.contentResolver
    var firstError: Exception? = null

    try {
        resolver.openInputStream(uri)?.let { return it }
    } catch (e: Exception) {
        firstError = e
    }

    try {
        resolver.openFileDescriptor(uri, "r")?.let { descriptor ->
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        }
    } catch (e: Exception) {
        if (firstError == null) firstError = e
    }

    try {
        resolver.openTypedAssetFileDescriptor(uri, "image/*", null)?.let { descriptor ->
            return descriptor.createInputStream()
        }
    } catch (e: Exception) {
        if (firstError == null) firstError = e
    }

    val reason = firstError?.message?.takeIf { it.isNotBlank() }
    throw IOException(
        if (reason != null) {
            "이미지 스트림을 열 수 없습니다 ($reason)"
        } else {
            "이미지 스트림을 열 수 없습니다"
        },
        firstError,
    )
}

/**
 * 화면 표시용 Bitmap은 장시간 메모리에 남기 때문에 최대 크기를 제한해서 디코딩합니다.
 */
fun loadBitmapWithOrientation(
    sourceFile: File,
    maxDimension: Int,
): Bitmap {
    val bounds = readBitmapBounds(sourceFile)
    val inSampleSize = calculateInSampleSizeForMaxDimension(
        width = bounds.first,
        height = bounds.second,
        maxDimension = maxDimension,
    )

    val bitmap = decodeBitmap(sourceFile, inSampleSize)
    val exifTransform = readExifTransform(sourceFile)
    return applyTransformAndRecycleSource(bitmap, exifTransform)
}

/**
 * 저장용 원본은 기기의 heap 크기와 목표 비율을 고려해 가능한 높은 해상도로 디코딩합니다.
 */
fun loadBitmapForExport(
    context: Context,
    sourceFile: File,
    targetRatio: Float?,
    additionalRotation: Float,
    minSampleSize: Int = 1,
): Bitmap {
    val (rawWidth, rawHeight) = readBitmapBounds(sourceFile)
    val exifTransform = readExifTransform(sourceFile)
    val totalRotation = normalizeRotation(exifTransform.rotationDegrees + additionalRotation)
    val combinedTransform = exifTransform.copy(rotationDegrees = totalRotation)
    val orientedWidth = if (combinedTransform.swapsDimensions) rawHeight else rawWidth
    val orientedHeight = if (combinedTransform.swapsDimensions) rawWidth else rawHeight

    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val heapBytes = activityManager.memoryClass.toLong() * 1024L * 1024L
    val memoryBudget = (heapBytes * EXPORT_MEMORY_FRACTION).toLong()
        .coerceIn(MIN_EXPORT_MEMORY_BUDGET, MAX_EXPORT_MEMORY_BUDGET)

    var inSampleSize = max(1, minSampleSize)
    while (
        estimateWorkingSetBytes(
            width = max(1, orientedWidth / inSampleSize),
            height = max(1, orientedHeight / inSampleSize),
            targetRatio = targetRatio,
            transformNeeded = !combinedTransform.isIdentity,
        ) > memoryBudget &&
        max(orientedWidth, orientedHeight) / inSampleSize > 1
    ) {
        inSampleSize *= 2
    }

    val bitmap = decodeBitmap(sourceFile, inSampleSize)
    return applyTransformAndRecycleSource(bitmap, combinedTransform)
}

internal fun readExifTransform(sourceFile: File): ExifTransform {
    return try {
        val exif = ExifInterface(sourceFile.absolutePath)
        when (
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(rotationDegrees = 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(rotationDegrees = 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(rotationDegrees = 270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(flipHorizontal = true)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(flipVertical = true)
            ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(
                rotationDegrees = 90f,
                flipHorizontal = true,
            )
            ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(
                rotationDegrees = 270f,
                flipHorizontal = true,
            )
            else -> ExifTransform()
        }
    } catch (_: Exception) {
        ExifTransform()
    }
}

fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val normalized = normalizeRotation(degrees)
    if (normalized == 0f) return bitmap

    val matrix = Matrix().apply { postRotate(normalized) }
    return Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        false,
    )
}

private fun applyTransformAndRecycleSource(bitmap: Bitmap, transform: ExifTransform): Bitmap {
    if (transform.isIdentity) return bitmap

    val matrix = Matrix().apply {
        if (transform.rotationDegrees != 0f) {
            postRotate(transform.rotationDegrees)
        }
        if (transform.flipHorizontal || transform.flipVertical) {
            postScale(
                if (transform.flipHorizontal) -1f else 1f,
                if (transform.flipVertical) -1f else 1f,
            )
        }
    }

    val transformed = Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        false,
    )
    if (transformed !== bitmap && !bitmap.isRecycled) {
        bitmap.recycle()
    }
    return transformed
}

fun resizeToAspectRatio(
    bitmap: Bitmap,
    targetRatio: Float,
    backgroundColor: Int = android.graphics.Color.WHITE,
): Bitmap {
    val (canvasWidth, canvasHeight) = calculateCanvasSize(
        bitmap.width,
        bitmap.height,
        targetRatio,
    )

    val newBitmap = createBitmap(canvasWidth, canvasHeight)
    val canvas = android.graphics.Canvas(newBitmap)
    canvas.drawColor(backgroundColor)

    val left = (canvasWidth - bitmap.width) / 2f
    val top = (canvasHeight - bitmap.height) / 2f
    canvas.drawBitmap(bitmap, left, top, null)

    return newBitmap
}

internal fun calculateCanvasSize(
    originalWidth: Int,
    originalHeight: Int,
    targetRatio: Float,
): Pair<Int, Int> {
    require(originalWidth > 0 && originalHeight > 0) { "잘못된 이미지 크기입니다." }
    require(targetRatio > 0f && targetRatio.isFinite()) { "잘못된 목표 비율입니다." }

    val originalRatio = originalWidth.toFloat() / originalHeight.toFloat()
    if (abs(originalRatio - targetRatio) < 1e-4f) {
        return originalWidth to originalHeight
    }

    return when {
        originalRatio > targetRatio -> {
            originalWidth to max(1, (originalWidth / targetRatio).roundToInt())
        }
        else -> {
            max(1, (originalHeight * targetRatio).roundToInt()) to originalHeight
        }
    }
}

private fun readBitmapBounds(sourceFile: File): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourceFile.absolutePath, options)

    if (options.outWidth <= 0 || options.outHeight <= 0) {
        throw IOException("이미지 크기를 확인할 수 없습니다")
    }

    return options.outWidth to options.outHeight
}

private fun decodeBitmap(sourceFile: File, inSampleSize: Int): Bitmap {
    val options = BitmapFactory.Options().apply {
        this.inSampleSize = max(1, inSampleSize)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    return BitmapFactory.decodeFile(sourceFile.absolutePath, options)
        ?: throw IOException("이미지를 디코딩할 수 없습니다")
}

internal fun calculateInSampleSizeForMaxDimension(
    width: Int,
    height: Int,
    maxDimension: Int,
): Int {
    var sample = 1
    val longest = max(width, height)
    while (longest / sample > maxDimension && longest / sample > 1) {
        sample *= 2
    }
    return sample
}

internal fun estimateWorkingSetBytes(
    width: Int,
    height: Int,
    targetRatio: Float?,
    transformNeeded: Boolean,
): Long {
    val sourceBytes = width.toLong() * height.toLong() * BYTES_PER_PIXEL
    val outputBytes = if (targetRatio != null) {
        val (canvasWidth, canvasHeight) = calculateCanvasSize(width, height, targetRatio)
        canvasWidth.toLong() * canvasHeight.toLong() * BYTES_PER_PIXEL
    } else {
        0L
    }
    val rotationPeak = if (transformNeeded) sourceBytes else 0L
    val safetyMargin = 8L * 1024L * 1024L

    return sourceBytes + rotationPeak + outputBytes + safetyMargin
}

internal fun normalizeRotation(degrees: Float): Float {
    val normalized = ((degrees % 360f) + 360f) % 360f
    return when {
        normalized in 45f..<135f -> 90f
        normalized in 135f..<225f -> 180f
        normalized in 225f..<315f -> 270f
        else -> 0f
    }
}

fun exportEditedImage(
    context: Context,
    sourceFile: File,
    originalUri: Uri,
    targetRatio: Float?,
    backgroundColor: Int,
    additionalRotation: Float,
    format: ImageFormat,
    quality: Int,
) {
    var extraSampleMultiplier = 1
    while (true) {
        var source: Bitmap? = null
        var output: Bitmap? = null
        try {
            source = loadBitmapForExport(
                context = context,
                sourceFile = sourceFile,
                targetRatio = targetRatio,
                additionalRotation = additionalRotation,
                minSampleSize = extraSampleMultiplier,
            )
            output = if (targetRatio != null) {
                resizeToAspectRatio(source, targetRatio, backgroundColor)
            } else {
                source
            }
            saveImage(
                context = context,
                bitmap = output,
                originalUri = originalUri,
                format = format,
                quality = quality,
            )
            return
        } catch (oom: OutOfMemoryError) {
            if (extraSampleMultiplier >= 16) {
                throw IOException("메모리가 부족하여 이미지를 저장할 수 없습니다", oom)
            }
            extraSampleMultiplier *= 2
        } finally {
            if (output != null && output !== source && !output.isRecycled) {
                output.recycle()
            }
            if (source != null && !source.isRecycled) {
                source.recycle()
            }
        }
    }
}

internal fun resolveCompressFormat(
    format: ImageFormat,
    quality: Int,
): Bitmap.CompressFormat = when (format) {
    ImageFormat.JPG -> Bitmap.CompressFormat.JPEG
    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
    ImageFormat.WEBP -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (quality >= 100) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP_LOSSY
            }
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
    }
}

internal fun buildExportFileName(
    originalNameWithoutExt: String,
    format: ImageFormat,
    timestamp: String,
): String {
    val extension = format.extension
    var baseFileName = "${originalNameWithoutExt}_${timestamp}"
    val maxLength = 250 - extension.length - 1
    if (baseFileName.length > maxLength) {
        val timestampWithUnderscore = "_${timestamp}"
        val maxOriginalLength = maxLength - timestampWithUnderscore.length

        baseFileName = if (maxOriginalLength > 0) {
            val truncatedOriginal = if (originalNameWithoutExt.length > maxOriginalLength) {
                if (maxOriginalLength > 10) {
                    originalNameWithoutExt.take(maxOriginalLength - 3) + "~"
                } else {
                    originalNameWithoutExt.take(maxOriginalLength)
                }
            } else {
                originalNameWithoutExt
            }
            "${truncatedOriginal}_${timestamp}"
        } else {
            "IMG_${timestamp}"
        }
    }
    return "${baseFileName}.${extension}"
}

fun saveImage(
    context: Context,
    bitmap: Bitmap,
    originalUri: Uri?,
    format: ImageFormat,
    quality: Int,
) {
    val originalFileNameWithoutExt = getImprovedFileNameFromUri(context, originalUri)
    val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
    val timestamp = dateFormat.format(Date())
    val finalFileName = buildExportFileName(originalFileNameWithoutExt, format, timestamp)
    val clampedQuality = quality.coerceIn(1, 100)

    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
        put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
        put(MediaStore.Images.Media.WIDTH, bitmap.width)
        put(MediaStore.Images.Media.HEIGHT, bitmap.height)
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues,
    ) ?: throw IOException("미디어 저장소에 접근할 수 없습니다")

    try {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            val compressFormat = resolveCompressFormat(format, clampedQuality)
            if (!bitmap.compress(compressFormat, clampedQuality, outputStream)) {
                throw IOException("이미지 압축에 실패했습니다")
            }
        } ?: throw IOException("파일 스트림을 열 수 없습니다")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, publishValues, null, null)
        }
    } catch (e: Exception) {
        context.contentResolver.delete(uri, null, null)
        throw e
    }
}

fun getImprovedFileNameFromUri(context: Context, uri: Uri?): String {
    uri ?: return "IMG"

    var fileName: String? = null

    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
    } catch (_: Exception) {
        // 다음 방법으로 진행합니다.
    }

    if (fileName == null && uri.scheme == ContentResolver.SCHEME_CONTENT) {
        try {
            val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(0)
                }
            }
        } catch (_: Exception) {
            // 다음 방법으로 진행합니다.
        }
    }

    if (fileName == null) {
        fileName = uri.lastPathSegment
        if (fileName?.all { it.isDigit() } == true) {
            fileName = null
        }
    }

    val validFileName = fileName
    if (validFileName == null || validFileName.length < 3 || validFileName.all { it.isDigit() }) {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return "IMG_${dateFormat.format(Date())}"
    }

    val lastDotIndex = validFileName.lastIndexOf('.')
    return if (lastDotIndex > 0) {
        validFileName.substring(0, lastDotIndex)
    } else {
        validFileName
    }
}
