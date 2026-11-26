package com.krdondon.Replacestring

import org.junit.Assert.assertEquals
import org.junit.Test

class WordReplacerLogicTest {

    @Test
    fun replaceTextAndCount_replacesAllOccurrencesAndCountsMatches() {
        val result = replaceTextAndCount(
            original = "사과가 맛있습니다. 사과를 또 샀습니다.",
            target = "사과",
            replacement = "배"
        )

        assertEquals("배가 맛있습니다. 배를 또 샀습니다.", result.text)
        assertEquals(2, result.matchCount)
    }

    @Test
    fun replaceTextAndCount_emptyTarget_returnsOriginalWithZeroMatches() {
        val result = replaceTextAndCount(
            original = "hello",
            target = "",
            replacement = "X"
        )

        assertEquals("hello", result.text)
        assertEquals(0, result.matchCount)
    }

    @Test
    fun replaceTextAndCount_emptyOriginal_returnsEmptyWithZeroMatches() {
        val result = replaceTextAndCount(
            original = "",
            target = "hello",
            replacement = "world"
        )

        assertEquals("", result.text)
        assertEquals(0, result.matchCount)
    }

    @Test
    fun replaceTextAndCount_noMatch_returnsOriginalWithZeroMatches() {
        val result = replaceTextAndCount(
            original = "안드로이드 메모장",
            target = "아이폰",
            replacement = "갤럭시"
        )

        assertEquals("안드로이드 메모장", result.text)
        assertEquals(0, result.matchCount)
    }

    @Test
    fun replaceTextAndCount_overlappingOccurrences_matchesKotlinReplaceBehavior() {
        val result = replaceTextAndCount(
            original = "aaaa",
            target = "aa",
            replacement = "b"
        )

        assertEquals("bb", result.text)
        assertEquals(2, result.matchCount)
    }

    @Test
    fun replaceTextAndCount_replacementContainingTarget_doesNotInfiniteLoop() {
        val result = replaceTextAndCount(
            original = "foo bar foo",
            target = "foo",
            replacement = "foobar"
        )

        assertEquals("foobar bar foobar", result.text)
        assertEquals(2, result.matchCount)
    }
}
