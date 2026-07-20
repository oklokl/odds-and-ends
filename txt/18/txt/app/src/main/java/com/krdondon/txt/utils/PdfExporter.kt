package com.krdondon.txt.utils

import android.graphics.Paint
import android.graphics.pdf.PdfDocument

object PdfExporter {
    fun textToPdfBytes(
        text: String,
        pageWidth: Int = 595,
        pageHeight: Int = 842,
        margin: Int = 40,
        fontSize: Float = 12f,
        lineSpacing: Float = 1.4f
    ): ByteArray {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fontSize }

        val lines = text.replace("\r\n", "\n").split("\n")
        val lineH = fontSize * lineSpacing
        val maxLines = ((pageHeight - margin * 2) / lineH).toInt().coerceAtLeast(1)

        var pageIndex = 0
        var i = 0
        while (i < lines.size) {
            pageIndex++
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create())
            val canvas = page.canvas

            var y = margin + fontSize
            var count = 0
            while (i < lines.size && count < maxLines) {
                canvas.drawText(lines[i], margin.toFloat(), y, paint)
                y += lineH
                i++
                count++
            }

            pdf.finishPage(page)
        }

        return try {
            val out = java.io.ByteArrayOutputStream()
            pdf.writeTo(out)
            out.toByteArray()
        } finally {
            pdf.close()
        }
    }
}
