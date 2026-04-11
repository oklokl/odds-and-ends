package com.krdonon.timer

import androidx.lifecycle.ViewModel

/**
 * 2번째(독립) 스톱워치 상태
 * - 기존 StopwatchViewModel 과 완전히 분리
 */
class StopwatchSecondViewModel : ViewModel() {
    var isRunning: Boolean = false
    var startBaseMs: Long = 0L
    var accumulatedMs: Long = 0L

    /** 랩 기록 */
    val lapTimes: MutableList<String> = mutableListOf()
    var lapCount: Int = 0
    var previousLapTotalMs: Long = 0L
}
