package com.krdondon.txt.utils

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

object PdfExporter {
    /**
     * Writes the generated PDF directly to [output].
     *
     * Writing to the destination stream avoids creating an additional full-size
     * ByteArray/ByteArrayOutputStream copy of the PDF in memory.
     */
    fun writeTextToPdf(
        text: String,
        output: OutputStream,
        pageWidth: Int = 595,
        pageHeight: Int = 842,
        margin: Int = 40,
        fontSize: Float = 12f,
        lineSpacing: Float = 1.4f
    ) {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fontSize }

        val lineH = fontSize * lineSpacing
        val maxLines = ((pageHeight - margin * 2) / lineH).toInt().coerceAtLeast(1)
        val lines = text.lineSequence().iterator()

        var pageIndex = 0
        var hasMoreLines = lines.hasNext()

        // Keep the existing behavior of creating at least one page for an empty document.
        if (!hasMoreLines) {
            pageIndex++
            val page = pdf.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
            )
            pdf.finishPage(page)
        }

        try {
            while (hasMoreLines) {
                pageIndex++
                val page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
                )
                val canvas = page.canvas

                var y = margin + fontSize
                var count = 0
                while (hasMoreLines && count < maxLines) {
                    canvas.drawText(lines.next(), margin.toFloat(), y, paint)
                    y += lineH
                    count++
                    hasMoreLines = lines.hasNext()
                }

                pdf.finishPage(page)
            }

            pdf.writeTo(output)
            output.flush()
        } finally {
            pdf.close()
        }
    }
}
