package com.krdondon.read.util

/**
 * 텍스트 문서 내의 구분선(예: ----------, ──────────, 구분선 등)을
 * TTS 음성 합성 시 "다시다시...", "대시대시...", 기호 이름 등으로 읽지 않도록
 * 필터링하고 건너뛰는 유틸리티 클래스입니다.
 */
object SpeechSanitizer {

    // 2개 이상 연속된 구분선/장식 기호 (- ─ ━ = ~ * _ # 등)
    private val REPEATED_DIVIDERS = Regex("[-─━—–−=~∼〰*★☆#_■□◆◇●○▲▼▶◀/\\\\|]{2,}")

    // 공백을 사이에 둔 반복 기호 (예: "- - - -", "* * * *", "= = = =")
    private val SPACED_DIVIDERS = Regex("([-─━—–−=~*#_■◆●]\\s*){3,}")

    // 단독 유니코드 선 그리기 문자 (Box Drawing Characters)
    private val BOX_DRAWING_CHARS = Regex("[─━│┃┌┐└┘├┤┬┴┼═║╒╓╔╕╖╗╘╙╚╛╜╝╞╟╠╡╢╣╤╥╦╧╨╩╪╫╬]")

    // 단순 구분선/절취선 전용 키워드
    private val DIVIDER_KEYWORDS = setOf(
        "구분선", "절취선", "경계선", "단락구분", "줄바꿈",
        "divider", "separator", "boundary", "line"
    )

    /**
     * TTS 발화용 텍스트 정제:
     * - 반복되는 하이픈/대시/기호 및 유니코드 선 그리기 문자를 제거
     * - 실제 발음 가능한 문자(한글, 영문, 숫자)가 남아있지 않으면 빈 문자열("") 반환
     */
    fun sanitizeForSpeech(rawText: String): String {
        if (rawText.isBlank()) return ""

        val cleaned = rawText
            .replace(REPEATED_DIVIDERS, " ")
            .replace(SPACED_DIVIDERS, " ")
            .replace(BOX_DRAWING_CHARS, " ")
            .trim()

        // 발음 가능한 문자(한글, 영문, 숫자)가 포함되어 있는지 검사
        val hasPronounceableChar = cleaned.any { char ->
            char.isLetterOrDigit() || char in '가'..'힣'
        }

        return if (hasPronounceableChar) cleaned else ""
    }

    /**
     * '구분선', '절취선' 등 내용이 없는 단순 구분선 표시인지 판별
     */
    fun isDividerWord(text: String): Boolean {
        val normalized = text.replace(Regex("[\\s\\[\\](){}<>_\\-~·.]"), "").trim()
        return normalized.isEmpty() || normalized in DIVIDER_KEYWORDS
    }

    /**
     * 해당 텍스트가 TTS에서 읽지 않고 바로 넘어가야 하는 순수 구분선인지 판별
     */
    fun shouldSkipSentence(rawText: String): Boolean {
        val sanitized = sanitizeForSpeech(rawText)
        return sanitized.isBlank() || isDividerWord(sanitized)
    }
}
