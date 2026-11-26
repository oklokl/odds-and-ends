package com.krdondon.thelordsprayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSetStoreTest {

    @Test
    fun parseAndSortVoiceSets_sortsNumericallyAndFiltersInvalidEntries() {
        val rawNames = listOf("set10", "set2", "other", "set0", "set1", "set14", "set", "setA")
        val sorted = VoiceSetStore.parseAndSortVoiceSets(rawNames)
        assertEquals(listOf("set0", "set1", "set2", "set10", "set14"), sorted)
    }

    @Test
    fun getNextVoiceSet_cyclesAndWrapsAround() {
        val sets = listOf("set0", "set1", "set2")

        assertEquals("set0", VoiceSetStore.getNextVoiceSet(sets, null))
        assertEquals("set1", VoiceSetStore.getNextVoiceSet(sets, "set0"))
        assertEquals("set2", VoiceSetStore.getNextVoiceSet(sets, "set1"))
        assertEquals("set0", VoiceSetStore.getNextVoiceSet(sets, "set2"))
        assertNull(VoiceSetStore.getNextVoiceSet(emptyList(), "set0"))
    }

    @Test
    fun getPreviousVoiceSet_cyclesAndWrapsAround() {
        val sets = listOf("set0", "set1", "set2")

        assertEquals("set2", VoiceSetStore.getPreviousVoiceSet(sets, null))
        assertEquals("set2", VoiceSetStore.getPreviousVoiceSet(sets, "set0"))
        assertEquals("set0", VoiceSetStore.getPreviousVoiceSet(sets, "set1"))
        assertEquals("set1", VoiceSetStore.getPreviousVoiceSet(sets, "set2"))
        assertNull(VoiceSetStore.getPreviousVoiceSet(emptyList(), "set0"))
    }

    @Test
    fun formatVoiceLabel_handlesNullAndBlank() {
        assertEquals("기본", VoiceSetStore.formatVoiceLabel(null))
        assertEquals("기본", VoiceSetStore.formatVoiceLabel(""))
        assertEquals("기본", VoiceSetStore.formatVoiceLabel("   "))
        assertEquals("set5", VoiceSetStore.formatVoiceLabel("set5"))
    }
}
