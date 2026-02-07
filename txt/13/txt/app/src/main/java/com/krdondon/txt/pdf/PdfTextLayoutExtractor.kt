package com.krdondon.txt.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * PdfRenderer(비트맵 렌더링) 위에서 "부분 선택"을 구현하기 위해,
 * PDFBox로 각 글자의 대략적인 위치(바운딩 박스)를 추출한다.
 *
 * 주의:
 * - PDF의 텍스트 레이어가 없는(스캔본) 문서는 chars가 비어 있을 수 있다.
 * - PDFBox의 좌표/정렬은 문서에 따라 100% 정교하지 않을 수 있다.
 */
data class PageTextLayout(
    val pageWidthPt: Float,
    val pageHeightPt: Float,
    val chars: List<CharBox>
)

data class CharBox(
    val index: Int,
    val text: String,
    /** left/top/width/height in PDF points, with top-origin coordinate (0,0 = top-left) */
    val xPt: Float,
    val yTopPt: Float,
    val wPt: Float,
    val hPt: Float
)

suspend fun extractPageTextLayout(
    context: Context,
    uri: Uri,
    unlockedFile: File? = null,
    pageIndex: Int
): PageTextLayout {
    PDFBoxResourceLoader.init(context)

    val doc = if (unlockedFile != null && unlockedFile.exists()) {
        PDDocument.load(unlockedFile)
    } else {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open input stream for: $uri")
        input.use { PDDocument.load(it) }
    }

    doc.use { docInUse ->
            val page = docInUse.getPage(pageIndex)
            val mediaBox = page.mediaBox
            val pageW = mediaBox.width
            val pageH = mediaBox.height

            val out = ArrayList<CharBox>(8_000)
            var i = 0

            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    val unicode = text.unicode ?: return
                    if (unicode.isEmpty()) return

                    val x = text.xDirAdj
                    val y = text.yDirAdj
                    val w = text.widthDirAdj
                    val h = text.heightDir

                    // PDFBox의 y는 문서에 따라 bottom-origin인 경우가 많아 top-origin으로 변환
                    // (폰트/회전에 따라 약간의 오차 가능)
                    val yTop = pageH - y

                    out.add(
                        CharBox(
                            index = i++,
                            text = unicode,
                            xPt = x,
                            yTopPt = yTop,
                            wPt = w,
                            hPt = h
                        )
                    )
                }
            }

            val pageNo = pageIndex + 1
            stripper.startPage = pageNo
            stripper.endPage = pageNo
            // getText 호출이 내부적으로 processTextPosition을 트리거
            stripper.getText(docInUse)

            return PageTextLayout(
                pageWidthPt = pageW,
                pageHeightPt = pageH,
                chars = out
            )
    }
}
