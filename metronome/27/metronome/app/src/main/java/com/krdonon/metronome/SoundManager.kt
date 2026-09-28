package com.krdonon.metronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AudioTrack 기반 메트로놈 오디오 엔진.
 *
 * 핵심 원칙:
 * - 매 박마다 SoundPool.play()를 호출하지 않습니다.
 * - 한 마디 전체 PCM을 샘플 단위로 미리 합성한 뒤 AudioTrack에 맡깁니다.
 * - 일반적인 길이의 마디는 MODE_STATIC + setLoopPoints()로 네이티브 레이어에서 무한 반복합니다.
 * - 매우 긴 마디이거나 MODE_STATIC 생성이 실패하면 MODE_STREAM으로 같은 PCM을 연속 공급합니다.
 *
 * 이 구조에서는 Java Handler의 순간 지연이나 UI/GC 부하가 개별 박의 실제 오디오 시작 시각을
 * 직접 흔들지 않으므로, "몇 박이 갑자기 몰렸다가 강박이 오는" 형태의 SoundPool 시작 지터를
 * 제거하는 데 초점을 둡니다.
 */
class SoundManager(context: Context) {

    private val appContext = context.applicationContext

    /** sounds/set0, set1 ... */
    @Volatile
    private var setNames: List<String> = emptyList()

    @Volatile
    private var currentSetIndex = 0

    private data class PcmSample(
        val samples: ShortArray,
        val frameCount: Int
    )

    private data class SoundSet(
        val name: String,
        val weak: PcmSample,
        val strong: PcmSample
    )

    private val loadedSets = ConcurrentHashMap<String, SoundSet>()
    private val loadLock = Any()

    private val playbackLock = Any()
    private var activeTrack: AudioTrack? = null
    private var streamThread: Thread? = null
    private var playbackGeneration: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SoundManager"
        private const val SOUNDS_DIR = "sounds"
        private const val WEAK_PCM_FILE = "weak_pcm.wav"
        private const val STRONG_PCM_FILE = "strong_pcm.wav"

        // 모든 보조 PCM 파일은 빌드에 포함된 48 kHz / stereo / PCM16 WAV입니다.
        private const val OUTPUT_SAMPLE_RATE = 48_000
        private const val OUTPUT_CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_FRAME = OUTPUT_CHANNELS * BYTES_PER_SAMPLE

        // MODE_STATIC은 가장 안정적이지만 극단적으로 긴 마디에서는 큰 네이티브 버퍼가 필요합니다.
        // 8 MiB를 넘으면 MODE_STREAM으로 전환합니다.
        private const val MAX_STATIC_BYTES = 8 * 1024 * 1024

        // 스트리밍 폴백은 타이머로 박을 쏘지 않고 이미 합성된 PCM을 연속 write합니다.
        private const val STREAM_BUFFER_MILLIS = 120
    }

    init {
        scanSoundSets()
    }

    private fun scanSoundSets() {
        setNames = try {
            (appContext.assets.list(SOUNDS_DIR) ?: emptyArray())
                .filter { it.startsWith("set") }
                .sortedWith(compareBy { name ->
                    name.removePrefix("set").toIntOrNull() ?: Int.MAX_VALUE
                })
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning sounds", e)
            emptyList()
        }
    }

    /** 현재 세트를 미리 메모리에 올립니다. */
    fun preloadAsync(initialSetName: String = "set0") {
        scope.launch {
            val setName = when {
                setNames.contains(initialSetName) -> initialSetName
                else -> setNames.firstOrNull()
            } ?: return@launch

            try {
                loadSetIfNeeded(setName)
            } catch (e: Exception) {
                Log.e(TAG, "PCM preload failed for $setName", e)
            }
        }
    }

    private fun loadSetIfNeeded(setName: String): SoundSet? {
        loadedSets[setName]?.let { return it }

        synchronized(loadLock) {
            loadedSets[setName]?.let { return it }

            return try {
                val weak = readPcmWav("$SOUNDS_DIR/$setName/$WEAK_PCM_FILE")
                val strong = readPcmWav("$SOUNDS_DIR/$setName/$STRONG_PCM_FILE")
                SoundSet(setName, weak, strong).also { loadedSets[setName] = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load PCM set $setName", e)
                null
            }
        }
    }

    /**
     * 표준 PCM16 little-endian WAV를 읽습니다.
     * 생성된 보조 WAV는 모두 48 kHz / stereo로 통일되어 있습니다.
     */
    private fun readPcmWav(assetPath: String): PcmSample {
        val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
        require(bytes.size >= 44) { "Invalid WAV: $assetPath" }
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "Not a RIFF/WAVE file: $assetPath"
        }

        var offset = 12
        var audioFormat = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataSize = -1

        while (offset + 8 <= bytes.size) {
            val chunkId = ascii(bytes, offset, 4)
            val chunkSize = readIntLE(bytes, offset + 4)
            val chunkData = offset + 8
            if (chunkSize < 0 || chunkData + chunkSize > bytes.size) break

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16) { "Invalid fmt chunk: $assetPath" }
                    audioFormat = readShortLE(bytes, chunkData).toInt() and 0xFFFF
                    channels = readShortLE(bytes, chunkData + 2).toInt() and 0xFFFF
                    sampleRate = readIntLE(bytes, chunkData + 4)
                    bitsPerSample = readShortLE(bytes, chunkData + 14).toInt() and 0xFFFF
                }

                "data" -> {
                    dataOffset = chunkData
                    dataSize = chunkSize
                    break
                }
            }

            // RIFF chunk는 2-byte alignment를 사용합니다.
            offset = chunkData + chunkSize + (chunkSize and 1)
        }

        require(audioFormat == 1) { "WAV must be PCM: $assetPath format=$audioFormat" }
        require(channels == OUTPUT_CHANNELS) { "WAV must be stereo: $assetPath channels=$channels" }
        require(sampleRate == OUTPUT_SAMPLE_RATE) { "WAV must be 48kHz: $assetPath rate=$sampleRate" }
        require(bitsPerSample == 16) { "WAV must be PCM16: $assetPath bits=$bitsPerSample" }
        require(dataOffset >= 0 && dataSize > 0 && dataSize % BYTES_PER_FRAME == 0) {
            "Invalid WAV data chunk: $assetPath"
        }

        val sampleCount = dataSize / BYTES_PER_SAMPLE
        val samples = ShortArray(sampleCount)
        ByteBuffer.wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)

        return PcmSample(
            samples = samples,
            frameCount = sampleCount / OUTPUT_CHANNELS
        )
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Short {
        val value = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return value.toShort()
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * 현재 설정으로 한 마디를 샘플 단위로 정확히 합성합니다.
     * 각 박 위치는 Double 기준 누적 위치를 roundToLong() 해 평균 BPM 오차를 최소화합니다.
     */
    private fun buildMeasure(
        set: SoundSet,
        bpm: Int,
        beatUnit: Int,
        beatsPerMeasure: Int
    ): Pair<ShortArray, Int> {
        val safeBpm = bpm.coerceIn(1, 1000)
        val safeUnit = beatUnit.coerceIn(1, 16)
        val beats = beatsPerMeasure.coerceIn(1, 16)

        val framesPerBeatExact = OUTPUT_SAMPLE_RATE.toDouble() * 60.0 / safeBpm * (4.0 / safeUnit)
        val measureFrames = kotlin.math.round(framesPerBeatExact * beats)
            .toLong()
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong() / OUTPUT_CHANNELS)
            .toInt()

        val mixed = ShortArray(measureFrames * OUTPUT_CHANNELS)

        for (beat in 0 until beats) {
            val sample = if (beat == 0) set.strong else set.weak
            val startFrame = kotlin.math.round(framesPerBeatExact * beat)
                .toLong()
                .coerceIn(0L, (measureFrames - 1).toLong())
                .toInt()

            mixSampleCircular(mixed, measureFrames, sample, startFrame)
        }

        return mixed to measureFrames
    }

    /**
     * 샘플의 꼬리가 다음 박/다음 마디까지 자연스럽게 이어지도록 원형 버퍼에 합성합니다.
     * 겹침 시 saturating add를 사용해 정수 overflow만 방지합니다.
     */
    private fun mixSampleCircular(
        destination: ShortArray,
        destinationFrames: Int,
        source: PcmSample,
        startFrame: Int
    ) {
        if (destinationFrames <= 0 || source.frameCount <= 0) return

        for (srcFrame in 0 until source.frameCount) {
            val dstFrame = (startFrame + srcFrame) % destinationFrames
            val srcBase = srcFrame * OUTPUT_CHANNELS
            val dstBase = dstFrame * OUTPUT_CHANNELS

            for (channel in 0 until OUTPUT_CHANNELS) {
                val sum = destination[dstBase + channel].toInt() +
                        source.samples[srcBase + channel].toInt()
                destination[dstBase + channel] = sum.coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                ).toShort()
            }
        }
    }

    /**
     * 현재 사운드 세트로 샘플 정확도의 반복 오디오를 시작/재구성합니다.
     */
    @Synchronized
    fun startLoop(bpm: Int, beatUnit: Int, beatsPerMeasure: Int): Boolean {
        val setName = getCurrentSetName()
        if (setName == "None") return false

        val set = loadSetIfNeeded(setName) ?: return false
        val (measure, frameCount) = try {
            buildMeasure(set, bpm, beatUnit, beatsPerMeasure)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to build measure", e)
            return false
        }

        // 새 트랙이 준비되기 전까지 기존 트랙은 유지합니다.
        val staticTrack = if (measure.size * BYTES_PER_SAMPLE <= MAX_STATIC_BYTES) {
            createStaticLoopTrack(measure, frameCount)
        } else {
            null
        }

        if (staticTrack != null) {
            replacePlayback(staticTrack, null)
            return true
        }

        // 큰 마디 또는 기기별 MODE_STATIC 제약 시 스트리밍 폴백.
        return startStreamingLoop(measure)
    }

    private fun audioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun audioFormat(): AudioFormat {
        return AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(OUTPUT_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
    }

    private fun createStaticLoopTrack(measure: ShortArray, frameCount: Int): AudioTrack? {
        var track: AudioTrack? = null
        return try {
            val bufferBytes = measure.size * BYTES_PER_SAMPLE
            track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes())
                .setAudioFormat(audioFormat())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack static init failed")
            }

            val written = track.write(measure, 0, measure.size, AudioTrack.WRITE_BLOCKING)
            if (written != measure.size) {
                throw IllegalStateException("AudioTrack static short write: $written/${measure.size}")
            }

            val loopResult = track.setLoopPoints(0, frameCount, -1)
            if (loopResult != AudioTrack.SUCCESS) {
                throw IllegalStateException("setLoopPoints failed: $loopResult")
            }

            track.play()
            track
        } catch (e: Throwable) {
            Log.w(TAG, "MODE_STATIC unavailable; falling back to MODE_STREAM", e)
            try {
                track?.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun startStreamingLoop(measure: ShortArray): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val desiredBuffer = OUTPUT_SAMPLE_RATE * BYTES_PER_FRAME * STREAM_BUFFER_MILLIS / 1000
        val bufferBytes = maxOf(minBuffer * 2, desiredBuffer)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes())
                .setAudioFormat(audioFormat())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Throwable) {
            Log.e(TAG, "MODE_STREAM init failed", e)
            return false
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            try { track.release() } catch (_: Exception) {}
            return false
        }

        var generation = 0L
        lateinit var thread: Thread
        synchronized(playbackLock) {
            playbackGeneration++
            generation = playbackGeneration
            thread = Thread(writer@{
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    track.play()

                    while (!Thread.currentThread().isInterrupted && isGenerationActive(generation, track)) {
                        var offset = 0
                        while (offset < measure.size &&
                            !Thread.currentThread().isInterrupted &&
                            isGenerationActive(generation, track)
                        ) {
                            val written = track.write(
                                measure,
                                offset,
                                measure.size - offset,
                                AudioTrack.WRITE_BLOCKING
                            )
                            if (written <= 0) return@writer
                            offset += written
                        }
                    }
                } catch (_: Throwable) {
                    // stop/release와 동시에 blocking write가 풀릴 때 발생할 수 있으므로 종료 시 무시합니다.
                }
            }, "MetronomePcmWriter")
        }

        replacePlayback(track, thread, generation)
        thread.start()
        return true
    }

    private fun isGenerationActive(generation: Long, track: AudioTrack): Boolean {
        synchronized(playbackLock) {
            return generation == playbackGeneration && activeTrack === track
        }
    }

    private fun replacePlayback(
        newTrack: AudioTrack,
        newThread: Thread?,
        generationAlreadyAllocated: Long? = null
    ) {
        var oldTrack: AudioTrack? = null
        var oldThread: Thread? = null

        synchronized(playbackLock) {
            oldTrack = activeTrack
            oldThread = streamThread

            if (generationAlreadyAllocated == null) {
                playbackGeneration++
            }

            activeTrack = newTrack
            streamThread = newThread
        }

        oldThread?.interrupt()
        if (oldTrack !== newTrack) {
            try { oldTrack?.pause() } catch (_: Exception) {}
            try { oldTrack?.flush() } catch (_: Exception) {}
            try { oldTrack?.stop() } catch (_: Exception) {}
            try { oldTrack?.release() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun stopLoop() {
        var track: AudioTrack? = null
        var thread: Thread? = null

        synchronized(playbackLock) {
            playbackGeneration++
            track = activeTrack
            thread = streamThread
            activeTrack = null
            streamThread = null
        }

        thread?.interrupt()
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
    }

    fun nextSoundSet() {
        if (setNames.isEmpty()) return
        currentSetIndex = (currentSetIndex + 1) % setNames.size
        preloadAsync(getCurrentSetName())
    }

    fun setSoundSetIndex(index: Int) {
        if (setNames.isEmpty()) return
        currentSetIndex = index.coerceIn(0, setNames.lastIndex)
        preloadAsync(getCurrentSetName())
    }

    fun getCurrentSetIndex(): Int = currentSetIndex

    fun getCurrentSetName(): String = setNames.getOrNull(currentSetIndex) ?: "None"

    fun getSetNames(): List<String> = setNames.toList()

    fun getSoundSetCount(): Int = setNames.size

    /** 메모리 압박 시 현재 세트만 남깁니다. */
    fun trimMemory() {
        val current = getCurrentSetName()
        loadedSets.keys
            .filter { it != current }
            .forEach { loadedSets.remove(it) }
    }

    fun release() {
        stopLoop()
        try {
            scope.cancel()
        } catch (_: Exception) {
        }
        loadedSets.clear()
        setNames = emptyList()
    }
}
