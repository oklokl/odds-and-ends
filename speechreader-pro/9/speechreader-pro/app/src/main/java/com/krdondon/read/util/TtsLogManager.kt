package com.krdondon.read.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * TTS 진단 및 에러 분석용 실시간 로그 매니저
 *
 * - 앱 시작 시마다 기존 로그를 새로 덮어쓰기하여 디스크/메모리 용량을 최소화합니다.
 * - 앱을 사용하지 않을 때(백그라운드 전환 후 일정 시간 경과 시) 로그를 자동 정리하는 타이머를 지원합니다.
 * - 클립보드 복사, 텍스트 공유(Gmail 등), 다운로드 폴더 .txt 저장을 지원합니다.
 */
object TtsLogManager {

    private const val TAG = "TtsLogManager"
    private const val MAX_LOG_ENTRIES = 800
    private const val LOG_FILE_NAME = "tts_diagnostic_log.txt"
    private const val INACTIVITY_CLEANUP_DELAY_MS = 40 * 60 * 1000L // 40 minutes inactivity cleanup

    private val logEntries = mutableListOf<String>()
    private val _logTextFlow = MutableStateFlow("")
    val logTextFlow: StateFlow<String> = _logTextFlow.asStateFlow()

    private var cleanupTimer: Timer? = null
    private fun getTimestamp(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /**
     * Initialize diagnostic logging session on application launch
     */
    fun initOnAppStart(context: Context) {
        cancelCleanupTimer()
        synchronized(logEntries) {
            logEntries.clear()
        }

        // Reset and overwrite existing cache file
        try {
            val file = File(context.cacheDir, LOG_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}

        log("SESSION_INIT", "STATUS=STARTED, app='K Reader', sessionTime='${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}'")
        log("DEVICE_INFO", "manufacturer='${Build.MANUFACTURER}', model='${Build.MODEL}', brand='${Build.BRAND}', osRelease='${Build.VERSION.RELEASE}', apiLevel=${Build.VERSION.SDK_INT}, locale='${Locale.getDefault()}'")

        val hasSamsungTts = isPackageInstalled(context, "com.samsung.SMT")
        val hasGoogleTts = isPackageInstalled(context, "com.google.android.tts")
        log("TTS_PACKAGE_VISIBILITY", "hasSamsungTts=$hasSamsungTts (com.samsung.SMT), hasGoogleTts=$hasGoogleTts (com.google.android.tts)")
    }

    fun isPackageInstalled(context: Context, pkgName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkgName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkgName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 일반 정보 로그 기록
     */
    fun log(tag: String, message: String) {
        val timestamp = getTimestamp()
        val logLine = "[$timestamp][$tag] $message"
        Log.i("TTS_LOG", logLine)

        synchronized(logEntries) {
            if (logEntries.size >= MAX_LOG_ENTRIES) {
                logEntries.removeAt(0)
            }
            logEntries.add(logLine)
            _logTextFlow.value = logEntries.joinToString("\n")
        }
    }

    /**
     * Record error log with exception stacktrace in AI-parseable format
     */
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = getTimestamp()
        val errorDetail = if (throwable != null) "\nexception='${throwable.javaClass.name}', exceptionMessage='${throwable.message}'\nstacktrace:\n${Log.getStackTraceString(throwable)}" else ""
        val logLine = "[$timestamp][ERROR][$tag] $message$errorDetail"
        Log.e("TTS_LOG", logLine)

        synchronized(logEntries) {
            if (logEntries.size >= MAX_LOG_ENTRIES) {
                logEntries.removeAt(0)
            }
            logEntries.add(logLine)
            _logTextFlow.value = logEntries.joinToString("\n")
        }
    }

    /**
     * 전체 로그 텍스트 반환
     */
    fun getFullLogText(): String {
        return synchronized(logEntries) {
            if (logEntries.isEmpty()) {
                "기록된 로그가 없습니다."
            } else {
                logEntries.joinToString("\n")
            }
        }
    }

    /**
     * 클립보드에 로그 복사
     */
    fun copyToClipboard(context: Context): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TTS Diagnostic Log", getFullLogText())
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            logError("LOG", "클립보드 복사 실패", e)
            false
        }
    }

    /**
     * 로그를 기기의 '다운로드(Download)' 폴더에 .txt 파일로 저장
     */
    fun saveLogToDownloads(context: Context): Pair<Boolean, String> {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "KReader_TTS_Log_$timestamp.txt"
        val logContent = getFullLogText()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return Pair(false, "MediaStore 파일 생성 실패")

                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(logContent.toByteArray(Charsets.UTF_8))
                }
                Pair(true, "다운로드 폴더에 저장되었습니다: $fileName")
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val targetFile = File(downloadDir, fileName)
                FileOutputStream(targetFile).use { fos ->
                    fos.write(logContent.toByteArray(Charsets.UTF_8))
                }
                Pair(true, "다운로드 폴더에 저장되었습니다: ${targetFile.absolutePath}")
            }
        } catch (e: Exception) {
            logError("LOG", "다운로드 폴더 저장 실패", e)
            Pair(false, "저장 실패: ${e.localizedMessage}")
        }
    }

    /**
     * G메일, 메시지 등 외부 앱으로 로그 공유 Intent 생성 및 실행
     */
    fun shareLog(context: Context) {
        try {
            val logText = getFullLogText()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "[K 읽기] TTS 진단 및 에러 로그")
                putExtra(Intent.EXTRA_TEXT, logText)
            }
            val chooser = Intent.createChooser(shareIntent, "TTS 로그 공유하기 (Gmail, 메시지 등)")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            logError("LOG", "로그 공유 인텐트 실행 실패", e)
        }
    }

    /**
     * 앱 백그라운드 전환 시 타이머 시작 (일정 시간 미사용 시 로그 자동 삭제)
     */
    fun onAppBackgrounded(context: Context) {
        cancelCleanupTimer()
        cleanupTimer = Timer("TtsLogCleanupTimer", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    clearAllLogs(context)
                }
            }, INACTIVITY_CLEANUP_DELAY_MS)
        }
    }

    /**
     * 앱 포그라운드 복귀 시 타이머 취소
     */
    fun onAppForegrounded() {
        cancelCleanupTimer()
    }

    /**
     * 로그 전체 삭제 및 메모리/파일 해제
     */
    fun clearAllLogs(context: Context) {
        synchronized(logEntries) {
            logEntries.clear()
            _logTextFlow.value = ""
        }
        try {
            val file = File(context.cacheDir, LOG_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
        Log.i(TAG, "미사용 타이머에 의해 TTS 로그가 정리되었습니다.")
    }

    private fun cancelCleanupTimer() {
        cleanupTimer?.cancel()
        cleanupTimer = null
    }
}
