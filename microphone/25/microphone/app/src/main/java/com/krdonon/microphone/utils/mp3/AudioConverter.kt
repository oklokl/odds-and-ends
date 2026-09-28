package com.krdonon.microphone.utils.mp3

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import de.sciss.jump3r.lowlevel.LameEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.sound.sampled.AudioFormat

object AudioConverter {

    private const val TAG = "AudioConverter"
    private const val TIMEOUT_US = 10_000L

    /**
     * M4A (AAC) 파일을 디코딩하여 고음질 MP3 파일로 변환합니다.
     * 순수 Java LAME 인코더를 사용하여 NDK .so 바이너리 없이 16KB 페이지 사이즈 및 모든 기기와 완벽 호환됩니다.
     *
     * @param inputM4a 입력 M4A 파일
     * @param outputMp3 출력 MP3 파일
     * @param targetBitrateKbps 목표 MP3 비트레이트 (예: 128, 256)
     * @param onProgress 진행률 콜백 (0.0f ~ 1.0f)
     * @return 성공 여부
     */
    suspend fun convertM4aToMp3(
        inputM4a: File,
        outputMp3: File,
        targetBitrateKbps: Int = 128,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!inputM4a.exists() || inputM4a.length() == 0L) {
            Log.e(TAG, "Input file does not exist or is empty: ${inputM4a.absolutePath}")
            return@withContext false
        }

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var fos: FileOutputStream? = null
        var lameEncoder: LameEncoder? = null

        try {
            extractor = MediaExtractor().apply {
                setDataSource(inputM4a.absolutePath)
            }

            // 오디오 트랙 탐색
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                Log.e(TAG, "No audio track found in: ${inputM4a.absolutePath}")
                return@withContext false
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 48000

            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            Log.d(TAG, "Input Audio: mime=$mime, sampleRate=$sampleRate, channels=$channelCount, durationUs=$durationUs")

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            // LAME 인코더 초기화
            val audioFormat = AudioFormat(
                sampleRate.toFloat(),
                16,
                channelCount,
                true,
                false
            )
            val channelMode = if (channelCount == 1) {
                LameEncoder.CHANNEL_MODE_MONO
            } else {
                LameEncoder.CHANNEL_MODE_STEREO
            }

            lameEncoder = LameEncoder(
                audioFormat,
                targetBitrateKbps,
                channelMode,
                LameEncoder.QUALITY_MIDDLE,
                false
            )

            fos = FileOutputStream(outputMp3)
            val mp3Buffer = ByteArray(65536)

            val bufferInfo = MediaCodec.BufferInfo()
            var isExtractorEOS = false
            var isDecoderEOS = false

            while (!isDecoderEOS) {
                // 1) Extractor -> Decoder Input
                if (!isExtractorEOS) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isExtractorEOS = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                decoder.queueInputBuffer(
                                    inIndex, 0, sampleSize, sampleTime, 0
                                )
                                extractor.advance()

                                if (durationUs > 0) {
                                    val progress = (sampleTime.toFloat() / durationUs.toFloat()).coerceIn(0f, 0.95f)
                                    onProgress?.invoke(progress)
                                }
                            }
                        }
                    }
                }

                // 2) Decoder Output -> LameEncoder -> File
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val pcmBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmBytes)

                        val encodedBytes = lameEncoder.encodeBuffer(
                            pcmBytes, 0, pcmBytes.size, mp3Buffer
                        )
                        if (encodedBytes > 0) {
                            fos.write(mp3Buffer, 0, encodedBytes)
                        }
                    }

                    decoder.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                }
            }

            // 인코딩 마무리 Flush
            val finishBytes = lameEncoder.encodeFinish(mp3Buffer)
            if (finishBytes > 0) {
                fos.write(mp3Buffer, 0, finishBytes)
            }
            fos.flush()

            onProgress?.invoke(1.0f)
            Log.d(TAG, "MP3 conversion completed successfully: ${outputMp3.absolutePath} (${outputMp3.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert M4A to MP3", e)
            outputMp3.delete()
            false
        } finally {
            try {
                fos?.close()
            } catch (_: Exception) {}
            try {
                lameEncoder?.close()
            } catch (_: Exception) {}
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {}
            try {
                extractor?.release()
            } catch (_: Exception) {}
        }
    }
}
