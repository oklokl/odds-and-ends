package com.krdonon.metronome

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SoundPool 기반 메트로놈 사운드 관리자.
 *
 * 메모리 정책:
 * - 앱 시작 시 모든 사운드를 디코딩하지 않고 현재 세트만 준비합니다.
 * - 사용한 세트는 최대 [MAX_CACHED_SETS]개까지만 메모리에 유지합니다.
 * - 시스템 메모리 압박 콜백이 오면 현재 세트 외의 샘플을 즉시 해제합니다.
 */
class SoundManager(context: Context) {

    private val appContext = context.applicationContext
    private var soundPool: SoundPool? = null

    /** sounds/set0, set1 ... 처럼 사용 가능한 세트 이름 */
    private var setNames: List<String> = emptyList()

    /** 실제로 로드 완료된 세트만 보관 */
    private val loadedSets = ConcurrentHashMap<String, SoundSet>()

    /** 중복 로드와 SoundPool.load() 경쟁을 막기 위한 직렬화 */
    private val loadMutex = Mutex()

    /** sampleId -> load 완료 대기 */
    private val loadWaiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    /** waiter 등록 전에 매우 빠르게 도착한 SoundPool 콜백을 임시 보관 */
    private val earlyLoadResults = ConcurrentHashMap<Int, Int>()

    /** LRU 순서. 앞이 오래된 세트, 뒤가 최근 사용 세트 */
    private val cacheLock = Any()
    private val usageOrder = ArrayDeque<String>()

    @Volatile
    private var currentSetIndex = 0

    // 메트로놈 클릭은 서로 겹치지 않게 한 스트림만 유지합니다.
    // 긴 strong/weak 샘플이 다음 박까지 남으면 실제 BPM이 일정해도 박이 밀리거나 빨라진 듯 들릴 수 있습니다.
    @Volatile
    private var activeStreamId: Int = 0

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

        // 전 세트를 메모리에 유지하지 않고 최근 세트만 작게 캐시합니다.
        private const val MAX_CACHED_SETS = 3
    }

    init {
        initializeSoundPool()
        scanSoundSets()
    }

    private fun initializeSoundPool() {
        if (soundPool != null) return

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(audioAttributes)
            .build().apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    val waiter = loadWaiters.remove(sampleId)
                    if (waiter != null) {
                        completeLoadWaiter(waiter, sampleId, status)
                    } else {
                        // sp.load() 직후 waiter를 넣기 전에 콜백이 도착할 수 있는 경쟁 조건 방어
                        earlyLoadResults[sampleId] = status
                    }
                }
            }
    }

    private fun registerLoadWaiter(sampleId: Int): CompletableDeferred<Unit> {
        val waiter = CompletableDeferred<Unit>()
        loadWaiters[sampleId] = waiter

        // 콜백이 waiter 등록보다 먼저 도착했다면 여기서 즉시 반영합니다.
        earlyLoadResults.remove(sampleId)?.let { status ->
            loadWaiters.remove(sampleId, waiter)
            completeLoadWaiter(waiter, sampleId, status)
        }
        return waiter
    }

    private fun completeLoadWaiter(
        waiter: CompletableDeferred<Unit>,
        sampleId: Int,
        status: Int
    ) {
        if (status == 0 && sampleId != 0) {
            waiter.complete(Unit)
        } else {
            waiter.completeExceptionally(
                IllegalStateException(
                    "SoundPool load failed: id=$sampleId status=$status"
                )
            )
        }
    }

    /** assets/sounds 아래 set* 디렉터리 목록만 스캔합니다. */
    private fun scanSoundSets() {
        try {
            val sets = appContext.assets.list(SOUNDS_DIR) ?: emptyArray()
            setNames = sets
                .filter { it.startsWith("set") }
                .sortedWith(compareBy { name ->
                    name.removePrefix("set").toIntOrNull() ?: Int.MAX_VALUE
                })

            if (setNames.isEmpty()) {
                Log.w(TAG, "No sound sets found under assets/$SOUNDS_DIR")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning sounds: ${e.message}")
            setNames = emptyList()
        }
    }

    /**
     * 첫 사용에 필요한 한 세트만 백그라운드에서 준비합니다.
     * 과거처럼 모든 세트를 선로드하지 않아 SoundPool의 디코딩 메모리를 제한합니다.
     */
    fun preloadAsync(initialSetName: String = "set0") {
        scope.launch {
            val first = when {
                setNames.contains(initialSetName) -> initialSetName
                else -> setNames.firstOrNull()
            } ?: return@launch

            try {
                loadSetIfNeeded(first)
                warmUp(first)
            } catch (e: Exception) {
                Log.e(TAG, "Initial set preload failed ($first): ${e.message}")
            }
        }
    }

    private suspend fun loadSetIfNeeded(setName: String) {
        if (loadedSets.containsKey(setName)) {
            markSetUsed(setName)
            return
        }

        loadMutex.withLock {
            if (loadedSets.containsKey(setName)) {
                markSetUsed(setName)
                return@withLock
            }

            val sp = soundPool ?: return@withLock

            withContext(Dispatchers.IO) {
                val setPath = "$SOUNDS_DIR/$setName"
                val weakPath = "$setPath/$WEAK_FILE"
                val strongPath = "$setPath/$STRONG_FILE"

                var weakFd: AssetFileDescriptor? = null
                var strongFd: AssetFileDescriptor? = null
                var weakId = 0
                var strongId = 0

                try {
                    weakFd = appContext.assets.openFd(weakPath)
                    strongFd = appContext.assets.openFd(strongPath)

                    weakId = sp.load(weakFd, 1)
                    strongId = sp.load(strongFd, 1)

                    if (weakId == 0 || strongId == 0) {
                        throw IllegalStateException(
                            "Invalid sound IDs for set=$setName (weak=$weakId strong=$strongId)"
                        )
                    }

                    val weakWaiter = registerLoadWaiter(weakId)
                    val strongWaiter = registerLoadWaiter(strongId)

                    weakWaiter.await()
                    strongWaiter.await()

                    loadedSets[setName] = SoundSet(setName, weakId, strongId)
                    markSetUsed(setName)
                    enforceCacheLimit(protectedSetName = setName)
                } catch (e: Exception) {
                    loadWaiters.remove(weakId)?.cancel()
                    loadWaiters.remove(strongId)?.cancel()
                    if (weakId != 0) sp.unload(weakId)
                    if (strongId != 0) sp.unload(strongId)
                    throw e
                } finally {
                    try { weakFd?.close() } catch (_: Exception) {}
                    try { strongFd?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    /** 내부 디코더 워밍업: 볼륨 0으로 현재 세트만 한 번씩 재생합니다. */
    private fun warmUp(setName: String) {
        val sp = soundPool ?: return
        val set = loadedSets[setName] ?: return
        sp.play(set.weakBeatId, 0f, 0f, 1, 0, 1f)
        sp.play(set.strongBeatId, 0f, 0f, 1, 0, 1f)
    }

    private fun requestLoadCurrentSetIfNeeded() {
        val name = getCurrentSetName()
        if (name == "None" || loadedSets.containsKey(name)) return

        scope.launch {
            try {
                loadSetIfNeeded(name)
            } catch (e: Exception) {
                Log.e(TAG, "Lazy load failed ($name): ${e.message}")
            }
        }
    }

    private fun playBeatSample(strong: Boolean) {
        val sp = soundPool ?: return
        val name = setNames.getOrNull(currentSetIndex) ?: return
        val set = loadedSets[name]

        if (set == null) {
            requestLoadCurrentSetIfNeeded()
            return
        }

        markSetUsed(name)

        // 이전 클릭의 긴 잔향을 다음 박 경계에서 끊어 박과 박이 겹치지 않게 합니다.
        // 특히 7/8, 120 BPM이면 한 박이 250ms인데 set0 strong은 약 456ms라
        // 겹침을 허용하면 다음 약박 위에 강박 꼬리가 약 200ms 남습니다.
        val previousStream = activeStreamId
        val sampleId = if (strong) set.strongBeatId else set.weakBeatId

        // 새 클릭의 play 요청을 먼저 넣어 박 시작 시각을 최우선으로 보장한 뒤
        // 이전 잔향을 즉시 정리합니다.
        val newStream = sp.play(sampleId, 1f, 1f, 1, 0, 1f)
        if (newStream != 0) {
            activeStreamId = newStream
            if (previousStream != 0 && previousStream != newStream) {
                try {
                    sp.stop(previousStream)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun playWeakBeat() = playBeatSample(strong = false)

    fun playStrongBeat() = playBeatSample(strong = true)

    fun stopActiveBeat() {
        val streamId = activeStreamId
        if (streamId == 0) return

        try {
            soundPool?.stop(streamId)
        } catch (_: Exception) {
        } finally {
            activeStreamId = 0
        }
    }

    fun nextSoundSet() {
        if (setNames.isEmpty()) return
        currentSetIndex = (currentSetIndex + 1) % setNames.size
        requestLoadCurrentSetIfNeeded()
    }

    fun getCurrentSetName(): String = setNames.getOrNull(currentSetIndex) ?: "None"

    fun setSoundSetIndex(index: Int) {
        if (setNames.isEmpty()) return
        currentSetIndex = index.coerceIn(0, setNames.lastIndex)
        requestLoadCurrentSetIfNeeded()
    }

    fun getCurrentSetIndex(): Int = currentSetIndex

    fun getSetNames(): List<String> = setNames.toList()

    fun getSoundSetCount(): Int = setNames.size

    /**
     * 시스템이 메모리 회수를 요청하면 현재 선택 세트 외의 디코딩 샘플을 해제합니다.
     * 현재 세트는 유지해 백그라운드 메트로놈 재생 안정성을 보존합니다.
     */
    fun trimMemory() {
        val currentName = getCurrentSetName()
        val sp = soundPool ?: return

        synchronized(cacheLock) {
            val namesToRemove = loadedSets.keys.filter { it != currentName }
            for (name in namesToRemove) {
                loadedSets.remove(name)?.let { set ->
                    sp.unload(set.weakBeatId)
                    sp.unload(set.strongBeatId)
                }
                usageOrder.remove(name)
            }
        }
    }

    private fun markSetUsed(setName: String) {
        synchronized(cacheLock) {
            usageOrder.remove(setName)
            usageOrder.addLast(setName)
        }
    }

    private fun enforceCacheLimit(protectedSetName: String) {
        val sp = soundPool ?: return

        synchronized(cacheLock) {
            while (loadedSets.size > MAX_CACHED_SETS) {
                val candidate = usageOrder.firstOrNull { it != protectedSetName } ?: break
                usageOrder.remove(candidate)
                loadedSets.remove(candidate)?.let { set ->
                    sp.unload(set.weakBeatId)
                    sp.unload(set.strongBeatId)
                }
            }
        }
    }

    fun release() {
        try {
            scope.cancel()
        } catch (_: Exception) {
        }

        loadWaiters.values.forEach { it.cancel() }
        loadWaiters.clear()
        earlyLoadResults.clear()

        synchronized(cacheLock) {
            usageOrder.clear()
            loadedSets.clear()
        }

        try {
            activeStreamId.takeIf { it != 0 }?.let { streamId ->
                soundPool?.stop(streamId)
            }
            activeStreamId = 0
            soundPool?.release()
        } catch (_: Exception) {
        } finally {
            soundPool = null
            setNames = emptyList()
        }
    }
}
