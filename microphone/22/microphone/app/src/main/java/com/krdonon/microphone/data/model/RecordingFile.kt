package com.krdonon.microphone.data.model

import java.io.File

data class RecordingFile(
    val id: String,
    val fileName: String,
    val filePath: String,
    val duration: Long, // milliseconds
    val fileSize: Long, // bytes
    val dateCreated: Long, // timestamp
    val category: String = "미지정",
    val isTemporary: Boolean = false
) {
    val file: File
        get() = File(filePath)
    
    val durationFormatted: String
        get() {
            val seconds = (duration / 1000).toInt()
            val minutes = seconds / 60
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            val remainingSeconds = seconds % 60
            
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, remainingMinutes, remainingSeconds)
            } else {
                String.format("%02d:%02d", remainingMinutes, remainingSeconds)
            }
        }
    
    val fileSizeFormatted: String
        get() {
            val kb = fileSize / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1.0) {
                String.format("%.2f MB", mb)
            } else {
                String.format("%.2f KB", kb)
            }
        }
}
