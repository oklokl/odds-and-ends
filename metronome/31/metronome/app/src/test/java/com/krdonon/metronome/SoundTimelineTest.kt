package com.krdonon.metronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundTimelineTest {

    @Test
    fun preStartNegativeFrameMapsToPreStartState() {
        val starts = intArrayOf(0, 24_000, 48_000, 72_000)
        val state = SoundManager.computeBeatFrameState(
            currentFrame = -1_200.0,
            beatsPerMeasure = 4,
            measureFrames = 96_000,
            beatStartFrames = starts
        )
        assertTrue(state.isPreStart)
        assertEquals(0, state.currentBeat)
        assertEquals(0f, state.beatProgress, 0.0001f)
    }

    @Test
    fun fourFour120BpmMapsAllBeatsAndMeasureWrapAround() {
        val starts = intArrayOf(0, 24_000, 48_000, 72_000)
        val measureFrames = 96_000

        val b0 = SoundManager.computeBeatFrameState(0.0, 4, measureFrames, starts)
        assertFalse(b0.isPreStart)
        assertEquals(0, b0.currentBeat)
        assertEquals(0f, b0.beatProgress, 0.0001f)

        val b1Mid = SoundManager.computeBeatFrameState(36_000.0, 4, measureFrames, starts)
        assertEquals(1, b1Mid.currentBeat)
        assertEquals(0.5f, b1Mid.beatProgress, 0.0001f)
        assertEquals(0.375f, b1Mid.measureProgress, 0.0001f)

        val b3 = SoundManager.computeBeatFrameState(72_000.0, 4, measureFrames, starts)
        assertEquals(3, b3.currentBeat)
        assertEquals(0f, b3.beatProgress, 0.0001f)

        // Wrap around into 10th measure
        val wrapped = SoundManager.computeBeatFrameState(96_000.0 * 10 + 48_000.0, 4, measureFrames, starts)
        assertEquals(2, wrapped.currentBeat)
        assertEquals(0f, wrapped.beatProgress, 0.0001f)
        assertEquals(0.5f, wrapped.measureProgress, 0.0001f)
    }

    @Test
    fun sevenEight120BpmMapsSevenBeatsAccurately() {
        val starts = intArrayOf(0, 12_000, 24_000, 36_000, 48_000, 60_000, 72_000)
        val measureFrames = 84_000

        val beat6 = SoundManager.computeBeatFrameState(78_000.0, 7, measureFrames, starts)
        assertEquals(6, beat6.currentBeat)
        assertEquals(0.5f, beat6.beatProgress, 0.0001f)
    }
}
