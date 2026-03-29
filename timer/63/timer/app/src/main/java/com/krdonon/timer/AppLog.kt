package com.krdonon.timer

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.ScrollView
import android.widget.TextView
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
            setPadding(32, 24, 32, 24)
            textSize = 12f
        }
        val scroll = ScrollView(context).apply { addView(tv) }
        AlertDialog.Builder(context)
            .setTitle("알림/타이머 로그")
            .setView(scroll)
            .setPositiveButton("복사") { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("timer_log", logText))
            }
            .setNeutralButton("공유") { _, _ -> share(context) }
            .setNegativeButton("닫기", null)
            .show()
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
}
