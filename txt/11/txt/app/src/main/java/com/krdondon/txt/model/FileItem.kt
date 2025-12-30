package com.krdondon.txt.model

import android.net.Uri

data class FileItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val modifiedMillis: Long = 0L
)
