package com.krdonon.metronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
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

    private data class BuiltMeasure(
        val samples: ShortArray,
        val frameCount: Int,
        val beatStartFrames: IntArray,
        val beatsPerMeasure: Int
    )

    private class ActiveAudioTimeline(
        val track: AudioTrack,
        val beatsPerMeasure: Int,
        val measureFrames: Int,
        val beatStartFrames: IntArray,
        @Volatile var playStartNanoTime: Long
    ) {
        val audioTimestamp = AudioTimestamp()
        var lastTimestampQueryNano: Long = 0L
        var hasTimestamp: Boolean = false
        var timestampConfirmedAdvancing: Boolean = false
        var firstSeenFrame: Long = Long.MIN_VALUE
        var firstSeenNano: Long = Long.MIN_VALUE
        var lastRawFrame32: Long = 0L
        var unwrapOffset: Long = 0L
        var hasPlaybackHead: Boolean = false
        var anchorFrame: Double = 0.0
        var anchorNanoTime: Long = playStartNanoTime
        var timestampFailedCount: Int = 0
    }

    private val loadedSets = ConcurrentHashMap<String, SoundSet>()
    private val loadLock = Any()

    private val playbackLock = Any()
    private var activeTrack: AudioTrack? = null
    private var activeTimeline: ActiveAudioTimeline? = null
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

        // AudioTrack.getTimestamp() 쿼리 주기 (SDK 권장 사항 준수: 워밍업 시 짧게, 안정 후 저빈도)
        private const val TIMESTAMP_POLL_WARMUP_NANOS = 25_000_000L       // 25 ms
        private const val TIMESTAMP_REFRESH_STABLE_NANOS = 2_000_000_000L // 2 s
        private const val TIMESTAMP_RETRY_UNSUPPORTED_NANOS = 5_000_000_000L // 5 s
        private const val MAX_WARMUP_FAILS = 40

        internal fun computeBeatFrameState(
            currentFrame: Double,
            beatsPerMeasure: Int,
            measureFrames: Int,
            beatStartFrames: IntArray
        ): BeatFrameState {
            if (currentFrame == Double.NEGATIVE_INFINITY || currentFrame < 0.0) {
                return BeatFrameState(
                    isPlaying = true,
                    beatsPerMeasure = beatsPerMeasure,
                    currentBeat = 0,
                    beatProgress = 0f,
                    measureProgress = 0f,
                    isPreStart = true
                )
            }

            val measureLen = measureFrames.toDouble()
            var frameInMeasure = currentFrame % measureLen
            if (frameInMeasure < 0.0) frameInMeasure += measureLen

            var beatIdx = 0
            for (b in beatsPerMeasure - 1 downTo 0) {
                if (frameInMeasure >= beatStartFrames[b]) {
                    beatIdx = b
                    break
                }
            }

            val beatStart = beatStartFrames[beatIdx].toDouble()
            val beatEnd = if (beatIdx + 1 < beatsPerMeasure) beatStartFrames[beatIdx + 1].toDouble() else measureLen
            val beatDur = (beatEnd - beatStart).coerceAtLeast(1.0)
            val beatProgress = ((frameInMeasure - beatStart) / beatDur).toFloat().coerceIn(0f, 1f)
            val measureProgress = (frameInMeasure / measureLen).toFloat().coerceIn(0f, 1f)

            return BeatFrameState(
                isPlaying = true,
                beatsPerMeasure = beatsPerMeasure,
                currentBeat = beatIdx,
                beatProgress = beatProgress,
                measureProgress = measureProgress,
                isPreStart = false
            )
        }
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
    ): BuiltMeasure {
        val safeBpm = bpm.coerceIn(1, 1000)
        val safeUnit = beatUnit.coerceIn(1, 16)
        val beats = beatsPerMeasure.coerceIn(1, 16)

        val framesPerBeatExact = OUTPUT_SAMPLE_RATE.toDouble() * 60.0 / safeBpm * (4.0 / safeUnit)
        val measureFrames = kotlin.math.round(framesPerBeatExact * beats)
            .toLong()
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong() / OUTPUT_CHANNELS)
            .toInt()

        val acc = IntArray(measureFrames * OUTPUT_CHANNELS)
        val beatStartFrames = IntArray(beats)

        for (beat in 0 until beats) {
            val sample = if (beat == 0) set.strong else set.weak
            val startFrame = kotlin.math.round(framesPerBeatExact * beat)
                .toLong()
                .coerceIn(0L, (measureFrames - 1).toLong())
                .toInt()
            beatStartFrames[beat] = startFrame

            mixSampleCircular(acc, measureFrames, sample, startFrame)
        }

        val mixed = ShortArray(acc.size)
        for (i in acc.indices) {
            mixed[i] = acc[i].coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
            ).toShort()
        }

        return BuiltMeasure(
            samples = mixed,
            frameCount = measureFrames,
            beatStartFrames = beatStartFrames,
            beatsPerMeasure = beats
        )
    }

    /**
     * 샘플의 꼬리가 다음 박/다음 마디까지 자연스럽게 이어지도록 원형 버퍼에 합성합니다.
     * 누적은 IntArray로 처리해 다중 tail 중첩 시 중간 왜곡 없이 최종 단계에서 클램핑합니다.
     */
    private fun mixSampleCircular(
        destination: IntArray,
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
                destination[dstBase + channel] += source.samples[srcBase + channel].toInt()
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
        val built = try {
            buildMeasure(set, bpm, beatUnit, beatsPerMeasure)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to build measure", e)
            return false
        }

        // 새 트랙이 준비되기 전까지 기존 트랙은 유지합니다.
        val staticTrackAndStart = if (built.samples.size * BYTES_PER_SAMPLE <= MAX_STATIC_BYTES) {
            createStaticLoopTrack(built.samples, built.frameCount)
        } else {
            null
        }

        if (staticTrackAndStart != null) {
            val (staticTrack, playStartNano) = staticTrackAndStart
            val timeline = ActiveAudioTimeline(
                track = staticTrack,
                beatsPerMeasure = built.beatsPerMeasure,
                measureFrames = built.frameCount,
                beatStartFrames = built.beatStartFrames,
                playStartNanoTime = playStartNano
            )
            replacePlayback(staticTrack, null, timeline)
            return true
        }

        // 큰 마디 또는 기기별 MODE_STATIC 제약 시 스트리밍 폴백.
        return startStreamingLoop(built)
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

    private fun createStaticLoopTrack(measure: ShortArray, frameCount: Int): Pair<AudioTrack, Long>? {
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

            val playStartNano = System.nanoTime()
            track.play()
            track to playStartNano
        } catch (e: Throwable) {
            Log.w(TAG, "MODE_STATIC unavailable; falling back to MODE_STREAM", e)
            try {
                track?.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun startStreamingLoop(built: BuiltMeasure): Boolean {
        val measure = built.samples
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

        val timeline = ActiveAudioTimeline(
            track = track,
            beatsPerMeasure = built.beatsPerMeasure,
            measureFrames = built.frameCount,
            beatStartFrames = built.beatStartFrames,
            playStartNanoTime = System.nanoTime()
        )

        var generation = 0L
        lateinit var thread: Thread
        synchronized(playbackLock) {
            playbackGeneration++
            generation = playbackGeneration
            thread = Thread(writer@{
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    timeline.playStartNanoTime = System.nanoTime()
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

        replacePlayback(track, thread, timeline, generation)
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
        newTimeline: ActiveAudioTimeline,
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
            activeTimeline = newTimeline
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
            activeTimeline = null
            streamThread = null
        }

        thread?.interrupt()
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
    }

    /**
     * AudioTrack의 하드웨어 출력 타임스탬프를 기반으로 현재 스피커에서 재생 중인
     * 정확한 박자 위치와 진행도(0f..1f)를 계산합니다.
     */
    fun getBeatFrameState(nowNanos: Long = System.nanoTime()): BeatFrameState? {
        val currentFrame: Double
        val beats: Int
        val measureFrames: Int
        val beatStarts: IntArray

        synchronized(playbackLock) {
            val tl = activeTimeline ?: return null
            if (tl.track.playState != AudioTrack.PLAYSTATE_PLAYING) return null

            currentFrame = sampleCurrentFrameLocked(tl, nowNanos)
            beats = tl.beatsPerMeasure
            measureFrames = tl.measureFrames
            beatStarts = tl.beatStartFrames
        }

        return computeBeatFrameState(
            currentFrame = currentFrame,
            beatsPerMeasure = beats,
            measureFrames = measureFrames,
            beatStartFrames = beatStarts
        )
    }

    /**
     * AudioTrack 기준 다음 강박(1박)이 스피커에 도달할 때까지 남은 시간(ns)을 반환합니다.
     * 플래시 등 부가 시각 효과를 AudioTrack 클록에 위상 고정할 때 사용합니다.
     */
    fun getNextStrongBeatDelayNanos(nowNanos: Long = System.nanoTime()): Long? {
        val currentFrame: Double
        val measureFrames: Int

        synchronized(playbackLock) {
            val tl = activeTimeline ?: return null
            if (tl.track.playState != AudioTrack.PLAYSTATE_PLAYING) return null
            if (!tl.timestampConfirmedAdvancing && !tl.hasTimestamp && !tl.hasPlaybackHead) return null

            currentFrame = sampleCurrentFrameLocked(tl, nowNanos)
            measureFrames = tl.measureFrames
        }

        if (currentFrame == Double.NEGATIVE_INFINITY) return null
        if (currentFrame < 0.0) {
            return ((-currentFrame / OUTPUT_SAMPLE_RATE.toDouble()) * 1_000_000_000.0).toLong()
        }

        val measureLen = measureFrames.toDouble()
        val frameInMeasure = currentFrame % measureLen
        val framesRemaining = measureLen - frameInMeasure
        return ((framesRemaining / OUTPUT_SAMPLE_RATE.toDouble()) * 1_000_000_000.0).toLong()
    }

    private fun sampleCurrentFrameLocked(tl: ActiveAudioTimeline, nowNanos: Long): Double {
        val queryInterval = when {
            tl.timestampConfirmedAdvancing -> TIMESTAMP_REFRESH_STABLE_NANOS
            tl.timestampFailedCount < MAX_WARMUP_FAILS -> TIMESTAMP_POLL_WARMUP_NANOS
            else -> TIMESTAMP_RETRY_UNSUPPORTED_NANOS
        }

        if (tl.lastTimestampQueryNano == 0L || (nowNanos - tl.lastTimestampQueryNano) >= queryInterval) {
            tl.lastTimestampQueryNano = nowNanos
            val ok = runCatching { tl.track.getTimestamp(tl.audioTimestamp) }.getOrDefault(false)
            if (ok && tl.audioTimestamp.nanoTime > 0L && tl.audioTimestamp.framePosition >= 0L) {
                val raw32 = tl.audioTimestamp.framePosition and 0xFFFFFFFFL
                val tsNano = tl.audioTimestamp.nanoTime
                if (tl.hasTimestamp && raw32 < tl.lastRawFrame32 && (tl.lastRawFrame32 - raw32) > 0x80000000L) {
                    tl.unwrapOffset += 0x1_0000_0000L
                }
                val frame64 = tl.unwrapOffset + raw32
                tl.lastRawFrame32 = raw32

                if (!tl.hasTimestamp) {
                    tl.hasTimestamp = true
                    tl.firstSeenFrame = frame64
                    tl.firstSeenNano = tsNano
                } else if (frame64 != tl.firstSeenFrame || tsNano != tl.firstSeenNano) {
                    tl.timestampConfirmedAdvancing = true
                }

                tl.anchorFrame = frame64.toDouble()
                tl.anchorNanoTime = tsNano
                tl.timestampFailedCount = 0
            } else {
                tl.timestampFailedCount++
                if (!tl.timestampConfirmedAdvancing && !tl.hasTimestamp) {
                    val head = runCatching { tl.track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }
                        .getOrDefault(0L)
                    if (head > 0L) {
                        tl.hasPlaybackHead = true
                        tl.anchorFrame = head.toDouble()
                        tl.anchorNanoTime = nowNanos
                    }
                }
            }
        }

        if (!tl.hasTimestamp && !tl.hasPlaybackHead) {
            val elapsed = nowNanos - tl.playStartNanoTime
            return if (elapsed > 250_000_000L) {
                (elapsed * OUTPUT_SAMPLE_RATE.toDouble()) / 1_000_000_000.0
            } else {
                Double.NEGATIVE_INFINITY
            }
        }

        return tl.anchorFrame + ((nowNanos - tl.anchorNanoTime) * OUTPUT_SAMPLE_RATE.toDouble()) / 1_000_000_000.0
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
