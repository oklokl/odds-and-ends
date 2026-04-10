package com.krdonon.timer

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val DIR_NAME = "debug_logs"
    private const val FILE_NAME = "timer_debug.log"
    private const val BACKUP_FILE_NAME = "timer_debug_prev.log"
    private const val SHARE_FILE_NAME = "timer_debug_share.log"
    private const val SAVE_FILE_PREFIX = "timer_debug_share"
    private const val SAVE_FILE_EXTENSION = ".log"
    private const val MAX_BYTES = 512 * 1024L
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private fun file(context: Context): File = File(dir(context), FILE_NAME)
    private fun backupFile(context: Context): File = File(dir(context), BACKUP_FILE_NAME)

    @Synchronized
    fun d(context: Context, tag: String, message: String) {
        android.util.Log.d(tag, message)
        append(context, "D", tag, message, null)
    }

    @Synchronized
    fun e(context: Context, tag: String, message: String, tr: Throwable? = null) {
        android.util.Log.e(tag, message, tr)
        append(context, "E", tag, message, tr)
    }

    @Synchronized
    fun i(context: Context, tag: String, message: String) {
        android.util.Log.i(tag, message)
        append(context, "I", tag, message, null)
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
        runCatching { backupFile(context).delete() }
        runCatching { File(dir(context), SHARE_FILE_NAME).delete() }
    }

    @Synchronized
    fun read(context: Context): String {
        val current = file(context)
        val previous = backupFile(context)
        val blocks = mutableListOf<String>()

        if (previous.exists()) {
            val previousText = runCatching {
                previous.readText(Charsets.UTF_8)
            }.getOrElse {
                "로그 읽기 실패: ${it.message}"
            }
            blocks += "[이전 로그]\n$previousText"
        }

        if (current.exists()) {
            val currentText = runCatching {
                current.readText(Charsets.UTF_8)
            }.getOrElse {
                "로그 읽기 실패: ${it.message}"
            }
            blocks += "[현재 로그]\n$currentText"
        }

        return if (blocks.isEmpty()) {
            "저장된 로그가 없습니다."
        } else {
            blocks.joinToString("\n\n")
        }
    }

    @Synchronized
    fun getLogFile(context: Context): File = buildCombinedLogFile(context)

    fun showLogDialog(context: Context) {
        val logText = read(context)
        val tv = TextView(context).apply {
            text = logText
            setTextIsSelectable(true)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            textSize = 12f
        }
        val scroll = ScrollView(context).apply {
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun addActionButton(text: String, onClick: () -> Unit): Button {
            val button = Button(context).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener { onClick() }
            }
            actions.addView(button)
            return button
        }

        val actionsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            addView(scroll)
            addView(actionsScroll)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("알림/타이머 로그")
            .setView(root)
            .create()

        addActionButton("공유") { share(context) }
        addActionButton("저장") {
            val savedName = saveAsIndexedLogFile(context)
            if (savedName != null) {
                Toast.makeText(context, "$savedName 저장됨", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "로그 저장에 실패했습니다", Toast.LENGTH_SHORT).show()
            }
        }
        addActionButton("닫기") { dialog.dismiss() }
        addActionButton("복사") {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("timer_log", logText))
            Toast.makeText(context, "로그를 복사했습니다", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    fun share(context: Context) {
        val f = getLogFile(context)
        if (!f.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            f
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "timer_debug.log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "로그 공유"))
    }

    private fun append(context: Context, level: String, tag: String, message: String, tr: Throwable?) {
        rotateIfNeeded(context)
        val line = buildString {
            append(ts.format(Date()))
            append(" ")
            append(level)
            append("/")
            append(tag)
            append(": ")
            append(message)
            if (tr != null) {
                append('\n')
                append(android.util.Log.getStackTraceString(tr))
            }
            append('\n')
        }
        runCatching { file(context).appendText(line, Charsets.UTF_8) }
    }

    private fun rotateIfNeeded(context: Context) {
        val current = file(context)
        if (current.exists() && current.length() > MAX_BYTES) {
            val previous = backupFile(context)
            runCatching {
                if (previous.exists()) previous.delete()
                current.copyTo(previous, overwrite = true)
                current.writeText("", Charsets.UTF_8)
            }
        }
    }

    private fun buildCombinedLogFile(context: Context): File {
        val out = File(dir(context), SHARE_FILE_NAME)
        val content = read(context)
        runCatching { out.writeText(content, Charsets.UTF_8) }
        return out
    }

    private fun saveAsIndexedLogFile(context: Context): String? {
        val content = read(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloadsViaMediaStore(context, content)
        } else {
            saveToAppExternalDirectory(context, content)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsViaMediaStore(context: Context, content: String): String? {
        val resolver = context.contentResolver
        var index = 1
        while (index <= 9999) {
            val displayName = "$SAVE_FILE_PREFIX$index$SAVE_FILE_EXTENSION"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/TimerAppLogs/", displayName)
            val exists = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                selectionArgs,
                null
            )?.use { it.moveToFirst() } == true
            if (!exists) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TimerAppLogs/")
                }
                val uri = resolver.insert(collection, values) ?: return null
                return runCatching {
                    resolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(content)
                    } ?: throw IllegalStateException("openOutputStream returned null")
                    displayName
                }.getOrNull()
            }
            index += 1
        }
        return null
    }

    private fun saveToAppExternalDirectory(context: Context, content: String): String? {
        val baseDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "TimerAppLogs"
        ).apply { mkdirs() }

        var index = 1
        while (index <= 9999) {
            val name = "$SAVE_FILE_PREFIX$index$SAVE_FILE_EXTENSION"
            val outFile = File(baseDir, name)
            if (!outFile.exists()) {
                return runCatching {
                    outFile.writeText(content, Charsets.UTF_8)
                    name
                }.getOrNull()
            }
            index += 1
        }
        return null
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
