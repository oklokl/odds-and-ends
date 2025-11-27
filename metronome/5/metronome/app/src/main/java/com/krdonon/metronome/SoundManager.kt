package com.krdonon.metronome

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

class SoundManager(private val context: Context) {

    private var soundPool: SoundPool? = null
    private val soundSets = mutableListOf<SoundSet>()
    private var currentSetIndex = 0

    // 로딩 완료 관리용
    @Volatile
    private var totalToLoad: Int = 0

    @Volatile
    private var loadedCount: Int = 0

    @Volatile
    private var allLoaded: Boolean = false

    data class SoundSet(
        val name: String,
        val weakBeatId: Int,
        val strongBeatId: Int
    )

    companion object {
        private const val TAG = "SoundManager"

        private const val SOUNDS_DIR = "sounds"
        private const val WEAK_FILE = "weak.mp3"
        private const val STRONG_FILE = "strong.mp3"
    }

    init {
        initializeSoundPool()
        loadSounds()
    }

    private fun initializeSoundPool() {
        if (soundPool != null) return

        val audioAttributes = AudioAttributes.Builder()
            // 알림/효과음 용으로 설정 (메트로놈 톡톡 소리)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)   // 동시에 겹쳐 울려도 여유 있게
            .setAudioAttributes(audioAttributes)
            .build().apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0 && sampleId != 0) {
                        loadedCount++
                        if (loadedCount >= totalToLoad && totalToLoad > 0) {
                            allLoaded = true
                            Log.d(TAG, "All sounds loaded: $loadedCount/$totalToLoad")
                        }
                    }
                }
            }
    }

    private fun loadSounds() {
        val sp = soundPool ?: return

        allLoaded = false
        loadedCount = 0
        totalToLoad = 0
        soundSets.clear()

        try {
            val assetManager = context.assets
            val sets = assetManager.list(SOUNDS_DIR) ?: emptyArray()

            // set0, set1 … 만 사용 (원래 코드의 필터 유지)
            val filteredSets = sets.sorted().filter { it.startsWith("set") }

            val skipped = sets.toSet() - filteredSets.toSet()
            skipped.forEach {
                Log.d(TAG, "Skipping non-sound entry in sounds/: $it")
            }

            filteredSets.forEach { setName ->
                val setPath = "$SOUNDS_DIR/$setName"
                val weakPath = "$setPath/$WEAK_FILE"
                val strongPath = "$setPath/$STRONG_FILE"

                var weakFd: AssetFileDescriptor? = null
                var strongFd: AssetFileDescriptor? = null

                try {
                    weakFd = assetManager.openFd(weakPath)
                    strongFd = assetManager.openFd(strongPath)

                    val weakId = sp.load(weakFd, 1)
                    val strongId = sp.load(strongFd, 1)

                    if (weakId != 0 && strongId != 0) {
                        soundSets.add(SoundSet(setName, weakId, strongId))
                        totalToLoad += 2
                        Log.d(TAG, "Queued sound set load: $setName (weak=$weakId, strong=$strongId)")
                    } else {
                        Log.e(TAG, "Invalid sound IDs for set: $setName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading sound set $setName: ${e.message}")
                } finally {
                    try {
                        weakFd?.close()
                    } catch (_: Exception) { }
                    try {
                        strongFd?.close()
                    } catch (_: Exception) { }
                }
            }

            if (soundSets.isEmpty()) {
                Log.w(TAG, "No sound sets loaded!")
            } else {
                Log.i(TAG, "Requested load of ${soundSets.size} sound set(s)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sounds: ${e.message}")
        }
    }

    /**
     * 앱/서비스 시작 시 한 번 호출하면
     * 첫 박이 씹히는 현상을 줄이는 효과가 있습니다.
     */
    fun warmUp() {
        if (!allLoaded) return
        val sp = soundPool ?: return
        val firstSet = soundSets.firstOrNull() ?: return

        // 내부 디코더 준비용. 볼륨 0으로 한 번씩 재생.
        sp.play(firstSet.weakBeatId, 0f, 0f, 1, 0, 1f)
        sp.play(firstSet.strongBeatId, 0f, 0f, 1, 0, 1f)
    }

    fun playWeakBeat() {
        if (!allLoaded) {
            // 아직 로딩 안 끝난 경우는 조용히 무시 (첫 박 씹힘/노이즈 방지)
            return
        }
        if (soundSets.isEmpty()) return

        val sp = soundPool ?: return
        val currentSet = soundSets.getOrNull(currentSetIndex) ?: soundSets.first()

        // 재생 속도는 항상 1.0f (BPM은 타이머로만 조절)
        sp.play(currentSet.weakBeatId, 1f, 1f, 1, 0, 1f)
    }

    fun playStrongBeat() {
        if (!allLoaded) return
        if (soundSets.isEmpty()) return

        val sp = soundPool ?: return
        val currentSet = soundSets.getOrNull(currentSetIndex) ?: soundSets.first()

        sp.play(currentSet.strongBeatId, 1f, 1f, 1, 0, 1f)
    }

    fun nextSoundSet() {
        if (soundSets.isEmpty()) return
        currentSetIndex = (currentSetIndex + 1) % soundSets.size
        Log.d(TAG, "Switched to sound set: ${soundSets[currentSetIndex].name}")
    }

    fun getCurrentSetName(): String {
        return soundSets.getOrNull(currentSetIndex)?.name ?: "None"
    }

    fun getSoundSetCount(): Int = soundSets.size

    fun release() {
        try {
            soundPool?.release()
        } catch (_: Exception) {
        } finally {
            soundPool = null
            soundSets.clear()
            totalToLoad = 0
            loadedCount = 0
            allLoaded = false
        }
    }
}
