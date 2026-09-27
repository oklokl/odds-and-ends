package com.krdondon.read.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.krdondon.read.data.model.TxtDocument
import com.krdondon.read.data.repository.DocumentRepository
import com.krdondon.read.service.TtsEngineType
import com.krdondon.read.service.TtsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * 읽기 기록 및 설정 백업/복원 매니저:
 * - 앱 업데이트(11 -> 12 등)나 재설치 시에도 읽던 문장 위치와 책갈피를 복구
 * - 파일이 무한히 쌓여 핸드폰 용량을 차지하지 않도록 단일 최신 파일(KReader_Reading_Backup.json)로 덮어쓰기 관리
 * - Google Play Scoped Storage 규정 100% 준수 (위험 권한 없이 MediaStore 활용)
 */
object ReadingProgressBackupManager {

    private const val BACKUP_FILE_NAME = "KReader_Reading_Backup.json"
    private const val SNAPSHOT_FILE_NAME = "reading_progress_snapshot.json"

    /**
     * 현재 상태를 JSON 문자열로 직렬화
     */
    fun createBackupJson(
        docs: List<TxtDocument>,
        speechRate: Float,
        speechPitch: Float,
        ttsEngine: TtsEngineType,
        isLoopEnabled: Boolean
    ): String {
        val root = JSONObject()
        root.put("app", "KReader")
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())
        root.put("speechRate", speechRate.toDouble())
        root.put("speechPitch", speechPitch.toDouble())
        root.put("ttsEngine", ttsEngine.name)
        root.put("isLoopEnabled", isLoopEnabled)

        val docArray = JSONArray()
        for (doc in docs) {
            val docObj = JSONObject()
            docObj.put("title", doc.title)
            docObj.put("lastReadSentenceIndex", doc.lastReadSentenceIndex)
            docObj.put("bookmarkSentenceIndex", doc.bookmarkSentenceIndex)
            docObj.put("bookmarkCharIndex", doc.bookmarkCharIndex)
            docObj.put("updatedAt", doc.updatedAt)
            docArray.put(docObj)
        }
        root.put("documents", docArray)

        return root.toString(2)
    }

    /**
     * 1. [기록 저장]: 다운로드 폴더에 최신 백업 파일 저장
     * 파일이 계속 쌓이지 않도록 기존 백업 파일을 덮어쓰거나 정리합니다.
     */
    suspend fun saveBackupToDownloads(
        context: Context,
        docs: List<TxtDocument>,
        ttsState: TtsUiState
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val jsonString = createBackupJson(
                docs = docs,
                speechRate = ttsState.speechRate,
                speechPitch = ttsState.speechPitch,
                ttsEngine = ttsState.engineType,
                isLoopEnabled = ttsState.isLoopEnabled
            )

            // 앱 내부 영구 스냅샷 동시 갱신 (앱 업데이트 대비)
            saveInternalSnapshot(context, jsonString)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 다운로드 폴더에 기존 동일 파일이 있을 경우 삭제하여 누적 방지
                cleanupOldDownloadsBackup(context)

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext Pair(false, "백업 파일 생성 실패 (저장소 접근 오류)")

                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(jsonString.toByteArray(Charsets.UTF_8))
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val targetFile = File(downloadDir, BACKUP_FILE_NAME)
                FileOutputStream(targetFile).use { fos ->
                    fos.write(jsonString.toByteArray(Charsets.UTF_8))
                }
            }

            Pair(true, "다운로드 폴더에 읽기 기록이 저장되었습니다.\n(파일명: $BACKUP_FILE_NAME)")
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, "백업 저장 중 오류: ${e.localizedMessage}")
        }
    }

    /**
     * 다운로드 폴더의 기존 백업 파일을 정리하여 중복 누적 방지
     */
    private fun cleanupOldDownloadsBackup(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'KReader_Reading_Backup%'"
                val selectionArgs = arrayOf(BACKUP_FILE_NAME)

                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val deleteUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        try {
                            context.contentResolver.delete(deleteUri, null, null)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 2. 내부 영구 스냅샷 저장 (기기 전용 공간, 외부 노출 없이 단 1개 파일로 덮어쓰기)
     */
    fun saveInternalSnapshot(context: Context, jsonString: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, SNAPSHOT_FILE_NAME)
            file.writeText(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 3. [기록 불러오기]: Uri(SAF 파일 선택기)를 통해 백업 복원
     */
    suspend fun restoreFromUri(
        context: Context,
        uri: Uri,
        docs: List<TxtDocument>,
        repository: DocumentRepository,
        onSettingsRestored: (rate: Float, pitch: Float, engine: TtsEngineType, loop: Boolean) -> Unit
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                InputStreamReader(inputStream, Charsets.UTF_8).readText()
            } ?: return@withContext Pair(0, "파일을 읽을 수 없습니다.")

            val restoredCount = restoreFromJsonString(jsonString, docs, repository, onSettingsRestored)
            if (restoredCount > 0) {
                // 내부 스냅샷도 최신본으로 동기화
                saveInternalSnapshot(context, jsonString)
                Pair(restoredCount, "${restoredCount}개 문서의 읽기 위치 및 설정이 복원되었습니다.")
            } else {
                Pair(0, "일치하는 문서 제목을 찾지 못했거나 복원할 데이터가 없습니다.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(0, "복원 중 오류 발생: ${e.localizedMessage}")
        }
    }

    /**
     * 4. 다운로드 폴더에 저장된 최신 백업 파일이 있으면 원클릭 자동 복원
     */
    suspend fun restoreFromLatestDownloadsOrSnapshot(
        context: Context,
        docs: List<TxtDocument>,
        repository: DocumentRepository,
        onSettingsRestored: (rate: Float, pitch: Float, engine: TtsEngineType, loop: Boolean) -> Unit
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        // 1) 내부 영구 스냅샷 확인
        val internalDir = context.getExternalFilesDir(null) ?: context.filesDir
        val snapshotFile = File(internalDir, SNAPSHOT_FILE_NAME)
        if (snapshotFile.exists()) {
            try {
                val json = snapshotFile.readText()
                val count = restoreFromJsonString(json, docs, repository, onSettingsRestored)
                if (count > 0) {
                    return@withContext Pair(count, "최신 읽기 기록(${count}개 문서)이 복원되었습니다.")
                }
            } catch (_: Exception) {}
        }

        // 2) 다운로드 폴더 직접 확인 (Android 9 이하 등)
        @Suppress("DEPRECATION")
        val downloadFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_FILE_NAME)
        if (downloadFile.exists()) {
            try {
                val json = downloadFile.readText()
                val count = restoreFromJsonString(json, docs, repository, onSettingsRestored)
                if (count > 0) {
                    return@withContext Pair(count, "다운로드 폴더의 백업 파일에서 ${count}개 문서가 복원되었습니다.")
                }
            } catch (_: Exception) {}
        }

        Pair(0, "저장된 백업 파일이 없습니다. [기록 불러오기]를 눌러 백업 파일을 직접 선택해주세요.")
    }

    /**
     * JSON 문자열을 파싱하여 DB에 문서 위치 및 설정 복원
     */
    suspend fun restoreFromJsonString(
        jsonString: String,
        docs: List<TxtDocument>,
        repository: DocumentRepository,
        onSettingsRestored: (rate: Float, pitch: Float, engine: TtsEngineType, loop: Boolean) -> Unit
    ): Int {
        val root = JSONObject(jsonString)

        // 설정 복원
        val rate = root.optDouble("speechRate", 1.0).toFloat()
        val pitch = root.optDouble("speechPitch", 1.0).toFloat()
        val engineStr = root.optString("ttsEngine", TtsEngineType.SYSTEM.name)
        val engine = try { TtsEngineType.valueOf(engineStr) } catch (_: Exception) { TtsEngineType.SYSTEM }
        val loop = root.optBoolean("isLoopEnabled", true)
        onSettingsRestored(rate, pitch, engine, loop)

        // 문서 읽기 위치 복원
        val docArray = root.optJSONArray("documents") ?: return 0
        var restoredCount = 0

        for (i in 0 until docArray.length()) {
            val docObj = docArray.getJSONObject(i)
            val title = docObj.optString("title", "")
            if (title.isBlank()) continue

            val lastReadSentenceIndex = docObj.optInt("lastReadSentenceIndex", 0)
            val bookmarkSentenceIndex = docObj.optInt("bookmarkSentenceIndex", -1)
            val bookmarkCharIndex = docObj.optInt("bookmarkCharIndex", 0)

            // 제목이 일치하는 문서 탐색
            val matchedDoc = docs.find { it.title == title }
            if (matchedDoc != null) {
                repository.updateReadingProgress(
                    id = matchedDoc.id,
                    sentenceIndex = lastReadSentenceIndex,
                    bookmarkIndex = bookmarkSentenceIndex,
                    bookmarkChar = bookmarkCharIndex
                )
                restoredCount++
            }
        }

        return restoredCount
    }

    /**
     * 앱 시작 시 읽기 위치가 0인 문서가 있고 백업 스냅샷이 있다면 자동 복구 수행
     */
    suspend fun autoRestoreOnAppStart(
        context: Context,
        docs: List<TxtDocument>,
        repository: DocumentRepository,
        onSettingsRestored: (rate: Float, pitch: Float, engine: TtsEngineType, loop: Boolean) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val snapshotFile = File(dir, SNAPSHOT_FILE_NAME)
        if (!snapshotFile.exists()) return@withContext 0

        try {
            val json = snapshotFile.readText()
            // 현재 문서들 중 아직 읽은 기록이 없는 문서(lastReadSentenceIndex == 0)만 복구
            val unreadDocs = docs.filter { it.lastReadSentenceIndex == 0 && it.bookmarkSentenceIndex <= 0 }
            if (unreadDocs.isNotEmpty()) {
                restoreFromJsonString(json, unreadDocs, repository, onSettingsRestored)
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }
}
