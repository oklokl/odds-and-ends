package com.krdondon.txt

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemalto.jp2.JP2Decoder
import com.krdondon.txt.pdf.extractPageTextLayout
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class JP2AndPdfVerificationTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(targetContext.applicationContext)
    }

    @Test
    fun testJP2DecoderNativeDecoding() {
        // Load decodeTest.jp2 from test assets
        val inputStream = testContext.assets.open("decodeTest.jp2")
        val bytes = inputStream.readBytes()
        inputStream.close()
        assertTrue("JP2 byte array should not be empty", bytes.isNotEmpty())

        // Test JP2Decoder header parsing using readHeader()
        val decoder = JP2Decoder(bytes)
        val header = decoder.readHeader()
        assertNotNull("JP2 header should be decoded", header)
        assertTrue("Header width should be > 0", header.width > 0)
        assertTrue("Header height should be > 0", header.height > 0)

        // Test decoding to Bitmap (loads native libopenjpeg.so)
        val bitmap: Bitmap? = decoder.decode()
        assertNotNull("Decoded Bitmap should not be null", bitmap)
        assertEquals(header.width, bitmap!!.width)
        assertEquals(header.height, bitmap.height)
    }

    @Test
    fun testPdfBoxAndLayoutExtractionWithSamplePdf() = runBlocking {
        // Copy lorem-ipsum-10pages.pdf to cache
        val pdfInputStream = testContext.assets.open("lorem-ipsum-10pages.pdf")
        val cacheFile = File(targetContext.cacheDir, "sample_test.pdf")
        withContext(Dispatchers.IO) {
            FileOutputStream(cacheFile).use { out ->
                pdfInputStream.copyTo(out)
            }
        }
        pdfInputStream.close()
        assertTrue(cacheFile.exists())

        // Load document using PDFBox
        val document = PDDocument.load(cacheFile)
        assertNotNull(document)
        assertEquals(10, document.numberOfPages)
        document.close()

        // Test page text extraction
        val pageLayout = extractPageTextLayout(
            context = targetContext,
            uri = android.net.Uri.fromFile(cacheFile),
            unlockedFile = cacheFile,
            pageIndex = 0
        )
        assertNotNull(pageLayout)
        assertTrue("Page text layout should contain chars", pageLayout.chars.isNotEmpty())
    }
}
