package com.krdondon.read.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TxtFileHelper {

    data class LoadedTxt(
        val fileName: String,
        val content: String,
        val charCount: Int = 0
    )

    // Maximum supported TXT file size (30 MB) to prevent OOM while accommodating large novels
    private const val MAX_FILE_SIZE_BYTES = 30 * 1024 * 1024L

    suspend fun readTextFromUri(context: Context, uri: Uri): LoadedTxt? = withContext(Dispatchers.IO) {
        try {
            val fileName = queryFileName(context, uri) ?: "불러온 텍스트"

            // Check file size if available to prevent OOM
            val fileSize = queryFileSize(context, uri)
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return@withContext null
            }

            // Step 1: Detect encoding using sample bytes
            val sample = readHeaderSample(context, uri, 8192)
            val detectedCharset = detectCharset(sample)

            // Step 2: Stream read directly into StringBuilder with buffer
            val rawInputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val bufferedStream = BufferedInputStream(rawInputStream, 64 * 1024)

            // Check and skip BOM if present
            skipBomIfPresent(bufferedStream, detectedCharset)

            val estimatedCapacity = if (fileSize > 0 && fileSize < 5_000_000) fileSize.toInt() else 256 * 1024
            val stringBuilder = StringBuilder(estimatedCapacity)
            val charBuffer = CharArray(32 * 1024)

            BufferedReader(InputStreamReader(bufferedStream, detectedCharset), 64 * 1024).use { reader ->
                var charsRead: Int
                var totalChars = 0
                while (reader.read(charBuffer).also { charsRead = it } != -1) {
                    stringBuilder.append(charBuffer, 0, charsRead)
                    totalChars += charsRead
                    // Safety guard: max 15 million characters
                    if (totalChars > 15_000_000) break
                }
            }

            val title = fileName.removeSuffix(".txt").removeSuffix(".TXT")
            val fullText = stringBuilder.toString()
            LoadedTxt(fileName = title, content = fullText, charCount = fullText.length)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun readHeaderSample(context: Context, uri: Uri, sampleSize: Int): ByteArray {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(sampleSize)
                val read = stream.read(buffer)
                if (read > 0) buffer.copyOf(read) else ByteArray(0)
            } ?: ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }

        // Test UTF-8 validity on the sample
        val decoder = Charsets.UTF_8.newDecoder().apply {
            onMalformedInput(CodingErrorAction.REPORT)
            onUnmappableCharacter(CodingErrorAction.REPORT)
        }

        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            Charsets.UTF_8
        } catch (e: Exception) {
            // If UTF-8 fails, in Korean Windows/web files, it's typically EUC-KR / CP949
            try {
                Charset.forName("EUC-KR")
            } catch (ex: Exception) {
                Charset.defaultCharset()
            }
        }
    }

    private fun skipBomIfPresent(stream: BufferedInputStream, charset: Charset) {
        stream.mark(4)
        val header = ByteArray(4)
        val read = stream.read(header)
        if (read >= 3 && header[0] == 0xEF.toByte() && header[1] == 0xBB.toByte() && header[2] == 0xBF.toByte()) {
            // UTF-8 BOM is 3 bytes; reset and skip 3 bytes
            stream.reset()
            stream.skip(3)
        } else if (read >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xFE.toByte() && charset == Charsets.UTF_16LE) {
            stream.reset()
            stream.skip(2)
        } else if (read >= 2 && header[0] == 0xFE.toByte() && header[1] == 0xFF.toByte() && charset == Charsets.UTF_16BE) {
            stream.reset()
            stream.skip(2)
        } else {
            stream.reset()
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

    /**
     * Google Play Scoped Storage 규정 준수 (Android 10~16 MediaStore.Downloads):
     * 위험 권한(MANAGE_EXTERNAL_STORAGE 등) 없이 스마트폰 Download 폴더에 .txt 파일 생성 및 덮어쓰기 저장
     */
    suspend fun saveTxtToDownloads(
        context: Context,
        title: String,
        content: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = title.trim()
                .ifBlank { "새_텍스트_문서" }
                .removeSuffix(".txt")
                .removeSuffix(".TXT")
                .replace(Regex("[:/\\\\*?\"<>|]"), "_")

            val fileName = "$cleanTitle.txt"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 기존 동일 파일이 있을 경우 찾아서 덮어쓰기
                val existingUri = findExistingDownloadFileUri(context, fileName)
                val targetUri = existingUri ?: run {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                } ?: return@withContext Pair(false, "다운로드 폴더에 파일을 생성할 수 없습니다.")

                context.contentResolver.openOutputStream(targetUri, "wt")?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val targetFile = File(downloadDir, fileName)
                FileOutputStream(targetFile).use { fos ->
                    fos.write(content.toByteArray(Charsets.UTF_8))
                }
            }

            Pair(true, "다운로드 폴더에 '$fileName' 파일로 저장되었습니다.")
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, "다운로드 저장 실패: ${e.localizedMessage}")
        }
    }

    private fun findExistingDownloadFileUri(context: Context, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        return try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(idCol)
                    Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
