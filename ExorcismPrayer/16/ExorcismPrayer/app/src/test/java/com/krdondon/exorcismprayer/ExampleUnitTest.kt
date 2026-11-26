package com.krdondon.exorcismprayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun mediaList_hasValidUniqueItems() {
        assertTrue(mediaList.isNotEmpty())
        val defaultPaths = mediaList.map { it.defaultAssetPath }.toSet()
        val alternatePaths = mediaList.map { it.alternateAssetPath }.toSet()
        assertEquals(mediaList.size, defaultPaths.size)
        assertEquals(mediaList.size, alternatePaths.size)
        mediaList.forEach { item ->
            assertTrue(item.title.isNotBlank())
            assertTrue(item.description.isNotBlank())
            assertTrue(item.defaultAssetPath.startsWith("voice_default/"))
            assertTrue(item.alternateAssetPath.startsWith("voice_261121/"))
        }
    }

    @Test
    fun navigationIndices_wrapAroundAtBoundaries() {
        assertEquals(mediaList.lastIndex, previousMediaIndex(0))
        assertEquals(0, previousMediaIndex(1))
        assertEquals(0, nextMediaIndex(mediaList.lastIndex))
        assertEquals(1, nextMediaIndex(0))
    }

    @Test
    fun prayerVoice_togglesAndParsesStorageKey() {
        val voices = PrayerVoice.entries
        voices.forEachIndexed { index, voice ->
            val expectedNext = voices[(index + 1) % voices.size]
            assertEquals(expectedNext, voice.next())
            assertTrue(voice.displayName.isNotBlank())
            assertTrue(voice.buttonLabel.isNotBlank())
        }
        assertEquals(PrayerVoice.DEFAULT, PrayerVoice.fromStorageKey(null))
        assertEquals(PrayerVoice.DEFAULT, PrayerVoice.fromStorageKey("unknown"))
        assertEquals(PrayerVoice.ALTERNATE, PrayerVoice.fromStorageKey("261121"))
    }
}
