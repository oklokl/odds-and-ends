package com.krdondon.week

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TimeWidgetSizingTest {

    @Test
    fun `text size scales up as widget size grows`() {
        val small = TimeWidgetProvider.calculateWidgetMetrics(widthDp = 130f, heightDp = 56f)
        val medium = TimeWidgetProvider.calculateWidgetMetrics(widthDp = 260f, heightDp = 150f)
        val large = TimeWidgetProvider.calculateWidgetMetrics(widthDp = 360f, heightDp = 260f)

        assertTrue(small.timeSizeDp < medium.timeSizeDp)
        assertTrue(medium.timeSizeDp < large.timeSizeDp)
        assertTrue(small.timeSizeDp >= 11f)
        assertTrue(large.timeSizeDp <= 52f)
        assertEquals(large.timeSizeDp, large.dateSizeDp, 0.001f)
        assertEquals(large.ampmSizeDp, large.daySizeDp, 0.001f)
        assertTrue(large.ampmSizeDp < large.timeSizeDp)
        assertTrue(large.daySizeDp < large.dateSizeDp)
        assertTrue(large.hangulBottomOffsetDp > 0f)
    }

    @Test
    fun `narrow tall widget stays constrained by width`() {
        val narrowTall = TimeWidgetProvider.calculateWidgetMetrics(widthDp = 130f, heightDp = 220f)
        val wideTall = TimeWidgetProvider.calculateWidgetMetrics(widthDp = 320f, heightDp = 220f)

        assertTrue(narrowTall.timeSizeDp < wideTall.timeSizeDp)
    }

    @Test
    fun `non korean locale keeps ampm and day size equal to time and date size`() {
        val english = TimeWidgetProvider.calculateWidgetMetrics(
            widthDp = 260f,
            heightDp = 150f,
            isKoreanLocale = false,
        )

        assertEquals(english.timeSizeDp, english.ampmSizeDp, 0.001f)
        assertEquals(english.dateSizeDp, english.daySizeDp, 0.001f)
        assertEquals(0f, english.hangulBottomOffsetDp, 0.001f)
    }

    @Test
    fun `isSunday returns true only on Sunday`() {
        val sunday = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 22) // 2026-11-22 is Sunday
        }
        val monday = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 23)
        }
        val saturday = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 21)
        }

        assertTrue(TimeWidgetProvider.isSunday(sunday))
        assertFalse(TimeWidgetProvider.isSunday(monday))
        assertFalse(TimeWidgetProvider.isSunday(saturday))
    }

    @Test
    fun `nextMidnightDelayAnchorMillis schedules next day 00_00_05`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 21, 23, 50, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val next = Calendar.getInstance().apply {
            timeInMillis = TimeWidgetProvider.nextMidnightDelayAnchorMillis(now)
        }

        assertEquals(2026, next.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, next.get(Calendar.MONTH))
        assertEquals(22, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
        assertEquals(5, next.get(Calendar.SECOND))
    }
}
