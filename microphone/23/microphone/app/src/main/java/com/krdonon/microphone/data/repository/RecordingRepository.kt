package com.krdonon.microphone.data.repository

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.krdonon.microphone.data.model.RecordingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingRepository(private val context: Context) {

    private val _recordingsFlow = MutableStateFlow<List<RecordingFile>>(emptyList())
    val recordingsFlow: Flow<List<RecordingFile>> = _recordingsFlow.asStateFlow()

    private val gson = Gson()
    private val metadataFile = File(context.filesDir, "recordings_metadata.json")

    private val _trashFlow = MutableStateFlow<List<RecordingFile>>(emptyList())
    val trashFlow: Flow<List<RecordingFile>> = _trashFlow.asStateFlow()

    private val trashMetadataFile = File(context.filesDir, "trash_metadata.json")

    init {
        loadMetadata()
        loadTrashMetadata()
    }

    // 파일 상단 import 쪽은 그대로 두고, 함수만 교체하시면 됩니다.
    fun getRecordingsDirectory(storagePath: String = ""): File {
        // 1) 기본은 앱 전용 외부 저장소 (/Android/data/.../files/Music/)
        val baseDir = if (storagePath.isEmpty()) {
            // 외부 저장소가 없을 때를 대비해 내부 저장소로 fallback
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        } else {
            File(storagePath)
        }

        // 2) 그 안에 우리 앱용 하위 폴더 생성
        val recordingsDir = File(baseDir, "krdondon_mic")
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
        return recordingsDir
    }



    fun getTrashDirectory(): File {
        val trashDir = File(context.filesDir, "trash")
        if (!trashDir.exists()) {
            trashDir.mkdirs()
        }
        return trashDir
    }

    // ─────────────────────────────────────────────
    //  녹음 파일 스캔 (폴더 → 메모리)
    // ─────────────────────────────────────────────
    suspend fun loadRecordings() = withContext(Dispatchers.IO) {
        // 이미 메모리에 올라와 있는 녹음 목록에서 id -> category 맵을 만든다
        val previousCategories = _recordingsFlow.value
            .associateBy { it.id }
            .mapValues { it.value.category }

        val recordingsDir = getRecordingsDirectory()
        val files = recordingsDir.listFiles { file ->
            file.extension.lowercase() in listOf("m4a", "mp3")
        }?.toList() ?: emptyList()

        val recordings = files.mapNotNull { file ->
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                retriever.release()

                val id = file.nameWithoutExtension
                val category = previousCategories[id] ?: "미지정"

                RecordingFile(
                    id = id,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    duration = duration,
                    fileSize = file.length(),
                    dateCreated = file.lastModified(),
                    category = category
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
            // ★ 여기서 id 중복 제거
            .distinctBy { it.id }
            .sortedByDescending { it.dateCreated }

        _recordingsFlow.value = recordings
        saveMetadata()
    }

    // ─────────────────────────────────────────────
    //  저장(분할 파일 포함)
    // ─────────────────────────────────────────────
    suspend fun saveRecording(
        tempFile: File,
        fileName: String,
        category: String
    ): RecordingFile? = withContext(Dispatchers.IO) {
        try {
            // 1) 같은 녹음에서 나온 분할 캐시 파일들 찾기
            val cacheDir = context.cacheDir
            val extension = tempFile.extension
            val baseTempName = tempFile.nameWithoutExtension      // 예: temp_recording_1700

            val partFiles = mutableListOf<File>()
            partFiles.add(tempFile)                               // 1번 파트

            // 2번 이후 파트: temp_recording_1700_2, _3, ...
            val prefix = "${baseTempName}_"

            val extraParts = cacheDir
                .listFiles { f ->
                    f.extension.equals(extension, ignoreCase = true) &&
                            f.nameWithoutExtension.startsWith(prefix)
                }
                ?.sortedBy { f ->
                    val suffix = f.nameWithoutExtension.removePrefix(prefix)
                    suffix.toIntOrNull() ?: Int.MAX_VALUE
                }
                ?: emptyList()

            partFiles.addAll(extraParts)

            // 2) 각 파트를 순서대로 저장
            var lastRecording: RecordingFile? = null
            var partIndex = 1

            for (part in partFiles) {
                val partName =
                    if (partIndex == 1) fileName          // 예: "음성 1"
                    else "${fileName}_$partIndex"         // 예: "음성 1_2", "음성 1_3"

                lastRecording = saveSinglePart(part, partName, category)
                partIndex++
            }

            lastRecording
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ─────────────────────────────────────────────
    //  내보내기: 앱 전용 폴더 → 공개 Music 폴더
    // ─────────────────────────────────────────────
    suspend fun exportRecordingToMusic(recording: RecordingFile): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val sourceFile = File(recording.filePath)
                if (!sourceFile.exists()) {
                    return@withContext false
                }

                val fileName = sourceFile.name
                val extension = sourceFile.extension.lowercase(Locale.getDefault())
                val mimeType = when (extension) {
                    "m4a" -> "audio/mp4"
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    else -> "audio/*"
                }

                // Android 10(Q) 이상: MediaStore 사용
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                        // 공개 Music/krdondon_mic 폴더
                        put(
                            MediaStore.Audio.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MUSIC + "/krdondon_mic"
                        )
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }

                    val resolver = context.contentResolver
                    val collection = MediaStore.Audio.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )

                    val uri = resolver.insert(collection, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            sourceFile.inputStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                        // 복사 완료 표시
                        values.clear()
                        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        true
                    } else {
                        false
                    }
                } else {
                    // Android 9(P) 이하: 기존 방식으로 Music 폴더에 복사
                    val musicDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val outDir = File(musicDir, "krdondon_mic")
                    if (!outDir.exists()) {
                        outDir.mkdirs()
                    }
                    val targetFile = File(outDir, fileName)
                    sourceFile.copyTo(targetFile, overwrite = true)
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }


    private fun saveSinglePart(
        tempFile: File,
        fileName: String,
        category: String
    ): RecordingFile? {
        val recordingsDir = getRecordingsDirectory()
        val extension = tempFile.extension
        var targetFile = File(recordingsDir, "$fileName.$extension")

        // 파일명 중복 처리
        var counter = 1
        while (targetFile.exists()) {
            targetFile = File(recordingsDir, "${fileName}_$counter.$extension")
            counter++
        }

        tempFile.copyTo(targetFile, overwrite = false)
        tempFile.delete()

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(targetFile.absolutePath)
        val duration = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        retriever.release()

        val recording = RecordingFile(
            id = targetFile.nameWithoutExtension,
            fileName = targetFile.name,
            filePath = targetFile.absolutePath,
            duration = duration,
            fileSize = targetFile.length(),
            dateCreated = System.currentTimeMillis(),
            category = category
        )

        // ★ 현재 목록에 반영 (id 중복 제거)
        val current = _recordingsFlow.value
            .filterNot { it.id == recording.id }
            .toMutableList()
        current.add(0, recording)
        _recordingsFlow.value = current
            .distinctBy { it.id }
            .sortedByDescending { it.dateCreated }
        saveMetadata()

        return recording
    }

    // ─────────────────────────────────────────────
    //  삭제 / 휴지통 / 복원
    // ─────────────────────────────────────────────
    suspend fun deleteRecording(
        recording: RecordingFile,
        moveToTrash: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            val file = File(recording.filePath)
            if (!file.exists()) return@withContext

            if (moveToTrash) {
                // --- 휴지통으로 이동 (외부 -> 내부: rename 대신 copy + delete) ---
                val trashDir = getTrashDirectory()
                val trashFile = File(trashDir, file.name)

                file.copyTo(trashFile, overwrite = true)
                val deleted = file.delete()

                if (deleted) {
                    // recording 의 filePath 를 휴지통 경로로 바꾼 버전
                    val trashRecording = recording.copy(filePath = trashFile.absolutePath)

                    val currentTrash = _trashFlow.value.toMutableList()
                    currentTrash.add(0, trashRecording)
                    _trashFlow.value = currentTrash

                    // 메인 목록에서 제거
                    val current = _recordingsFlow.value.toMutableList()
                    current.removeAll { it.filePath == recording.filePath }
                    _recordingsFlow.value = current.sortedByDescending { it.dateCreated }

                    saveTrashMetadata()
                    saveMetadata()
                }
            } else {
                // --- 완전 삭제 ---
                val deleted = file.delete()
                if (deleted) {
                    val current = _recordingsFlow.value.toMutableList()
                    current.removeAll { it.filePath == recording.filePath }
                    _recordingsFlow.value = current.sortedByDescending { it.dateCreated }

                    val currentTrash = _trashFlow.value.toMutableList()
                    currentTrash.removeAll { it.filePath == recording.filePath }
                    _trashFlow.value = currentTrash
                    
                    saveTrashMetadata()
                    saveMetadata()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        try {
            val trashDir = getTrashDirectory()
            val trashFiles = trashDir.listFiles() ?: emptyArray()

            trashFiles.forEach { it.delete() }

            _trashFlow.value = emptyList()
            saveTrashMetadata()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun restoreFromTrash(recording: RecordingFile) = withContext(Dispatchers.IO) {
        try {
            // 1) 휴지통 안에 있는 파일
            val trashFile = File(recording.filePath)
            if (!trashFile.exists()) {
                android.util.Log.w("RecordingRepository", "Trash file not found: ${recording.filePath}")
                return@withContext
            }

            // 2) 원래 녹음 폴더
            val recordingsDir = getRecordingsDirectory()
            var targetFile = File(recordingsDir, trashFile.name)

            // 3) 이미 같은 이름이 있으면 이름 뒤에 _1, _2 붙여서 저장
            var counter = 1
            while (targetFile.exists()) {
                val nameWithoutExt = trashFile.nameWithoutExtension
                val ext = trashFile.extension
                targetFile = File(recordingsDir, "${nameWithoutExt}_$counter.$ext")
                counter++
            }

            // 4) 휴지통 → 원래 폴더로 파일 복사
            trashFile.copyTo(targetFile, overwrite = true)
            trashFile.delete()

            // 5) 메모리 상 목록 업데이트
            val restoredRecording = recording.copy(
                filePath = targetFile.absolutePath,
                dateCreated = targetFile.lastModified()
            )

            // 휴지통 목록에서 제거
            val newTrashList = _trashFlow.value.toMutableList()
            newTrashList.removeAll { it.filePath == recording.filePath }
            _trashFlow.value = newTrashList

            // 메인 목록에 추가
            val mainList = _recordingsFlow.value.toMutableList()
            mainList.add(0, restoredRecording)
            _recordingsFlow.value = mainList.sortedByDescending { it.dateCreated }

            // 6) 메타데이터 저장
            saveTrashMetadata()
            saveMetadata()
        } catch (e: Exception) {
            android.util.Log.e("RecordingRepository", "Restore from trash failed", e)
            e.printStackTrace()
        }
    }

    // ─────────────────────────────────────────────
    //  이름/카테고리 변경
    // ─────────────────────────────────────────────
    suspend fun renameRecording(recording: RecordingFile, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val oldFile = File(recording.filePath)
                if (!oldFile.exists()) return@withContext false

                val extension = oldFile.extension
                val newFile = File(oldFile.parent, "$newName.$extension")

                if (newFile.exists()) return@withContext false

                val renamed = oldFile.renameTo(newFile)
                if (renamed) {
                    val updatedRecording = recording.copy(
                        id = newName,
                        fileName = newFile.name,
                        filePath = newFile.absolutePath
                    )

                    val current = _recordingsFlow.value.toMutableList()
                    val index = current.indexOfFirst { it.filePath == recording.filePath }
                    if (index != -1) {
                        current[index] = updatedRecording
                        _recordingsFlow.value = current.sortedByDescending { it.dateCreated }
                        saveMetadata()
                    }
                }
                renamed
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    suspend fun updateRecordingCategory(recordingId: String, newCategory: String) =
        withContext(Dispatchers.IO) {
            try {
                val updatedList = _recordingsFlow.value.map { recording ->
                    if (recording.id == recordingId) {
                        recording.copy(category = newCategory)
                    } else {
                        recording
                    }
                }
                _recordingsFlow.value = updatedList
                saveMetadata()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    suspend fun updateCategoryName(oldCategoryName: String, newName: String) =
        withContext(Dispatchers.IO) {
            try {
                val updatedList = _recordingsFlow.value.map { recording ->
                    if (recording.category == oldCategoryName) {
                        recording.copy(category = newName)
                    } else {
                        recording
                    }
                }
                _recordingsFlow.value = updatedList
                saveMetadata()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    fun generateFileName(): String {
        val dateFormat = SimpleDateFormat("yyMMdd_HHmmss", Locale.getDefault())
        return "음성 ${dateFormat.format(Date())}"
    }

    // ─────────────────────────────────────────────
    //  메타데이터 (JSON)
    // ─────────────────────────────────────────────
    private fun loadMetadata() {
        try {
            if (metadataFile.exists()) {
                val json = metadataFile.readText()
                if (json.isNotBlank()) {
                    val type = object : TypeToken<List<RecordingFile>>() {}.type
                    val recordings: List<RecordingFile> = gson.fromJson(json, type)
                    _recordingsFlow.value = recordings
                        .distinctBy { it.id }
                        .sortedByDescending { it.dateCreated }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveMetadata() {
        try {
            val json = gson.toJson(_recordingsFlow.value)
            metadataFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadTrashMetadata() {
        try {
            if (trashMetadataFile.exists()) {
                val json = trashMetadataFile.readText()
                val type = object : TypeToken<List<RecordingFile>>() {}.type
                val recordings: List<RecordingFile> = gson.fromJson(json, type)

                // 실제 파일이 존재하는 데이터만 유지
                _trashFlow.value = recordings.filter { File(it.filePath).exists() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveTrashMetadata() {
        try {
            val json = gson.toJson(_trashFlow.value)
            trashMetadataFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
