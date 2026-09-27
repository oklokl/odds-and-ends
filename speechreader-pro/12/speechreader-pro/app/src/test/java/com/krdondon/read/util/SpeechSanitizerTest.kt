package com.krdondon.read.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSanitizerTest {

    @Test
    fun testDividerFiltering() {
        // Pure divider lines should be skipped
        assertTrue(SpeechSanitizer.shouldSkipSentence("----------"))
        assertTrue(SpeechSanitizer.shouldSkipSentence("──────────"))
        assertTrue(SpeechSanitizer.shouldSkipSentence("=========="))
        assertTrue(SpeechSanitizer.shouldSkipSentence("~~~~~~~~~~"))
        assertTrue(SpeechSanitizer.shouldSkipSentence("* * * * *"))
        assertTrue(SpeechSanitizer.shouldSkipSentence("- - - - -"))
        assertTrue(SpeechSanitizer.shouldSkipSentence("---------- 구분선 ──────────"))
        assertTrue(SpeechSanitizer.shouldSkipSentence("[ 구분선 ]"))
        assertTrue(SpeechSanitizer.shouldSkipSentence("---- 절취선 ----"))

        // Section title surrounded by dashes should NOT be skipped, but sanitized cleanly
        assertFalse(SpeechSanitizer.shouldSkipSentence("---------- 제1장 시작 ----------"))
        assertEquals("제1장 시작", SpeechSanitizer.sanitizeForSpeech("---------- 제1장 시작 ----------"))

        // Regular sentence should remain intact
        assertFalse(SpeechSanitizer.shouldSkipSentence("그는 조용히 책을 읽었다."))
        assertEquals("그는 조용히 책을 읽었다.", SpeechSanitizer.sanitizeForSpeech("그는 조용히 책을 읽었다."))
    }
}
