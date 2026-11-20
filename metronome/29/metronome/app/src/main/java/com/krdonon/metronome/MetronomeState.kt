package com.krdonon.metronome

data class MetronomeState(
    val isPlaying: Boolean = false,
    val bpm: Int = 120,
    val beatsPerMeasure: Int = 4,
    // 단위: 분모(1 = 온음표, 2 = 2분, 4 = 4분, 8 = 8분, 16 = 16분)
    val beatUnit: Int = 4,
    val currentBeat: Int = 0,
    val soundSetIndex: Int = 0,
    /** UI 표시용(예: "set0") */
    val soundSetName: String = "set0",
    // 세분(추가 클릭)용 – 지금은 항상 0만 사용
    val subBeatIndex: Int = 0,
    val isVibrationMode: Boolean = false, // 진동
    // 화면 꺼짐 방지(유지 버튼)
    val keepScreenOn: Boolean = false
)

/**
 * 60/120 FPS 비주얼라이저 전용 프레임 스냅샷.
 * AudioTrack 하드웨어 타임스탬프(또는 진동 타이머)로부터 계산되며,
 * Compose StateFlow 재구성을 유발하지 않고 Canvas Draw 단계에서만 읽힙니다.
 */
data class BeatFrameState(
    val isPlaying: Boolean = false,
    val beatsPerMeasure: Int = 4,
    val currentBeat: Int = 0,
    val beatProgress: Float = 0f,
    val measureProgress: Float = 0f,
    val isPreStart: Boolean = false
)


