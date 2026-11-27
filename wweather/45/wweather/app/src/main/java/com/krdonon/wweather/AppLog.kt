package com.krdonon.wweather

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 내부 진단 로그(사용자 공유용)
 * - 시스템 logcat 전체가 아니라, 앱이 직접 남기는 로그만 저장합니다.
 * - 최대 용량을 제한하여 무한히 쌓이지 않게 합니다.
 */
object AppLog {
    private const val MAX_CHARS = 120_000  // 약 120KB
    private val buf = StringBuilder()
    private val tsFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.KOREA)

    @Synchronized
    fun clear() {
        buf.setLength(0)
    }

    @Synchronized
    fun i(tag: String, msg: String) {
        append("D", tag, msg)
    }

    @Synchronized
    fun e(tag: String, msg: String) {
        append("E", tag, msg)
    }

    @Synchronized
    fun dump(): String = buf.toString()

    private fun append(level: String, tag: String, msg: String) {
        val line = "${tsFmt.format(Date())} $level/$tag: $msg\n"
        // 용량 제한: 초과 시 앞부분을 잘라냄
        if (buf.length + line.length > MAX_CHARS) {
            val overflow = (buf.length + line.length) - MAX_CHARS
            val cut = minOf(overflow + 2000, buf.length) // 여유 있게 더 자름
            buf.delete(0, cut)
        }
        buf.append(line)
    }
}
