package com.krdonon.timer

import androidx.lifecycle.ViewModel

/**
 * 스톱워치 상태를 보관하는 ViewModel
 * - 프래그먼트 전환/재생성 시에도 값 유지
 */
class StopwatchViewModel : ViewModel() {
    var isRunning: Boolean = false

    /** 현재 러닝을 시작한 기준 시간 (SystemClock.elapsedRealtime) */
    var startBaseMs: Long = 0L

    /** 이전까지 누적된 경과 시간 (정지/일시정지 때 누적) */
    var accumulatedMs: Long = 0L

    /** 랩 관련 */
    val lapTimes: MutableList<String> = mutableListOf()
    var lapCount: Int = 0
    var previousLapTotalMs: Long = 0L
}
