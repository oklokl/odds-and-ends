package com.krdondon.read.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.nio.charset.Charset

object TxtFileHelper {

    data class LoadedTxt(
        val fileName: String,
        val content: String
    )

    // Maximum supported TXT file size (15 MB) to prevent OOM crashes and high Anonymous RSS memory usage
    private const val MAX_FILE_SIZE_BYTES = 15 * 1024 * 1024L

    fun readTextFromUri(context: Context, uri: Uri): LoadedTxt? {
        return try {
            val fileName = queryFileName(context, uri) ?: "불러온 텍스트"

            // Check file size if available to prevent OOM
            val fileSize = queryFileSize(context, uri)
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return null
            }

            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.use { stream ->
                // Guard against unbounded read
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                var totalBytesRead = 0L
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytesRead += bytesRead
                    if (totalBytesRead > MAX_FILE_SIZE_BYTES) {
                        return null // File exceeds memory threshold
                    }
                    output.write(buffer, 0, bytesRead)
                }
                output.toByteArray()
            } ?: return null

            // Try UTF-8 first
            val text = try {
                val utf8 = String(bytes, Charsets.UTF_8)
                // If contains lots of replacement chars or corrupted, try EUC-KR
                if (utf8.contains('\uFFFD')) {
                    try {
                        String(bytes, Charset.forName("EUC-KR"))
                    } catch (e: Exception) {
                        utf8
                    }
                } else {
                    utf8
                }
            } catch (e: Exception) {
                String(bytes, Charset.defaultCharset())
            }

            // Remove .txt extension from title if present
            val title = fileName.removeSuffix(".txt").removeSuffix(".TXT")
            LoadedTxt(fileName = title, content = text)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun queryFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) {
                            return cursor.getLong(index)
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return -1L
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}
