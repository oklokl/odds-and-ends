package com.krdonon.metronome

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

class SoundManager(private val context: Context) {
    private var soundPool: SoundPool? = null
    private val soundSets = mutableListOf<SoundSet>()
    private var currentSetIndex = 0

    data class SoundSet(
        val name: String,
        val weakBeatId: Int,
        val strongBeatId: Int
    )

    init {
        initializeSoundPool()
        loadSounds()
    }

    private fun initializeSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()
    }

    private fun loadSounds() {
        try {
            val assetManager = context.assets
            val soundsDir = "sounds"
            val sets = assetManager.list(soundsDir) ?: emptyArray()

            // 1) 이름이 set 으로 시작하는 것만 사용 (set0, set1, set2 ...)
            val filteredSets = sets
                .sorted()
                .filter { it.startsWith("set") }

            // 2) 디버깅용 로그 (옵션)
            val skipped = sets.toSet() - filteredSets.toSet()
            skipped.forEach {
                Log.d("SoundManager", "Skipping non-sound entry in sounds/: $it")
            }

            // 3) 실제 로딩은 기존과 동일
            filteredSets.forEach { setName ->
                val setPath = "$soundsDir/$setName"
                val weakPath = "$setPath/weak.mp3"
                val strongPath = "$setPath/strong.mp3"

                try {
                    val weakFd = context.assets.openFd(weakPath)
                    val strongFd = context.assets.openFd(strongPath)

                    val weakId = soundPool?.load(weakFd, 1) ?: -1
                    val strongId = soundPool?.load(strongFd, 1) ?: -1

                    if (weakId != -1 && strongId != -1) {
                        soundSets.add(SoundSet(setName, weakId, strongId))
                        Log.d("SoundManager", "Loaded sound set: $setName")
                    }

                    weakFd.close()
                    strongFd.close()
                } catch (e: Exception) {
                    Log.e("SoundManager", "Error loading sound set $setName: ${e.message}")
                }
            }

            if (soundSets.isEmpty()) {
                Log.w("SoundManager", "No sound sets loaded!")
            } else {
                Log.i("SoundManager", "Loaded ${soundSets.size} sound set(s)")
            }
        } catch (e: Exception) {
            Log.e("SoundManager", "Error loading sounds: ${e.message}")
        }
    }


    fun playWeakBeat() {
        if (soundSets.isEmpty()) return
        val currentSet = soundSets.getOrNull(currentSetIndex) ?: soundSets.first()
        soundPool?.play(currentSet.weakBeatId, 1f, 1f, 1, 0, 1f)
    }

    fun playStrongBeat() {
        if (soundSets.isEmpty()) return
        val currentSet = soundSets.getOrNull(currentSetIndex) ?: soundSets.first()
        soundPool?.play(currentSet.strongBeatId, 1f, 1f, 1, 0, 1f)
    }

    fun nextSoundSet() {
        if (soundSets.isEmpty()) return
        currentSetIndex = (currentSetIndex + 1) % soundSets.size
        Log.d("SoundManager", "Switched to sound set: ${soundSets[currentSetIndex].name}")
    }

    fun getCurrentSetName(): String {
        return soundSets.getOrNull(currentSetIndex)?.name ?: "None"
    }

    fun getSoundSetCount(): Int = soundSets.size

    fun release() {
        soundPool?.release()
        soundPool = null
        soundSets.clear()
    }
}
