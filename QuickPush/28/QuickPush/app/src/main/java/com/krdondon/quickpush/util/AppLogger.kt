package com.krdondon.quickpush.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * High-performance, lightweight in-memory and file-based diagnostic logger.
 * Designed specifically for analyzing audio lifecycle, session retention, and runtime issues.
 */
object AppLogger {

  private const val TAG = "QuickPushLogger"
  private const val MAX_LOG_COUNT = 300
  private const val LOG_EXPIRY_MILLIS = 40 * 60 * 1000L // 40 minutes
  private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"

  @OptIn(ExperimentalCoroutinesApi::class)
  private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
  private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
  private val logLock = Any()
  private val logQueue = ArrayDeque<String>(MAX_LOG_COUNT + 1)

  @Volatile private var hasCriticalErrorOccurred = false
  @Volatile private var lastActivityTimestamp = System.currentTimeMillis()

  private val _logsStateFlow = MutableStateFlow<List<String>>(emptyList())
  val logsStateFlow: StateFlow<List<String>> = _logsStateFlow.asStateFlow()

  private val threadLocalDateFormat = ThreadLocal.withInitial {
    SimpleDateFormat(DATE_PATTERN, Locale.US)
  }

  private fun formatNow(now: Long): String {
    return threadLocalDateFormat.get()?.format(Date(now)) ?: ""
  }

  private fun parseTimestamp(dateStr: String): Long {
    return try {
      threadLocalDateFormat.get()?.parse(dateStr)?.time ?: 0L
    } catch (_: Exception) {
      0L
    }
  }

  fun init(context: Context) {
    val appContext = context.applicationContext
    logInfo("AppLogger initialized (PID=${android.os.Process.myPid()})")
    scope.launch {
      purgeExpiredLogsSync(appContext)
      loadPreviousSessionLogsSync(appContext)
    }
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

    val timestamp = formatNow(now)
    val entry = "[$timestamp] [$level] $message"

    Log.d(TAG, entry)

    val snapshot: List<String>
    synchronized(logLock) {
      logQueue.addLast(entry)
      while (logQueue.size > MAX_LOG_COUNT) {
        logQueue.removeFirst()
      }
      snapshot = logQueue.toList()
    }

    _logsStateFlow.value = snapshot
  }

  /**
   * Evaluates 40-minute expiry policy and persists logs to internal storage:
   * - If an error occurred: keep logs permanently across sessions for Gemini AI analysis.
   * - If no errors: purge logs if older than 40 minutes of inactivity.
   */
  fun saveSessionSnapshot(context: Context, isTerminating: Boolean = false) {
    val appContext = context.applicationContext
    scope.launch {
      try {
        val prefs = appContext.getSharedPreferences("app_logger_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_log_timestamp", lastActivityTimestamp).apply()

        val logFile = File(appContext.filesDir, "quickpush_diagnostics.log")
        val content: String
        val count: Int
        synchronized(logLock) {
          count = logQueue.size
          content = logQueue.joinToString("\n")
        }
        logFile.writeText(content)
        Log.d(TAG, "Saved session diagnostic log ($count entries, isTerminating=$isTerminating)")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to save session diagnostic log", e)
      }
    }
  }

  fun purgeExpiredLogsIfNeeded(context: Context) {
    val appContext = context.applicationContext
    scope.launch {
      purgeExpiredLogsSync(appContext)
    }
  }

  /**
   * Checks the timestamp of the last log file before loading:
   * 1) Checks file.lastModified()
   * 2) Checks the actual timestamp string from the last line of the log file
   * 3) Checks SharedPreferences last_log_timestamp
   * If >40 minutes have passed and no error exists, purges the log file completely.
   */
  private fun purgeExpiredLogsSync(context: Context) {
    try {
      val now = System.currentTimeMillis()
      val logFile = File(context.filesDir, "quickpush_diagnostics.log")
      if (!logFile.exists() || logFile.length() == 0L) return

      val fileLines = logFile.readLines()
      val hasRecordedErrors = fileLines.any { it.contains("[ERROR]") || it.contains("Status=FAILURE") }

      if (hasRecordedErrors) {
        Log.d(TAG, "Log file contains critical errors, retaining for analysis.")
        return
      }

      val prefs = context.getSharedPreferences("app_logger_prefs", Context.MODE_PRIVATE)
      val spTimestamp = prefs.getLong("last_log_timestamp", 0L)
      val fileModified = logFile.lastModified()

      var lineTimestamp = 0L
      val lastLine = fileLines.lastOrNull { it.startsWith("[") }
      if (lastLine != null) {
        val dateStr = lastLine.substringAfter("[").substringBefore("]")
        lineTimestamp = parseTimestamp(dateStr)
      }

      val referenceTimestamp = when {
        lineTimestamp > 0L -> lineTimestamp
        spTimestamp > 0L -> spTimestamp
        fileModified > 0L -> fileModified
        else -> 0L
      }

      val elapsed = now - referenceTimestamp

      if (referenceTimestamp > 0L && elapsed > LOG_EXPIRY_MILLIS) {
        logFile.delete()
        prefs.edit().remove("last_log_timestamp").apply()
        synchronized(logLock) {
          logQueue.clear()
          _logsStateFlow.value = emptyList()
        }
        Log.d(TAG, "Logs expired (elapsed=${elapsed / 1000}s > 2400s), successfully purged.")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to purge expired logs", e)
    }
  }

  private fun loadPreviousSessionLogsSync(context: Context) {
    try {
      val logFile = File(context.filesDir, "quickpush_diagnostics.log")
      if (logFile.exists() && logFile.length() > 0) {
        val lines = logFile.readLines().takeLast(MAX_LOG_COUNT)
        val snapshot: List<String>
        synchronized(logLock) {
          val currentEntries = logQueue.toList()
          logQueue.clear()
          val combined = (lines + currentEntries).takeLast(MAX_LOG_COUNT)
          combined.forEach { logQueue.addLast(it) }
          snapshot = logQueue.toList()
        }
        _logsStateFlow.value = snapshot
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load previous session logs", e)
    }
  }

  fun clearLogs(context: Context) {
    val appContext = context.applicationContext
    synchronized(logLock) {
      logQueue.clear()
      _logsStateFlow.value = emptyList()
    }
    hasCriticalErrorOccurred = false
    scope.launch {
      try {
        val logFile = File(appContext.filesDir, "quickpush_diagnostics.log")
        if (logFile.exists()) {
          logFile.delete()
        }
      } catch (_: Exception) {}
    }
    logInfo("Diagnostic logs cleared by user")
  }

  fun getLogsFormattedText(): String {
    val sb = StringBuilder()
    sb.append("=== QuickPush Diagnostics Log ===\n")
    sb.append("Generated at: ${formatNow(System.currentTimeMillis())}\n")
    sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n")
    sb.append("Critical Error Recorded: $hasCriticalErrorOccurred\n\n")
    synchronized(logLock) {
      sb.append(logQueue.joinToString("\n"))
    }
    return sb.toString()
  }

  /**
   * Exports the diagnostic log file into the public Downloads directory (or app Downloads fallback on API < 29).
   */
  fun exportLogToDownloads(context: Context, onResult: (Boolean, String) -> Unit) {
    val appContext = context.applicationContext
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
          val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          if (uri != null) {
            appContext.contentResolver.openOutputStream(uri)?.use { os ->
              os.write(text.toByteArray())
            }
            withContext(Dispatchers.Main) {
              onResult(true, "다운로드 폴더에 저장되었습니다:\n$fileName")
            }
          } else {
            withContext(Dispatchers.Main) {
              onResult(false, "저장 공간 URI 생성 실패")
            }
          }
        } else {
          val hasWritePerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
          ) == PackageManager.PERMISSION_GRANTED

          @Suppress("DEPRECATION")
          val targetDir = if (hasWritePerm) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
          } else {
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
          }
          targetDir?.mkdirs()
          val file = File(targetDir, fileName)
          FileOutputStream(file).use { os ->
            os.write(text.toByteArray())
          }
          withContext(Dispatchers.Main) {
            onResult(true, "로그가 저장되었습니다:\n${file.absolutePath}")
          }
        }
      } catch (e: Exception) {
        logError("Failed to export log to Downloads: ${e.message}", e)
        withContext(Dispatchers.Main) {
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
