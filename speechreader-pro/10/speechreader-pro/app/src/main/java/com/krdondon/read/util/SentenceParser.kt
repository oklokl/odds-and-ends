package com.krdondon.read.util

data class SentenceChunk(
    val index: Int,
    val text: String,
    val startCharOffset: Int,
    val endCharOffset: Int
)

object SentenceParser {
    fun parseSentences(content: String): List<SentenceChunk> {
        if (content.isBlank()) return emptyList()

        val list = mutableListOf<SentenceChunk>()
        var chunkIndex = 0
        var i = 0
        val length = content.length

        while (i < length) {
            // skip leading whitespace
            while (i < length && content[i].isWhitespace()) {
                i++
            }
            if (i >= length) break

            val start = i
            // find boundary: newline, or punctuation followed by whitespace/newline, or end of string
            while (i < length) {
                val c = content[i]
                if (c == '\n') {
                    i++
                    break
                }
                if (c == '.' || c == '?' || c == '!' || c == '…') {
                    val next = i + 1
                    if (next >= length || content[next].isWhitespace()) {
                        i = next
                        break
                    }
                }
                i++
            }

            val end = i
            val rawText = content.substring(start, end).trim()
            if (rawText.isNotEmpty()) {
                list.add(
                    SentenceChunk(
                        index = chunkIndex++,
                        text = rawText,
                        startCharOffset = start,
                        endCharOffset = end
                    )
                )
            }
        }
        return list
    }

    fun findSentenceIndexForCharOffset(sentences: List<SentenceChunk>, charOffset: Int): Int {
        if (sentences.isEmpty()) return 0
        val found = sentences.indexOfFirst { charOffset in it.startCharOffset..it.endCharOffset }
        return if (found != -1) found else {
            val before = sentences.indexOfLast { it.endCharOffset <= charOffset }
            if (before != -1) before else 0
        }
    }
}
