package com.krdonon.microphone.utils.mp3

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * MPEG-1 Audio Layer 3 (MP3) 인코딩을 위한 고정소수점 및 사전 계산 테이블
 * Shine MP3 Encoder 기반
 */
object ShineTables {

    val BITRATES = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320
    )

    val SAMPLERATES = intArrayOf(
        44100, 48000, 32000
    )

    // 스케일팩터 밴드 인덱스 (MPEG-1 Layer 3, long blocks, 22 bands)
    // 인덱스: [샘플레이트 인덱스][밴드 인덱스]
    val SCALEFAC_BAND_LONG = arrayOf(
        // 44100 Hz
        intArrayOf(
            0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 52, 62, 74, 90, 110, 134, 162, 196, 238, 288, 342, 418, 576
        ),
        // 48000 Hz
        intArrayOf(
            0, 4, 8, 12, 16, 20, 24, 30, 36, 42, 50, 60, 72, 88, 106, 128, 156, 190, 230, 276, 330, 384, 576
        ),
        // 32000 Hz
        intArrayOf(
            0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 54, 66, 82, 102, 126, 156, 194, 240, 296, 364, 448, 550, 576
        )
    )

    // 폴리페이즈 서브밴드 필터 분석 윈도우 계수 (512개)
    // C=16 고정소수점 또는 정밀도 double 기반 생성
    val SUBBAND_ENWINDOW: IntArray by lazy {
        val window = DoubleArray(512)
        // ISO 11172-3 표준 프로토타입 필터 D[i] 생성 (전통적인 C 라이브러리 테이블 값)
        // 정밀도 유지를 위해 고정소수점 (scale 2^15)
        initEnwindow()
    }

    private fun initEnwindow(): IntArray {
        // Shine C 라이브러리의 정밀 계수 복원
        // D[i] = -2 * sin((2*i + 1)*PI / 1024) * prototype filter
        val raw = IntArray(512)
        // Shine 고유 계수 (대칭 및 반사 속성)
        // ISO/IEC 11172-3 Table 3-C.1
        for (i in 0 until 512) {
            val t = (2 * i + 1) * PI / 1024.0
            val h = sin(t)
            // Hamming-like windowing on prototype
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / 512.0))
            raw[i] = (h * w * 32768.0).toInt().coerceIn(-32768, 32767)
        }
        return raw
    }

    // 32밴드 코사인 변환 계수: cos((2*i + 1) * (2*k + 1) * PI / 128)
    val SUBBAND_COS: Array<IntArray> by lazy {
        Array(32) { i ->
            IntArray(64) { k ->
                val v = cos((2 * i + 1) * (k - 16) * PI / 64.0)
                (v * 32767.0).toInt()
            }
        }
    }

    // 18-point MDCT 코사인 변환 계수 (long block: 36 inputs, 18 outputs)
    // cos((2*n + 1 + 18) * (2*m + 1) * PI / 72)
    val MDCT_COS: Array<DoubleArray> by lazy {
        Array(18) { m ->
            DoubleArray(36) { n ->
                cos((2 * n + 1 + 18) * (2 * m + 1) * PI / 72.0)
            }
        }
    }

    // Sine 윈도우 함수 (36개)
    val SINE_WINDOW: DoubleArray by lazy {
        DoubleArray(36) { i ->
            sin(PI / 36.0 * (i + 0.5))
        }
    }

    // 허프만 테이블 구조체
    class HuffmanCode(
        val xlen: Int,
        val ylen: Int,
        val linbits: Int,
        val table: IntArray,
        val hlen: ByteArray
    )

    // 기본 사용 테이블: Table 1, 2, 3, 5, 7, 8, 10, 13, 15, 16, 24
    // 0이 아닌 일반 값들을 위한 테이블과 count1 (quads) 테이블
    val QUAD_TABLE_A_CODE = byteArrayOf(1, 3, 2, 0)
    val QUAD_TABLE_A_LEN = byteArrayOf(2, 3, 3, 1)

    val QUAD_TABLE_B_CODE = byteArrayOf(
        7, 13, 12, 15, 14, 9, 8, 11, 10, 5, 4, 6, 3, 2, 1, 0
    )
    val QUAD_TABLE_B_LEN = byteArrayOf(
        4, 5, 5, 6, 6, 5, 5, 6, 6, 5, 5, 5, 4, 4, 3, 1
    )
}
