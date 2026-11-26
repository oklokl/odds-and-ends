package com.krdonon.ratio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageProcessorTest {

    @Test
    fun calculateCanvasSize_expandsShortSideUsingRounding() {
        assertEquals(1000 to 1000, calculateCanvasSize(1000, 500, 1f))
        assertEquals(1080 to 608, calculateCanvasSize(1080, 500, 16f / 9f))
        assertEquals(1920 to 1080, calculateCanvasSize(1920, 1080, 16f / 9f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun calculateCanvasSize_rejectsZeroWidth() {
        calculateCanvasSize(0, 100, 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calculateCanvasSize_rejectsNonPositiveRatio() {
        calculateCanvasSize(100, 100, 0f)
    }

    @Test
    fun normalizeRotation_normalizesAllQuadrants() {
        assertEquals(0f, normalizeRotation(0f), 0f)
        assertEquals(90f, normalizeRotation(90f), 0f)
        assertEquals(180f, normalizeRotation(180f), 0f)
        assertEquals(270f, normalizeRotation(270f), 0f)
        assertEquals(0f, normalizeRotation(360f), 0f)
        assertEquals(270f, normalizeRotation(-90f), 0f)
        assertEquals(90f, normalizeRotation(450f), 0f)
    }

    @Test
    fun calculateInSampleSize_doublesUntilWithinMaxDimension() {
        assertEquals(1, calculateInSampleSizeForMaxDimension(1920, 1080, 2048))
        assertEquals(2, calculateInSampleSizeForMaxDimension(4096, 3072, 2048))
        assertEquals(4, calculateInSampleSizeForMaxDimension(8192, 4096, 2048))
    }

    @Test
    fun estimateWorkingSetBytes_skipsOutputBufferWhenTargetRatioIsNull() {
        val rotationOnly = estimateWorkingSetBytes(
            width = 2000,
            height = 1000,
            targetRatio = null,
            transformNeeded = true,
        )
        val withRatio = estimateWorkingSetBytes(
            width = 2000,
            height = 1000,
            targetRatio = 1f,
            transformNeeded = true,
        )
        assertTrue(withRatio > rotationOnly)
    }

    @Test
    fun buildExportFileName_formatsExtensionsAndTruncatesLongNames() {
        assertEquals("photo_260101120000.webp", buildExportFileName("photo", ImageFormat.WEBP, "260101120000"))
        assertEquals("photo_260101120000.png", buildExportFileName("photo", ImageFormat.PNG, "260101120000"))
        assertEquals("photo_260101120000.jpg", buildExportFileName("photo", ImageFormat.JPG, "260101120000"))

        val veryLong = "a".repeat(300)
        val built = buildExportFileName(veryLong, ImageFormat.WEBP, "260101120000")
        assertTrue(built.length <= 250)
        assertTrue(built.endsWith("_260101120000.webp"))
    }

    @Test
    fun ratioUiState_hasEditsReflectsRotationAndAspectRatio() {
        val base = RatioUiState(
            selectedImageFile = File("dummy.img"),
        )
        assertFalse(base.hasEdits)
        assertFalse(base.canSave)

        val rotatedOnly = base.copy(currentRotation = 90f)
        assertTrue(rotatedOnly.hasEdits)

        val rotatedFullCircle = rotatedOnly.copy(currentRotation = 0f)
        assertFalse(rotatedFullCircle.hasEdits)

        val withRatio = base.copy(selectedRatio = AspectRatioOption("1:1", 1f))
        assertTrue(withRatio.hasEdits)
        // editedBitmap is null while processing/unbuilt -> canSave must remain false until ready
        assertFalse(withRatio.canSave)

        val formatModifiedOnly = base.copy(isFormatOrQualityModified = true)
        assertTrue(formatModifiedOnly.hasEdits)
    }

    @Test
    fun ageSignals_classifyAgeRangeHandlesBoundaries() {
        assertEquals(UserAgeCategory.ADULT, AgeSignalsCompliance.classifyAgeRange(18, 25))
        assertEquals(UserAgeCategory.MINOR, AgeSignalsCompliance.classifyAgeRange(13, 17))
        assertEquals(UserAgeCategory.UNKNOWN, AgeSignalsCompliance.classifyAgeRange(16, 20))
        assertEquals(UserAgeCategory.UNKNOWN, AgeSignalsCompliance.classifyAgeRange(null, 17))
    }

    @Test
    fun imageFormats_capabilitiesMatchSpecification() {
        assertFalse(ImageFormat.JPG.supportsTransparency)
        assertTrue(ImageFormat.JPG.supportsQuality)

        assertTrue(ImageFormat.PNG.supportsTransparency)
        assertFalse(ImageFormat.PNG.supportsQuality)

        assertTrue(ImageFormat.WEBP.supportsTransparency)
        assertTrue(ImageFormat.WEBP.supportsQuality)
        assertEquals("image/webp", ImageFormat.WEBP.mimeType)
        assertEquals("webp", ImageFormat.WEBP.extension)
    }
}
