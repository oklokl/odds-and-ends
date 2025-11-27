package com.krdonon.metronome

data class MetronomeState(
    val isPlaying: Boolean = false,
    val bpm: Int = 120,
    val beatsPerMeasure: Int = 4,
    // 단위: 분모(1 = 온음표, 2 = 2분, 4 = 4분, 8 = 8분, 16 = 16분)
    val beatUnit: Int = 4,
    val currentBeat: Int = 0,
    val soundSetIndex: Int = 0,
    // 세분(추가 클릭)용 – 지금은 항상 0만 사용
    val subBeatIndex: Int = 0,
    val isVibrationMode: Boolean = false // 진동
)


