package com.krdondon.read.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinedEditTextTest {

    @Test
    fun testLineCountCalculation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = LinedEditText(context)

        val text = "줄 하나\n줄 둘\n줄 셋\n줄 넷"
        editText.setInitialContent(text)

        assertEquals(4, editText.getTotalLineCount())
    }

    @Test
    fun testSearchNextAndPrevious() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = LinedEditText(context)

        val content = "사과 바나나 사과 포도 사과 멜론"
        editText.setInitialContent(content)

        assertEquals(3, editText.countMatches("사과"))

        // Search next from start (cursor = 0)
        val result1 = editText.searchNext("사과")
        assertTrue(result1.isFound)
        assertEquals(1, result1.matchIndex)
        assertEquals(3, result1.totalMatches)
        assertFalse(result1.didWrap)

        // Search next again
        val result2 = editText.searchNext("사과")
        assertTrue(result2.isFound)
        assertEquals(2, result2.matchIndex)

        // Search next again
        val result3 = editText.searchNext("사과")
        assertTrue(result3.isFound)
        assertEquals(3, result3.matchIndex)

        // Search next should wrap around to first
        val result4 = editText.searchNext("사과")
        assertTrue(result4.isFound)
        assertEquals(1, result4.matchIndex)
        assertTrue(result4.didWrap)

        // Search previous from current
        val resultPrev = editText.searchPrevious("사과")
        assertTrue(resultPrev.isFound)
    }
}
