package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.example.R

class SoundHelper(context: Context) {
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(5)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private var soundId: Int = 0

    init {
        try {
            soundId = soundPool.load(context, R.raw.crystal_click, 1)
        } catch (_: Exception) {
        }
    }

    fun playClickSound() {
        if (soundId != 0) {
            try {
                soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
            } catch (_: Exception) {
            }
        }
    }

    fun release() {
        try {
            soundPool.release()
        } catch (_: Exception) {
        }
    }
}
