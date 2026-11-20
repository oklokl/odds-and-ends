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
    fun testEmptyLinesNumbering() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = LinedEditText(context)

        editText.setInitialContent("\n\nHello\nWorld")

        assertEquals(4, editText.getTotalLineCount())
        assertEquals(1, editText.findPhysicalLineNumber(0))
        assertEquals(2, editText.findPhysicalLineNumber(1))
        assertEquals(3, editText.findPhysicalLineNumber(2))
        assertEquals(3, editText.findPhysicalLineNumber(5))
        assertEquals(4, editText.findPhysicalLineNumber(8))
    }

    @Test
    fun testIncrementalEnterAndEditUpdates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = LinedEditText(context)

        editText.setInitialContent("abcd")
        assertEquals(1, editText.getTotalLineCount())

        // Insert Enter in the middle: "ab\ncd"
        editText.editableText.insert(2, "\n")
        assertEquals(2, editText.getTotalLineCount())
        assertEquals(1, editText.findPhysicalLineNumber(0))
        assertEquals(2, editText.findPhysicalLineNumber(3))

        // Insert Enter at start: "\nab\ncd"
        editText.editableText.insert(0, "\n")
        assertEquals(3, editText.getTotalLineCount())
        assertEquals(1, editText.findPhysicalLineNumber(0))
        assertEquals(2, editText.findPhysicalLineNumber(1))
        assertEquals(3, editText.findPhysicalLineNumber(4))

        // Insert Enter at end: "\nab\ncd\n"
        val endPos = editText.editableText.length
        editText.editableText.insert(endPos, "\n")
        assertEquals(4, editText.getTotalLineCount())
        assertEquals(4, editText.findPhysicalLineNumber(editText.editableText.length))

        // Delete first newline: "ab\ncd\n"
        editText.editableText.delete(0, 1)
        assertEquals(3, editText.getTotalLineCount())
        assertEquals(1, editText.findPhysicalLineNumber(0))
        assertEquals(2, editText.findPhysicalLineNumber(3))

        // Replace middle range with multi-line text
        editText.editableText.replace(1, 4, "1\n2\n3") // "a1\n2\n3d\n"
        assertEquals("a1\n2\n3d\n", editText.getCurrentContent())
        assertEquals(4, editText.getTotalLineCount())
        assertEquals(1, editText.findPhysicalLineNumber(0))
        assertEquals(2, editText.findPhysicalLineNumber(3))
        assertEquals(3, editText.findPhysicalLineNumber(5))
        assertEquals(4, editText.findPhysicalLineNumber(8))
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
