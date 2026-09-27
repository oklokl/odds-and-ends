package com.krdondon.read.util

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LargeFileStreamReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testReadChunkWithFileChannel() = runBlocking {
        val file = tempFolder.newFile("sample.txt")
        val content = "1234567890abcdefghijklmnopqrstuvwxyz"
        file.writeText(content)

        // Read chunk starting at offset 10 with length 6
        val chunk = LargeFileStreamReader.readChunkWithFileChannel(file, 10L, 6)
        assertEquals("abcdef", chunk)
    }

    @Test
    fun testStreamLines() = runBlocking {
        val file = tempFolder.newFile("lines.txt")
        val lines = listOf("첫 번째 줄입니다.", "두 번째 줄입니다.", "세 번째 줄입니다.")
        file.writeText(lines.joinToString("\n"))

        val streamed = LargeFileStreamReader.streamLines(file).toList()
        assertEquals(3, streamed.size)
        assertEquals("첫 번째 줄입니다.", streamed[0])
        assertEquals("두 번째 줄입니다.", streamed[1])
        assertEquals("세 번째 줄입니다.", streamed[2])
    }

    @Test
    fun testStreamChunks() = runBlocking {
        val file = tempFolder.newFile("chunks.txt")
        val text = "A".repeat(100) + "B".repeat(100)
        file.writeText(text)

        val chunks = LargeFileStreamReader.streamChunks(file, chunkSize = 50).toList()
        assertTrue(chunks.size >= 4)
        val combined = chunks.joinToString("")
        assertEquals(text, combined)
    }
}
