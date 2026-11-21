package com.krdondon.exorcismprayer

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Local-only [BitmapLoader] that decodes and downsamples app resources and byte arrays
 * off the main thread without pulling in [androidx.media3.datasource.DefaultHttpDataSource]
 * or network bitmap decoding paths.
 */
@OptIn(UnstableApi::class)
class LocalArtworkBitmapLoader(
    context: Context,
    private val maxSizePx: Int = DEFAULT_MAX_SIZE_PX
) : BitmapLoader {

    private val appContext = context.applicationContext
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/", ignoreCase = true)
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return executor.submit(Callable {
            decodeByteArrayDownsampled(data)
        })
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return executor.submit(Callable {
            when (uri.scheme) {
                ContentResolver.SCHEME_ANDROID_RESOURCE -> loadAndroidResourceBitmap(uri)
                ContentResolver.SCHEME_CONTENT,
                ContentResolver.SCHEME_FILE -> loadLocalUriBitmap(uri)
                else -> throw IOException("Unsupported artwork URI scheme: ${uri.scheme}")
            }
        })
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun decodeByteArrayDownsampled(data: ByteArray): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(data))
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                configureTargetSize(decoder, info.size.width, info.size.height)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSizePx)
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
            ?: throw IOException("Failed to decode artwork byte array")
    }

    private fun loadLocalUriBitmap(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(appContext.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                configureTargetSize(decoder, info.size.width, info.size.height)
            }
        }
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Unable to open artwork URI: $uri")
        return decodeByteArrayDownsampled(bytes)
    }

    private fun loadAndroidResourceBitmap(uri: Uri): Bitmap {
        val resourceId = uri.lastPathSegment?.toIntOrNull() ?: R.mipmap.ic_launcher
        val drawable = ContextCompat.getDrawable(appContext, resourceId)
            ?: throw IOException("Unable to load drawable resource: $resourceId")

        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val sourceBitmap = drawable.bitmap
            if (sourceBitmap.width <= maxSizePx && sourceBitmap.height <= maxSizePx) {
                return sourceBitmap
            }
            val scale = maxSizePx.toFloat() / max(sourceBitmap.width, sourceBitmap.height)
            val targetWidth = (sourceBitmap.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (sourceBitmap.height * scale).toInt().coerceAtLeast(1)
            return sourceBitmap.scale(targetWidth, targetHeight)
        }

        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxSizePx
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxSizePx
        val scale = minOf(1f, maxSizePx.toFloat() / max(intrinsicWidth, intrinsicHeight))
        val targetWidth = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (intrinsicHeight * scale).toInt().coerceAtLeast(1)

        val bitmap = createBitmap(targetWidth, targetHeight)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, targetWidth, targetHeight)
        drawable.draw(canvas)
        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun configureTargetSize(decoder: ImageDecoder, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val maxDim = max(width, height)
        if (maxDim > maxSizePx) {
            val scale = maxSizePx.toFloat() / maxDim
            decoder.setTargetSize(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1)
            )
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        if (width > maxDim || height > maxDim) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while ((halfWidth / inSampleSize) >= maxDim && (halfHeight / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    companion object {
        private const val DEFAULT_MAX_SIZE_PX = 256
    }
}
