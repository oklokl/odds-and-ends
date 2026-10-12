package com.krdondon.quickpush.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * High-performance, lightweight in-memory and file-based diagnostic logger.
 * Designed specifically for analyzing audio lifecycle, session retention, and runtime issues.
 */
object AppLogger {

  private const val TAG = "QuickPushLogger"
  private const val MAX_LOG_COUNT = 300
  private const val LOG_EXPIRY_MILLIS = 40 * 60 * 1000L // 40 minutes

  private val scope = CoroutineScope(Dispatchers.IO)
  private val logQueue = ConcurrentLinkedQueue<String>()
  private var hasCriticalErrorOccurred = false
  private var lastActivityTimestamp = System.currentTimeMillis()

  private val _logsStateFlow = MutableStateFlow<List<String>>(emptyList())
  val logsStateFlow: StateFlow<List<String>> = _logsStateFlow.asStateFlow()

  private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

  fun init(context: Context) {
    loadPreviousSessionLogs(context)
    logInfo("AppLogger initialized (PID=${android.os.Process.myPid()})")
  }

  fun logInfo(message: String) {
    appendEntry("INFO", message)
  }

  fun logAudio(action: String, success: Boolean, details: String) {
    val status = if (success) "SUCCESS" else "FAILURE"
    val msg = "[BGM] Action=$action | Status=$status | Details=$details"
    appendEntry("AUDIO", msg)
  }

  fun logWarn(message: String) {
    appendEntry("WARN", message)
  }

  fun logError(message: String, throwable: Throwable? = null) {
    hasCriticalErrorOccurred = true
    val stackTrace = throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""
    appendEntry("ERROR", "$message$stackTrace")
  }

  private fun appendEntry(level: String, message: String) {
    val now = System.currentTimeMillis()
    lastActivityTimestamp = now

    val timestamp = timeFormat.format(Date(now))
    val entry = "[$timestamp] [$level] $message"

    Log.d(TAG, entry)

    logQueue.add(entry)
    while (logQueue.size > MAX_LOG_COUNT) {
      logQueue.poll()
    }

    _logsStateFlow.value = logQueue.toList()
  }

  /**
   * Evaluates 40-minute expiry policy and persists logs to internal storage:
   * - If an error occurred: keep logs permanently across sessions for Gemini AI analysis.
   * - If no errors: purge if older than 40 minutes of inactivity.
   */
  fun saveSessionSnapshot(context: Context, isTerminating: Boolean = false) {
    scope.launch {
      try {
        val now = System.currentTimeMillis()
        val isExpired = (now - lastActivityTimestamp) > LOG_EXPIRY_MILLIS

        val logFile = File(context.filesDir, "quickpush_diagnostics.log")

        if (isExpired && !hasCriticalErrorOccurred) {
          if (logFile.exists()) {
            logFile.delete()
          }
          logQueue.clear()
          _logsStateFlow.value = emptyList()
          return@launch
        }

        // Persist queue to file
        val content = logQueue.joinToString("\n")
        logFile.writeText(content)
        Log.d(TAG, "Saved session diagnostic log (${logQueue.size} entries, isTerminating=$isTerminating)")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to save session diagnostic log", e)
      }
    }
  }

  private fun loadPreviousSessionLogs(context: Context) {
    try {
      val logFile = File(context.filesDir, "quickpush_diagnostics.log")
      if (logFile.exists() && logFile.length() > 0) {
        val lines = logFile.readLines().takeLast(MAX_LOG_COUNT)
        logQueue.addAll(lines)
        _logsStateFlow.value = lines
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load previous session logs", e)
    }
  }

  fun clearLogs(context: Context) {
    logQueue.clear()
    _logsStateFlow.value = emptyList()
    hasCriticalErrorOccurred = false
    try {
      val logFile = File(context.filesDir, "quickpush_diagnostics.log")
      if (logFile.exists()) {
        logFile.delete()
      }
    } catch (_: Exception) {}
    logInfo("Diagnostic logs cleared by user")
  }

  fun getLogsFormattedText(): String {
    val sb = StringBuilder()
    sb.append("=== QuickPush Diagnostics Log ===\n")
    sb.append("Generated at: ${timeFormat.format(Date())}\n")
    sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n")
    sb.append("Critical Error Recorded: $hasCriticalErrorOccurred\n\n")
    sb.append(logQueue.joinToString("\n"))
    return sb.toString()
  }

  /**
   * Exports the diagnostic log file into the public Downloads directory.
   */
  fun exportLogToDownloads(context: Context, onResult: (Boolean, String) -> Unit) {
    scope.launch {
      val text = getLogsFormattedText()
      val fileName = "quickpush_bgm_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"

      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
          }
          val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { os ->
              os.write(text.toByteArray())
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
              onResult(true, "다운로드 폴더에 저장되었습니다:\n$fileName")
            }
          } else {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
              onResult(false, "저장 공간 URI 생성 실패")
            }
          }
        } else {
          @Suppress("DEPRECATION")
          val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
          val file = File(downloadsDir, fileName)
          FileOutputStream(file).use { os ->
            os.write(text.toByteArray())
          }
          kotlinx.coroutines.withContext(Dispatchers.Main) {
            onResult(true, "다운로드 폴더에 저장되었습니다:\n${file.name}")
          }
        }
      } catch (e: Exception) {
        logError("Failed to export log to Downloads: ${e.message}", e)
        kotlinx.coroutines.withContext(Dispatchers.Main) {
          onResult(false, "저장 중 오류: ${e.message}")
        }
      }
    }
  }

  /**
   * Creates an Android Share Intent with the log content.
   */
  fun createShareIntent(): Intent {
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, "QuickPush Diagnostic Log")
      putExtra(Intent.EXTRA_TEXT, getLogsFormattedText())
    }
    return Intent.createChooser(intent, "Share QuickPush Diagnostic Log")
  }
}
