package com.krdondon.read.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "txt_documents")
data class TxtDocument(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val bookmarkCharIndex: Int = 0,
    val bookmarkSentenceIndex: Int = 0,
    val lastReadSentenceIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
