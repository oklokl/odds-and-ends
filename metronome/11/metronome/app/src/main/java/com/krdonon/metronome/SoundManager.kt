package com.krdonon.metronome

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred

class SoundManager(private val context: Context) {

    private var soundPool: SoundPool? = null

    /** sounds/set0, set1 ... 처럼 "사용 가능한 세트 이름" */
    private var setNames: List<String> = emptyList()

    /** 실제로 로드 완료된 세트만 보관 */
    private val loadedSets = ConcurrentHashMap<String, SoundSet>()

    private var currentSetIndex = 0

    // 로딩 완료 관리용(전체 preload 진행 상황용)
    @Volatile private var totalToLoad: Int = 0
    @Volatile private var loadedCount: Int = 0

    // sampleId -> completion wait
    private val loadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        scanSoundSets() // 빠른 작업(리스트업)만 수행하고, 실제 로드는 백그라운드에서 처리
    }

    private fun initializeSoundPool() {
        if (soundPool != null) return

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build().apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    // sp.load()는 async -> 완료 시점에 sampleId로 깨워줌
                    val waiter = loadWaiters.remove(sampleId)
                    if (status == 0 && sampleId != 0) {
                        loadedCount++
                        waiter?.complete(Unit)
                    } else {
                        waiter?.completeExceptionally(IllegalStateException("SoundPool load failed: id=$sampleId status=$status"))
                    }
                }
            }
    }

    /** assets/sounds 아래 set* 디렉터리 목록만 스캔(가벼움) */
    private fun scanSoundSets() {
        try {
            val assetManager = context.assets
            val sets = assetManager.list(SOUNDS_DIR) ?: emptyArray()

            val filtered = sets
                .filter { it.startsWith("set") }
                .sortedWith(compareBy { name ->
                    name.removePrefix("set").toIntOrNull() ?: Int.MAX_VALUE
                })

            // 디버그 로그는 과도하면 시작 프레임에 영향을 줄 수 있어 최소화
            setNames = filtered
            if (setNames.isEmpty()) {
                Log.w(TAG, "No sound sets found under assets/$SOUNDS_DIR")
            } else {
                Log.i(TAG, "Found ${setNames.size} sound set(s)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning sounds: ${e.message}")
            setNames = emptyList()
        }
    }

    /**
     * 앱/서비스 시작 직후 호출:
     * 1) 초기 세트(기본 set0)만 먼저 로드해서 첫 재생 준비
     * 2) 나머지는 백그라운드에서 천천히 preload(초기 버벅임 방지)
     */
    fun preloadAsync(initialSetName: String = "set0") {
        scope.launch {
            totalToLoad = setNames.size * 2
            loadedCount = 0

            // 1) 초기 세트 우선 로드
            val first = if (setNames.contains(initialSetName)) initialSetName else setNames.firstOrNull()
            if (first != null) {
                try {
                    loadSetIfNeeded(first)
                    warmUp(first)
                } catch (e: Exception) {
                    Log.e(TAG, "Initial set preload failed ($first): ${e.message}")
                }
            }

            // 2) 나머지 세트는 지연 로드(스파이크 방지)
            for (name in setNames) {
                if (name == first) continue
                try {
                    loadSetIfNeeded(name)
                } catch (e: Exception) {
                    Log.e(TAG, "Preload failed ($name): ${e.message}")
                }
                // 너무 공격적으로 로드하면 CPU/IO 스파이크가 생길 수 있어 약간 숨 고르기
                delay(30)
            }

            Log.d(TAG, "Preload done. loaded=$loadedCount/$totalToLoad loadedSets=${loadedSets.size}/${setNames.size}")
        }
    }

    private suspend fun loadSetIfNeeded(setName: String) {
        if (loadedSets.containsKey(setName)) return
        val sp = soundPool ?: return

        withContext(Dispatchers.IO) {
            val assetManager = context.assets
            val setPath = "$SOUNDS_DIR/$setName"
            val weakPath = "$setPath/$WEAK_FILE"
            val strongPath = "$setPath/$STRONG_FILE"

            var weakFd: AssetFileDescriptor? = null
            var strongFd: AssetFileDescriptor? = null

            try {
                weakFd = assetManager.openFd(weakPath)
                strongFd = assetManager.openFd(strongPath)

                val weakId = sp.load(weakFd, 1).also { id ->
                    if (id != 0) loadWaiters[id] = CompletableDeferred()
                }
                val strongId = sp.load(strongFd, 1).also { id ->
                    if (id != 0) loadWaiters[id] = CompletableDeferred()
                }

                if (weakId == 0 || strongId == 0) {
                    throw IllegalStateException("Invalid sound IDs for set=$setName (weak=$weakId strong=$strongId)")
                }

                // SoundPool 로딩 완료까지 대기 (백그라운드에서만!)
                loadWaiters[weakId]?.await()
                loadWaiters[strongId]?.await()

                loadedSets[setName] = SoundSet(setName, weakId, strongId)
                Log.d(TAG, "Loaded sound set: $setName")
            } finally {
                try { weakFd?.close() } catch (_: Exception) {}
                try { strongFd?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 내부 디코더 워밍업: 볼륨 0으로 한 번씩 재생.
     * (세트가 로드된 뒤에만 의미가 있음)
     */
    private fun warmUp(setName: String) {
        val sp = soundPool ?: return
        val set = loadedSets[setName] ?: return

        sp.play(set.weakBeatId, 0f, 0f, 1, 0, 1f)
        sp.play(set.strongBeatId, 0f, 0f, 1, 0, 1f)
    }

    private fun requestLoadCurrentSetIfNeeded() {
        val name = getCurrentSetName()
        if (name == "None") return
        if (loadedSets.containsKey(name)) return

        // 메인스레드에서 막지 않기 위해 비동기로 로드 요청만 던짐
        scope.launch {
            try {
                loadSetIfNeeded(name)
            } catch (e: Exception) {
                Log.e(TAG, "Lazy load failed ($name): ${e.message}")
            }
        }
    }

    fun playWeakBeat() {
        if (setNames.isEmpty()) return
        val sp = soundPool ?: return
        val name = setNames.getOrNull(currentSetIndex) ?: return

        val set = loadedSets[name]
        if (set == null) {
            requestLoadCurrentSetIfNeeded()
            return
        }
        sp.play(set.weakBeatId, 1f, 1f, 1, 0, 1f)
    }

    fun playStrongBeat() {
        if (setNames.isEmpty()) return
        val sp = soundPool ?: return
        val name = setNames.getOrNull(currentSetIndex) ?: return

        val set = loadedSets[name]
        if (set == null) {
            requestLoadCurrentSetIfNeeded()
            return
        }
        sp.play(set.strongBeatId, 1f, 1f, 1, 0, 1f)
    }

    fun nextSoundSet() {
        if (setNames.isEmpty()) return
        currentSetIndex = (currentSetIndex + 1) % setNames.size
        Log.d(TAG, "Switched to sound set: ${setNames[currentSetIndex]}")
        requestLoadCurrentSetIfNeeded()
    }

    fun getCurrentSetName(): String {
        return setNames.getOrNull(currentSetIndex) ?: "None"
    }

    fun getSoundSetCount(): Int = setNames.size

    fun release() {
        try {
            scope.cancel()
        } catch (_: Exception) {}

        try {
            soundPool?.release()
        } catch (_: Exception) {
        } finally {
            soundPool = null
            setNames = emptyList()
            loadedSets.clear()
            loadWaiters.clear()
            totalToLoad = 0
            loadedCount = 0
        }
    }
}
