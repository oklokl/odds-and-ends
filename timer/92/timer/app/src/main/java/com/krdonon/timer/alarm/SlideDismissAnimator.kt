package com.krdonon.timer.alarm

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.SeekBar
import android.widget.TextView

/**
 * 해제 슬라이더가 오른쪽으로 밀어 움직이는 동작임을 직관적으로 보여주는 힌트 애니메이션.
 * - SeekBar의 progress를 0 -> 18 -> 0으로 부드럽게 왕복 (fromUser = false이므로 해제 트리거 안 됨)
 * - 안내 문구(→ 끝까지 밀면 꺼집니다)도 함께 오른쪽으로 살짝 이동
 * - 사용자가 슬라이더를 터치하는 즉시 중단되고, 터치를 놓으면 재개됨
 */
class SlideDismissAnimator(
    private val seekBar: SeekBar,
    private val hintText: TextView,
) {
    private var currentSet: AnimatorSet? = null
    private var active = false
    private var touching = false

    fun start() {
        active = true
        if (!touching) {
            scheduleNextCycle(initialDelayMs = 250L)
        }
    }

    fun pauseForTouch() {
        touching = true
        cancelAnimator()
        hintText.translationX = 0f
    }

    fun resumeAfterTouch() {
        touching = false
        if (active) {
            scheduleNextCycle(initialDelayMs = 500L)
        }
    }

    fun stop() {
        active = false
        touching = false
        cancelAnimator()
        hintText.translationX = 0f
    }

    private fun cancelAnimator() {
        currentSet?.removeAllListeners()
        currentSet?.cancel()
        currentSet = null
    }

    private fun scheduleNextCycle(initialDelayMs: Long) {
        cancelAnimator()
        if (!active || touching) return

        val thumbNudge = ObjectAnimator.ofInt(seekBar, "progress", 0, 18, 0).apply {
            duration = 1150L
            interpolator = AccelerateDecelerateInterpolator()
        }

        val hintShiftPx = 10f * hintText.resources.displayMetrics.density
        val textNudge = ObjectAnimator.ofFloat(hintText, "translationX", 0f, hintShiftPx, 0f).apply {
            duration = 1150L
            interpolator = AccelerateDecelerateInterpolator()
        }

        val set = AnimatorSet().apply {
            playTogether(thumbNudge, textNudge)
            startDelay = initialDelayMs
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && active && !touching) {
                        scheduleNextCycle(initialDelayMs = 550L)
                    }
                }
            })
        }
        currentSet = set
        set.start()
    }
}
