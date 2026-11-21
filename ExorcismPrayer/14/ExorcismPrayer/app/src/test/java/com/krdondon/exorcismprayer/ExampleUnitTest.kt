package com.krdondon.exorcismprayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun mediaList_hasValidUniqueItems() {
        assertTrue(mediaList.isNotEmpty())
        val resourceIds = mediaList.map { it.resourceId }.toSet()
        assertEquals(mediaList.size, resourceIds.size)
        mediaList.forEach { item ->
            assertTrue(item.title.isNotBlank())
            assertTrue(item.description.isNotBlank())
            assertTrue(item.resourceId != 0)
        }
    }
}
