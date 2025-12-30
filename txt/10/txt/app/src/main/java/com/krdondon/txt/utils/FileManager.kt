package com.krdondon.txt.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.krdondon.txt.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileManager {

    private fun downloadsCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

    suspend fun listDownloadDocs(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val collection = downloadsCollection()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val selection = "(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)"
        val selectionArgs = arrayOf("%.txt", "%.pdf")
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val result = mutableListOf<FileItem>()
        cr.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: ""
                val size = cursor.getLong(sizeCol)
                val modSec = cursor.getLong(modCol)

                val uri = Uri.withAppendedPath(collection, id.toString())
                result += FileItem(
                    uri = uri,
                    name = name,
                    mimeType = mime,
                    sizeBytes = size,
                    modifiedMillis = modSec * 1000L
                )
            }
        }
        result
    }

    /**
     * Find TXT file with same base name as PDF
     * e.g., "doc_123.pdf" -> find "doc_123.txt"
     */
    suspend fun findLinkedTxtFile(context: Context, pdfFileName: String): FileItem? = withContext(Dispatchers.IO) {
        val baseName = pdfFileName.substringBeforeLast(".")
        val txtName = "$baseName.txt"

        val cr = context.contentResolver
        val collection = downloadsCollection()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(txtName)

        cr.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: ""
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                val modSec = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED))

                val uri = Uri.withAppendedPath(collection, id.toString())
                return@withContext FileItem(
                    uri = uri,
                    name = name,
                    mimeType = mime,
                    sizeBytes = size,
                    modifiedMillis = modSec * 1000L
                )
            }
        }
        null
    }

    /**
     * Find PDF file with same base name as TXT
     */
    suspend fun findLinkedPdfUri(context: Context, txtFileName: String): Uri? = withContext(Dispatchers.IO) {
        val baseName = txtFileName.substringBeforeLast(".")
        val pdfName = "$baseName.pdf"

        val cr = context.contentResolver
        val collection = downloadsCollection()

        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(pdfName)

        cr.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return@withContext Uri.withAppendedPath(collection, id.toString())
            }
        }
        null
    }

    suspend fun readTextFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: ""
    }

    suspend fun overwriteTxtUri(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            os.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Cannot open output stream for overwrite")
    }

    suspend fun overwritePdfUri(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val pdfBytes = PdfExporter.textToPdfBytes(text)
        context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            os.write(pdfBytes)
        } ?: throw IllegalStateException("Cannot open output stream for overwrite")
    }

    suspend fun saveTxtToDownloads(context: Context, fileName: String, text: String): Uri =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val safeName = ensureExtension(fileName, "txt")

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = cr.insert(downloadsCollection(), values)
                ?: throw IllegalStateException("MediaStore insert failed")

            cr.openOutputStream(uri)?.use { os ->
                os.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Cannot open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            }
            uri
        }

    suspend fun savePdfToDownloads(context: Context, fileName: String, text: String): Uri =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val safeName = ensureExtension(fileName.substringBeforeLast("."), "pdf")

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = cr.insert(downloadsCollection(), values)
                ?: throw IllegalStateException("MediaStore insert failed")

            val pdfBytes = PdfExporter.textToPdfBytes(text)
            cr.openOutputStream(uri)?.use { os ->
                os.write(pdfBytes)
            } ?: throw IllegalStateException("Cannot open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            }
            uri
        }

    /**
     * Save both TXT and PDF with same base name
     * Returns pair of (txtUri, pdfUri)
     */
    suspend fun saveTxtAndPdfToDownloads(context: Context, fileName: String, text: String): Pair<Uri, Uri> =
        withContext(Dispatchers.IO) {
            val baseName = fileName.substringBeforeLast(".")

            // Check if TXT already exists
            val existingTxt = findLinkedTxtFile(context, "$baseName.pdf")
            val txtUri = if (existingTxt != null) {
                overwriteTxtUri(context, existingTxt.uri, text)
                existingTxt.uri
            } else {
                saveTxtToDownloads(context, baseName, text)
            }

            // Check if PDF already exists
            val existingPdf = findLinkedPdfUri(context, "$baseName.txt")
            val pdfUri = if (existingPdf != null) {
                overwritePdfUri(context, existingPdf, text)
                existingPdf
            } else {
                savePdfToDownloads(context, baseName, text)
            }

            Pair(txtUri, pdfUri)
        }

    suspend fun deleteFromDownloads(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: SecurityException) {
            false
        }
    }



    /**
     * Import an external SAF-selected document (txt/pdf) into MediaStore Downloads so that:
     * - it appears in the existing listDownloadDocs() automatically
     * - it can be opened by the app without relying on persisted SAF permissions
     *
     * This does NOT delete the original source file.
     */
    suspend fun importIntoDownloads(context: Context, sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val cr = context.contentResolver

        // Resolve display name (best effort)
        val displayName: String? = cr.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

        val srcName = (displayName ?: "import_${System.currentTimeMillis()}").trim().ifEmpty { "import_${System.currentTimeMillis()}" }

        // Decide type
        val mime = cr.getType(sourceUri)
            ?: when {
                srcName.lowercase().endsWith(".pdf") -> "application/pdf"
                srcName.lowercase().endsWith(".txt") -> "text/plain"
                else -> "application/octet-stream"
            }

        val ext = when {
            mime == "application/pdf" || srcName.lowercase().endsWith(".pdf") -> "pdf"
            mime.startsWith("text/") || srcName.lowercase().endsWith(".txt") -> "txt"
            else -> if (srcName.contains('.')) srcName.substringAfterLast('.', "bin") else "bin"
        }

        // Keep original name as much as possible, but make it unique to avoid collisions.
        val safeName = ensureExtension(srcName, ext)
        val targetName = "import_${System.currentTimeMillis()}_$safeName"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val collection = downloadsCollection()
        val destUri = cr.insert(collection, values) ?: return@withContext false

        try {
            cr.openInputStream(sourceUri)?.use { input ->
                cr.openOutputStream(destUri, "wt")?.use { output ->
                    input.copyTo(output)
                } ?: return@withContext false
            } ?: return@withContext false

            true
        } catch (_: Throwable) {
            runCatching { cr.delete(destUri, null, null) }
            false
        }
    }

    suspend fun renameInDownloads(context: Context, uri: Uri, newFileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName)
            }
            context.contentResolver.update(uri, values, null, null) > 0
        }

    fun ensureExtension(name: String, extWithoutDot: String): String {
        val ext = ".$extWithoutDot"
        return if (name.lowercase().endsWith(ext)) name else name + ext
    }
}
