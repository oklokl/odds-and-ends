package com.krdondon.week

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetBackgroundTest {

    @Test
    fun `next cycles through all four backgrounds and wraps around`() {
        var current = WidgetBackground.SOLID_MINT
        current = current.next()
        assertEquals(WidgetBackground.MINT_ORIGINAL, current)
        current = current.next()
        assertEquals(WidgetBackground.MINT_SOFT_WAVES, current)
        current = current.next()
        assertEquals(WidgetBackground.MINT_CORNER_LAYERS, current)
        current = current.next()
        assertEquals(WidgetBackground.SOLID_MINT, current)
    }

    @Test
    fun `fromId falls back to SOLID_MINT for unknown or null id`() {
        assertEquals(WidgetBackground.SOLID_MINT, WidgetBackground.fromId(null))
        assertEquals(WidgetBackground.SOLID_MINT, WidgetBackground.fromId("unknown"))
        assertEquals(WidgetBackground.MINT_SOFT_WAVES, WidgetBackground.fromId("mint_02"))
    }
}
