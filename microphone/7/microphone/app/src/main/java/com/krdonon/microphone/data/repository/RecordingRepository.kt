package com.krdonon.microphone.data.repository

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

    private val trashMetadataFile = File(context.filesDir, "trash_metadata.json")

    private val _trashFlow = MutableStateFlow<List<RecordingFile>>(emptyList())
    val trashFlow: Flow<List<RecordingFile>> = _trashFlow.asStateFlow()

    private val gson = Gson()
    private val metadataFile = File(context.filesDir, "recordings_metadata.json")

    init {
        loadMetadata()
        loadTrashMetadata()   // ← 추가
    }

    fun getRecordingsDirectory(storagePath: String = ""): File {
        val baseDir = if (storagePath.isEmpty()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        } else {
            File(storagePath)
        }

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
        }.sortedByDescending { it.dateCreated }

        _recordingsFlow.value = recordings
        saveMetadata()
    }


    suspend fun saveRecording(
        tempFile: File,
        fileName: String,
        category: String
    ): RecordingFile? = withContext(Dispatchers.IO) {
        try {
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

            // 현재 목록에 반영
            val current = _recordingsFlow.value.toMutableList()
            current.add(0, recording)
            _recordingsFlow.value = current.sortedByDescending { it.dateCreated }
            saveMetadata()

            // 필요하면 디스크와 한 번 더 동기화
            // loadRecordings()

            recording
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


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
                if (!trashDir.exists()) trashDir.mkdirs()

                val originalName = file.nameWithoutExtension
                val ext = file.extension

                // 휴지통 안에서 파일명 중복 처리
                var trashFile = File(trashDir, file.name)
                var counter = 1
                while (trashFile.exists()) {
                    trashFile = File(trashDir, "${originalName}_$counter.$ext")
                    counter++
                }

                // 다른 저장소라 renameTo 불가 → copyTo + delete
                file.copyTo(trashFile, overwrite = false)
                val deletedOriginal = file.delete()
                android.util.Log.d(
                    "RecordingRepository",
                    "Move to trash: copy OK, original deleted = $deletedOriginal, trashPath=${trashFile.absolutePath}"
                )

                // 메인 리스트에서 제거
                val mainList = _recordingsFlow.value.toMutableList()
                mainList.removeAll { it.id == recording.id }
                _recordingsFlow.value = mainList.sortedByDescending { it.dateCreated }

                // 휴지통 리스트에 추가
                val trashList = _trashFlow.value.toMutableList()
                trashList.removeAll { it.id == recording.id }
                trashList.add(
                    recording.copy(
                        filePath = trashFile.absolutePath,
                        fileName = trashFile.name
                    )
                )
                _trashFlow.value = trashList.sortedByDescending { it.dateCreated }

                saveMetadata()
                saveTrashMetadata()
            } else {
                // --- 영구 삭제 ---
                val deleted = file.delete()
                android.util.Log.d(
                    "RecordingRepository",
                    "File permanently deleted: $deleted, path=${file.absolutePath}"
                )

                // 휴지통에서 삭제할 때 + 메인 목록에서 바로 삭제할 때 둘 다 커버
                val trashList = _trashFlow.value.toMutableList()
                trashList.removeAll { it.id == recording.id }
                _trashFlow.value = trashList.sortedByDescending { it.dateCreated }

                val mainList = _recordingsFlow.value.toMutableList()
                mainList.removeAll { it.id == recording.id }
                _recordingsFlow.value = mainList.sortedByDescending { it.dateCreated }

                saveTrashMetadata()
                saveMetadata()
            }
        } catch (e: Exception) {
            android.util.Log.e("RecordingRepository", "Delete failed", e)
            e.printStackTrace()
        }
    }



    // 기존 녹음 파일들에서 카테고리 이름을 일괄 변경
    suspend fun updateCategoryName(oldName: String, newName: String) =
        withContext(Dispatchers.IO) {
            val updated = _recordingsFlow.value.map { recording ->
                if (recording.category == oldName) {
                    recording.copy(category = newName)
                } else {
                    recording
                }
            }
            _recordingsFlow.value = updated
            saveMetadata()
        }


    suspend fun renameRecording(recording: RecordingFile, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val oldFile = File(recording.filePath)
            val extension = oldFile.extension
            val newFile = File(oldFile.parent, "$newName.$extension")

            if (oldFile.renameTo(newFile)) {
                loadRecordings()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun generateFileName(): String {
        val dateFormat = SimpleDateFormat("yyMMdd_HHmmss", Locale.getDefault())
        return "음성 ${dateFormat.format(Date())}"
    }

    private fun loadMetadata() {
        try {
            if (metadataFile.exists()) {
                val json = metadataFile.readText()
                if (json.isNotBlank()) {
                    val type = object : TypeToken<List<RecordingFile>>() {}.type
                    val recordings: List<RecordingFile> = gson.fromJson(json, type)
                    _recordingsFlow.value = recordings
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

                // 실제 파일이 존재하는 것만 남김
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

    suspend fun restoreFromTrash(recording: RecordingFile) = withContext(Dispatchers.IO) {
        try {
            val trashFile = File(recording.filePath)
            if (!trashFile.exists()) return@withContext

            val recordingsDir = getRecordingsDirectory()
            val restoredFile = File(recordingsDir, trashFile.name)

            if (trashFile.renameTo(restoredFile)) {
                // 휴지통 리스트에서 제거
                val trashList = _trashFlow.value.toMutableList()
                trashList.removeAll { it.id == recording.id }
                _trashFlow.value = trashList.sortedByDescending { it.dateCreated }

                // 메인 리스트에 추가
                val mainList = _recordingsFlow.value.toMutableList()
                mainList.add(
                    recording.copy(
                        filePath = restoredFile.absolutePath,
                        fileName = restoredFile.name
                    )
                )
                _recordingsFlow.value = mainList.sortedByDescending { it.dateCreated }

                saveMetadata()
                saveTrashMetadata()
            }
        } catch (e: Exception) {
            android.util.Log.e("RecordingRepository", "Restore failed", e)
            e.printStackTrace()
        }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        try {
            _trashFlow.value.forEach { rec ->
                val file = File(rec.filePath)
                if (file.exists()) file.delete()
            }
            _trashFlow.value = emptyList()
            saveTrashMetadata()
        } catch (e: Exception) {
            android.util.Log.e("RecordingRepository", "Empty trash failed", e)
            e.printStackTrace()
        }
    }



}