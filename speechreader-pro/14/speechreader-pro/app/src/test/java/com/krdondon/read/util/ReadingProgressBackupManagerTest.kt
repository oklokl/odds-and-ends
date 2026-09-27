package com.krdondon.read.util

import com.krdondon.read.data.model.TxtDocument
import com.krdondon.read.service.TtsEngineType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadingProgressBackupManagerTest {

    @Test
    fun testCreateBackupJsonAndParse() {
        val docs = listOf(
            TxtDocument(
                id = 1,
                title = "테스트문서1",
                content = "내용1",
                bookmarkCharIndex = 120,
                bookmarkSentenceIndex = 15,
                lastReadSentenceIndex = 25
            ),
            TxtDocument(
                id = 2,
                title = "테스트문서2",
                content = "내용2",
                bookmarkCharIndex = 0,
                bookmarkSentenceIndex = -1,
                lastReadSentenceIndex = 100
            )
        )

        val jsonString = ReadingProgressBackupManager.createBackupJson(
            docs = docs,
            speechRate = 1.2f,
            speechPitch = 1.1f,
            ttsEngine = TtsEngineType.GOOGLE,
            isLoopEnabled = true
        )

        assertTrue(jsonString.isNotBlank())
        val json = JSONObject(jsonString)
        assertEquals("KReader", json.getString("app"))
        assertEquals(1.2, json.getDouble("speechRate"), 0.01)
        assertEquals(1.1, json.getDouble("speechPitch"), 0.01)
        assertEquals("GOOGLE", json.getString("ttsEngine"))
        assertTrue(json.getBoolean("isLoopEnabled"))

        val array = json.getJSONArray("documents")
        assertEquals(2, array.length())

        val doc1 = array.getJSONObject(0)
        assertEquals("테스트문서1", doc1.getString("title"))
        assertEquals(15, doc1.getInt("bookmarkSentenceIndex"))
        assertEquals(25, doc1.getInt("lastReadSentenceIndex"))

        val doc2 = array.getJSONObject(1)
        assertEquals("테스트문서2", doc2.getString("title"))
        assertEquals(-1, doc2.getInt("bookmarkSentenceIndex"))
        assertEquals(100, doc2.getInt("lastReadSentenceIndex"))
    }
}
