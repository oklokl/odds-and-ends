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
    
    private val _trashFlow = MutableStateFlow<List<RecordingFile>>(emptyList())
    val trashFlow: Flow<List<RecordingFile>> = _trashFlow.asStateFlow()
    
    private val gson = Gson()
    private val metadataFile = File(context.filesDir, "recordings_metadata.json")
    
    init {
        loadMetadata()
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
        val recordingsDir = getRecordingsDirectory()
        val files = recordingsDir.listFiles { file ->
            file.extension.lowercase() in listOf("m4a", "mp3")
        }?.toList() ?: emptyList()
        
        val recordings = files.mapNotNull { file ->
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever.release()
                
                RecordingFile(
                    id = file.nameWithoutExtension,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    duration = duration,
                    fileSize = file.length(),
                    dateCreated = file.lastModified(),
                    category = "미지정"
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }.sortedByDescending { it.dateCreated }
        
        _recordingsFlow.value = recordings
    }
    
    suspend fun saveRecording(tempFile: File, fileName: String, category: String): RecordingFile? = withContext(Dispatchers.IO) {
        try {
            val recordingsDir = getRecordingsDirectory()
            val extension = tempFile.extension
            val finalFile = File(recordingsDir, "$fileName.$extension")
            
            // 파일명 중복 처리
            var counter = 1
            var targetFile = finalFile
            while (targetFile.exists()) {
                targetFile = File(recordingsDir, "${fileName}_$counter.$extension")
                counter++
            }
            
            tempFile.copyTo(targetFile, overwrite = false)
            tempFile.delete()
            
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(targetFile.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
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
            
            loadRecordings()
            recording
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    suspend fun deleteRecording(recording: RecordingFile, moveToTrash: Boolean = true) = withContext(Dispatchers.IO) {
        val file = File(recording.filePath)
        if (file.exists()) {
            if (moveToTrash) {
                val trashDir = getTrashDirectory()
                val trashFile = File(trashDir, file.name)
                file.renameTo(trashFile)
            } else {
                file.delete()
            }
            loadRecordings()
        }
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
                val type = object : TypeToken<List<RecordingFile>>() {}.type
                val recordings: List<RecordingFile> = gson.fromJson(json, type)
                _recordingsFlow.value = recordings
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
}
