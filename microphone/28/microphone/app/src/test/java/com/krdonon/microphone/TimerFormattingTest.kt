package com.krdonon.microphone

import com.krdonon.microphone.service.RecordingStateManager
import com.krdonon.microphone.ui.screens.formatTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerFormattingTest {

    @Test
    fun formatTime_advancesEveryTenthOfASecondWithoutSkipping() {
        val expected = (0..9).map { tenth -> "00:00:02.$tenth" }
        val actual = (0..9).map { tenth -> formatTime(2_000L + tenth * 100L) }
        assertEquals(expected, actual)
    }

    @Test
    fun formatTime_handlesMinutesHoursAndMaxClamp() {
        assertEquals("00:00:00.0", formatTime(0L))
        assertEquals("00:01:05.3", formatTime(65_350L))
        assertEquals("01:02:03.9", formatTime(3_723_999L))
        assertEquals("99:00:00.0", formatTime(100L * 3600L * 1000L))
    }

    @Test
    fun recordingStateManager_lifecycleUpdatesStateCleanly() {
        RecordingStateManager.onStart()
        assertTrue(RecordingStateManager.isRecording.value)
        assertFalse(RecordingStateManager.isPaused.value)
        assertEquals(0L, RecordingStateManager.elapsedTime.value)

        RecordingStateManager.updateElapsedTime(1234L)
        assertEquals(1234L, RecordingStateManager.elapsedTime.value)

        RecordingStateManager.onPause()
        assertTrue(RecordingStateManager.isPaused.value)

        RecordingStateManager.onResume()
        assertFalse(RecordingStateManager.isPaused.value)

        RecordingStateManager.onStop(fromNotification = true)
        assertFalse(RecordingStateManager.isRecording.value)
        assertTrue(RecordingStateManager.stoppedFromNotification.value)
        assertEquals(0L, RecordingStateManager.elapsedTime.value)

        RecordingStateManager.consumeStoppedFromNotification()
        assertFalse(RecordingStateManager.stoppedFromNotification.value)
    }
}
