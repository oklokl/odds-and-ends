package com.krdondon.read.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.krdondon.read.data.model.TxtDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface TxtDocumentDao {
    @Query("SELECT * FROM txt_documents ORDER BY updatedAt DESC")
    fun getAllDocuments(): Flow<List<TxtDocument>>

    @Query("SELECT * FROM txt_documents WHERE id = :id")
    fun getDocumentById(id: Long): Flow<TxtDocument?>

    @Query("SELECT * FROM txt_documents WHERE id = :id")
    suspend fun getDocumentByIdOnce(id: Long): TxtDocument?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: TxtDocument): Long

    @Update
    suspend fun updateDocument(document: TxtDocument)

    @Query("UPDATE txt_documents SET bookmarkCharIndex = :charIndex, bookmarkSentenceIndex = :sentenceIndex, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBookmark(id: Long, charIndex: Int, sentenceIndex: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE txt_documents SET lastReadSentenceIndex = :sentenceIndex, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateLastReadSentence(id: Long, sentenceIndex: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM txt_documents WHERE id = :id")
    suspend fun deleteDocumentById(id: Long)
}
