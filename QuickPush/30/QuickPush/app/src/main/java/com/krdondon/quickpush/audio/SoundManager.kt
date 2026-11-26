package com.krdondon.quickpush.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.krdondon.quickpush.model.SoundStyle
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.HashSet
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SoundManager(private val context: Context) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var soundPool: SoundPool? = null
  @Volatile private var isReleased = false

  // 개별 사운드 ID별 로딩 완료 상태를 스레드 안전하게 정확히 추적
  private val loadedSoundIds: MutableSet<Int> = Collections.synchronizedSet(HashSet<Int>())

  // 4중 라운드 로빈(Round-Robin) 풀링: 6종 사운드 스타일별 독립 채널 분산
  private val popSoundChannels: Map<SoundStyle, IntArray> =
    SoundStyle.entries.associateWith { IntArray(4) }
  private val popLock = Any()
  private var popIndex = 0

  private var soundLevelUp = 0
  private var soundSuccess = 0
  private var soundFailure = 0
  private var soundTimerTick = 0
  private var soundTimerFinish = 0
  private var soundSnapBack = 0
  private var soundFailTts = 0
  private var activeFailTtsStreamId = 0

  @Volatile var isSoundEnabled: Boolean = true
  @Volatile var soundStyle: SoundStyle = SoundStyle.MOCHI_SOFT

  init {
    try {
      val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

      soundPool = SoundPool.Builder()
        .setMaxStreams(24) // 6종 효과음 및 동시 재생 스트림 확장
        .setAudioAttributes(audioAttributes)
        .build()

      soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
        if (status == 0) {
          loadedSoundIds.add(sampleId)
        } else {
          Log.w("SoundManager", "Sample $sampleId failed to load, status=$status")
          com.krdondon.quickpush.util.AppLogger.logWarn("SoundPool sample $sampleId load failed (status=$status)")
        }
      }

      // 백그라운드 IO 스레드에서 assets/sounds/*.wav 및 기타 효과음 비동기 로딩 수행
      scope.launch {
        loadSounds()
        scanGameOverTtsFiles()
      }
    } catch (e: Exception) {
      Log.e("SoundManager", "Error initializing SoundPool", e)
      com.krdondon.quickpush.util.AppLogger.logError("SoundPool init failed: ${e.message}", e)
    }
  }

  private fun loadSounds() {
    val pool = soundPool ?: return
    if (isReleased) return
    try {
      // 1. 버튼 팝 효과음 6종을 assets/sounds/*.wav에서 직접 4중 라운드 로빈 로드
      for (style in SoundStyle.entries) {
        val channels = popSoundChannels[style] ?: continue
        for (i in channels.indices) {
          if (isReleased) return
          try {
            context.assets.openFd(style.assetPath).use { afd ->
              channels[i] = pool.load(afd, 1)
            }
          } catch (e: Exception) {
            Log.e("SoundManager", "Failed to load ${style.assetPath} channel $i", e)
          }
        }
      }

      val cacheDir = context.cacheDir

      // 2. 시스템/피드백 효과음 생성 및 로드
      val levelUpFile = getOrCreateWav(cacheDir, "level_up_44k.wav") { generateLevelUpWav() }
      val successFile = getOrCreateWav(cacheDir, "success_44k.wav") { generateSuccessWav() }
      val failureFile = getOrCreateWav(cacheDir, "failure_44k.wav") { generateFailureWav() }
      val tickFile = getOrCreateWav(cacheDir, "tick_44k.wav") { generateTickWav() }
      val finishFile = getOrCreateWav(cacheDir, "finish_44k.wav") { generateFinishWav() }
      val snapFile = getOrCreateWav(cacheDir, "snap_44k.wav") { generateSnapWav() }

      if (isReleased) return

      soundLevelUp = pool.load(levelUpFile.absolutePath, 1)
      soundSuccess = pool.load(successFile.absolutePath, 1)
      soundFailure = pool.load(failureFile.absolutePath, 1)
      soundTimerTick = pool.load(tickFile.absolutePath, 1)
      soundTimerFinish = pool.load(finishFile.absolutePath, 1)
      soundSnapBack = pool.load(snapFile.absolutePath, 1)

      // 3. 기억력/실수 음성(assets/failTTS.wav) 사전 로드
      try {
        context.assets.openFd("failTTS.wav").use { afd ->
          soundFailTts = pool.load(afd, 1)
        }
      } catch (e: Exception) {
        Log.w("SoundManager", "Failed to load assets/failTTS.wav into SoundPool", e)
      }
    } catch (e: Exception) {
      Log.e("SoundManager", "Failed to load sound files", e)
    }
  }

  /**
   * 기억 모드 자동 시연 시작 전, 현재 선택된 스타일의 4개 채널 전체가 완전히 디코딩될 때까지 대기합니다.
   */
  suspend fun awaitPopSoundsReady(maxWaitMs: Long = 1500L): Boolean {
    val startTime = SystemClock.elapsedRealtime()
    val candidates = popSoundChannels[soundStyle] ?: return false
    while ((SystemClock.elapsedRealtime() - startTime) < maxWaitMs) {
      val readyCount = candidates.count { it != 0 && loadedSoundIds.contains(it) }
      if (readyCount >= candidates.size) return true
      delay(25L)
    }
    return candidates.any { it != 0 && loadedSoundIds.contains(it) }
  }

  /**
   * BGM이 꺼져 있거나 유휴 상태일 때 Android AudioFlinger / 블루투스 DAC 절전 복귀 지연으로
   * 첫 번째 패턴 효과음이 삼켜지지 않도록 무음(볼륨 0)으로 오디오 파이프라인을 미리 깨웁니다.
   */
  fun primeAudioForPattern() {
    if (!isSoundEnabled || isReleased) return
    val pool = soundPool ?: return
    val primerId = when {
      soundTimerTick != 0 && loadedSoundIds.contains(soundTimerTick) -> soundTimerTick
      else -> {
        val candidates = popSoundChannels[soundStyle]
        candidates?.firstOrNull { it != 0 && loadedSoundIds.contains(it) } ?: 0
      }
    }
    if (primerId != 0) {
      pool.play(primerId, 0f, 0f, 0, 0, 1.0f)
    }
  }

  fun playBubblePop(priority: Int = 1): Boolean {
    if (!isSoundEnabled || isReleased) return false
    val pool = soundPool
    val currentStyle = soundStyle

    val candidates = popSoundChannels[currentStyle] ?: IntArray(0)
    val availableIds = candidates.filter { it != 0 && loadedSoundIds.contains(it) }

    if (pool != null && availableIds.isNotEmpty()) {
      val targetSoundId = synchronized(popLock) {
        popIndex = (popIndex + 1) % availableIds.size
        availableIds[popIndex]
      }
      val pitch = if (priority >= 3) 1.0f else (0.96f + Random.nextFloat() * 0.12f)
      val streamId = pool.play(targetSoundId, 1.0f, 1.0f, priority, 0, pitch)

      if (streamId != 0) {
        return true
      }
      for (fallbackId in availableIds) {
        if (fallbackId != targetSoundId) {
          val retryStream = pool.play(fallbackId, 1.0f, 1.0f, priority, 0, 1.0f)
          if (retryStream != 0) return true
        }
      }
    }

    // 최후 안전장치: SoundPool 미준비/스트림 고갈 시 WAV asset을 AudioTrack으로 직접 재생
    Log.w("SoundManager", "SoundPool unavailable or dropped stream, falling back to AudioTrack")
    com.krdondon.quickpush.util.AppLogger.logWarn("SoundPool dropped -> AudioTrack fallback (${currentStyle.name})")
    playPopViaAudioTrackFallback(currentStyle)
    return true
  }

  private fun playPopViaAudioTrackFallback(style: SoundStyle) {
    scope.launch {
      if (isReleased || !isSoundEnabled) return@launch
      var track: AudioTrack? = null
      try {
        val wavBytes = context.assets.open(style.assetPath).use { it.readBytes() }
        if (wavBytes.size <= 44) return@launch

        val header = ByteBuffer.wrap(wavBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val channels = header.getShort(22).toInt()
        val sampleRate = header.getInt(24)
        val dataSize = wavBytes.size - 44

        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val attrs = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_GAME)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
        val format = AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(sampleRate)
          .setChannelMask(channelMask)
          .build()
        track = AudioTrack.Builder()
          .setAudioAttributes(attrs)
          .setAudioFormat(format)
          .setTransferMode(AudioTrack.MODE_STATIC)
          .setBufferSizeInBytes(dataSize)
          .build()
        track.write(wavBytes, 44, dataSize)
        track.play()
        val bytesPerSec = (sampleRate * channels * 2).coerceAtLeast(1)
        val durationMs = (dataSize * 1000L) / bytesPerSec
        delay(durationMs + 60L)
      } catch (e: Exception) {
        Log.w("SoundManager", "AudioTrack fallback failed", e)
        com.krdondon.quickpush.util.AppLogger.logError("AudioTrack fallback failed: ${e.message}", e)
      } finally {
        try {
          track?.stop()
          track?.release()
        } catch (_: Exception) {}
      }
    }
  }

  private fun playLoadedSound(soundId: Int, volume: Float, priority: Int) {
    if (!isSoundEnabled || isReleased) return
    if (soundId != 0 && loadedSoundIds.contains(soundId)) {
      soundPool?.play(soundId, volume, volume, priority, 0, 1.0f)
    }
  }

  fun playLevelUp() = playLoadedSound(soundLevelUp, 1.0f, 2)

  fun playSuccess() = playLoadedSound(soundSuccess, 0.9f, 1)

  fun playFailure() = playLoadedSound(soundFailure, 0.7f, 1)

  fun playTimerTick() = playLoadedSound(soundTimerTick, 0.6f, 1)

  fun playTimerFinish() = playLoadedSound(soundTimerFinish, 1.0f, 2)

  fun playSnapBack() = playLoadedSound(soundSnapBack, 1.0f, 2)

  /**
   * 기억력 모드에서 실수(1~4회)했을 때 assets/failTTS.wav를 즉시 재생합니다.
   * SoundPool 미로딩/실패 시 MediaPlayer로 폴백하고, 실패 시 기본 실패음으로 폴백합니다.
   */
  fun playPatternMistakeTts() {
    if (!isSoundEnabled || isReleased) return
    val pool = soundPool
    if (pool != null && soundFailTts != 0 && loadedSoundIds.contains(soundFailTts)) {
      if (activeFailTtsStreamId != 0) {
        try { pool.stop(activeFailTtsStreamId) } catch (_: Exception) {}
      }
      val streamId = pool.play(soundFailTts, 1.0f, 1.0f, 3, 0, 1.0f)
      if (streamId != 0) {
        activeFailTtsStreamId = streamId
        return
      }
    }

    // 폴백: MediaPlayer로 assets/failTTS.wav 비동기 재생
    scope.launch {
      withContext(Dispatchers.Main) {
        if (!isSoundEnabled || isReleased) return@withContext
        try {
          stopTtsPlayer()
          val afd = context.assets.openFd("failTTS.wav")
          val player = MediaPlayer().apply {
            setAudioAttributes(
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            )
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            setOnPreparedListener { mp ->
              if (isReleased) {
                try { mp.release() } catch (_: Exception) {}
                return@setOnPreparedListener
              }
              try { mp.start() } catch (_: Exception) { playFailure() }
            }
            setOnErrorListener { mp, _, _ ->
              try { mp.release() } catch (_: Exception) {}
              if (ttsMediaPlayer == mp) ttsMediaPlayer = null
              playFailure()
              true
            }
            setOnCompletionListener { mp ->
              try { mp.release() } catch (_: Exception) {}
              if (ttsMediaPlayer == mp) ttsMediaPlayer = null
            }
            prepareAsync()
          }
          ttsMediaPlayer = player
        } catch (e: Exception) {
          Log.w("SoundManager", "Failed to play fallback failTTS.wav", e)
          playFailure()
        }
      }
    }
  }

  private var ttsMediaPlayer: MediaPlayer? = null
  @Volatile private var cachedGameOverTtsFiles: List<String> = emptyList()

  /**
   * assets/Game_Over_TTS 디렉터리 내의 .wav / .mp3 음성 파일 목록을 동적 스캔합니다.
   * 사용자가 신규 파일을 폴더에 추가하더라도 별도의 코드 수정 없이 100% 자동 인식됩니다.
   */
  fun scanGameOverTtsFiles(): List<String> {
    return try {
      val files = context.assets.list("Game_Over_TTS") ?: emptyArray()
      val audioFiles = files.filter { it.endsWith(".wav", ignoreCase = true) || it.endsWith(".mp3", ignoreCase = true) }
      cachedGameOverTtsFiles = audioFiles
      audioFiles
    } catch (e: Exception) {
      Log.e("SoundManager", "Error listing Game_Over_TTS files", e)
      emptyList()
    }
  }

  /**
   * 게임 오버 시 Game_Over_TTS 폴더 안의 음성 파일 중 무작위 1개를 비동기 prepareAsync()로
   * 메인 스레드 멈춤 없이 안정적으로 재생합니다.
   */
  fun playGameOverTts() {
    if (!isSoundEnabled || isReleased) return

    scope.launch {
      val ttsFiles = cachedGameOverTtsFiles.ifEmpty { scanGameOverTtsFiles() }
      if (ttsFiles.isEmpty()) {
        withContext(Dispatchers.Main) { playFailure() }
        return@launch
      }

      val selectedFile = ttsFiles.random()
      val assetPath = "Game_Over_TTS/$selectedFile"

      withContext(Dispatchers.Main) {
        if (!isSoundEnabled || isReleased) return@withContext
        try {
          stopTtsPlayer()

          val afd = context.assets.openFd(assetPath)
          val player = MediaPlayer().apply {
            setAudioAttributes(
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            )
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            setOnPreparedListener { mp ->
              if (isReleased) {
                try { mp.release() } catch (_: Exception) {}
                return@setOnPreparedListener
              }
              try {
                mp.start()
              } catch (e: Exception) {
                Log.w("SoundManager", "Failed to start TTS MediaPlayer", e)
                com.krdondon.quickpush.util.AppLogger.logWarn("TTS start failed: ${e.message}")
                playFailure()
              }
            }
            setOnErrorListener { mp, what, extra ->
              com.krdondon.quickpush.util.AppLogger.logWarn("TTS MediaPlayer error: what=$what extra=$extra")
              try { mp.release() } catch (_: Exception) {}
              if (ttsMediaPlayer == mp) ttsMediaPlayer = null
              playFailure()
              true
            }
            setOnCompletionListener { mp ->
              try {
                mp.release()
              } catch (e: Exception) {
                Log.w("SoundManager", "Error releasing TTS MediaPlayer", e)
              }
              if (ttsMediaPlayer == mp) {
                ttsMediaPlayer = null
              }
            }
            prepareAsync()
          }
          ttsMediaPlayer = player
        } catch (e: Exception) {
          Log.e("SoundManager", "Failed to play Game_Over_TTS: $assetPath", e)
          com.krdondon.quickpush.util.AppLogger.logWarn("TTS load failed ($assetPath): ${e.message}")
          playFailure()
        }
      }
    }
  }

  private fun stopTtsPlayer() {
    try {
      ttsMediaPlayer?.apply {
        if (isPlaying) {
          stop()
        }
        release()
      }
      ttsMediaPlayer = null
    } catch (e: Exception) {
      Log.w("SoundManager", "Error stopping TTS player", e)
    }
  }

  fun onTrimMemory(level: Int) {
    // 중요: 효과음 사운드풀은 즉각 재생/종료되는 단타성 음원이므로,
    // 시스템 트림 시 autoPause()를 호출하면 사운드 채널이 잠겨 소리가 씹히는 원인이 됩니다.
    // 여기서는 soundPool을 pause하지 않고 그대로 유지합니다.
  }

  fun onResume() {
    try {
      soundPool?.autoResume()
    } catch (e: Exception) {
      Log.w("SoundManager", "Error auto-resuming SoundPool", e)
    }
  }

  fun release() {
    isReleased = true
    scope.cancel()
    try {
      stopTtsPlayer()
      soundPool?.release()
      soundPool = null
    } catch (e: Exception) {
      Log.e("SoundManager", "Error releasing SoundManager", e)
    }
  }

  private fun getOrCreateWav(dir: File, name: String, generator: () -> ByteArray): File {
    val file = File(dir, name)
    if (!file.exists() || file.length() <= 44L) {
      val data = generator()
      val tmpFile = File(dir, "$name.tmp")
      FileOutputStream(tmpFile).use { it.write(data) }
      if (!tmpFile.renameTo(file)) {
        FileOutputStream(file).use { it.write(data) }
        tmpFile.delete()
      }
    }
    return file
  }

  companion object {
    private const val SAMPLE_RATE = 44100 // 44.1kHz Android 네이티브 표준 샘플레이트 (리샘플링 지연 및 렉 0)

    private fun createWavHeader(dataLength: Int): ByteArray {
      val totalDataLen = dataLength + 36
      val byteRate = SAMPLE_RATE * 2
      val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      header.put("RIFF".toByteArray())
      header.putInt(totalDataLen)
      header.put("WAVE".toByteArray())
      header.put("fmt ".toByteArray())
      header.putInt(16) // Subchunk1Size (16 for PCM)
      header.putShort(1) // AudioFormat (1 for PCM)
      header.putShort(1) // NumChannels (1 mono)
      header.putInt(SAMPLE_RATE)
      header.putInt(byteRate)
      header.putShort(2) // BlockAlign (channels * bitsPerSample / 8)
      header.putShort(16) // BitsPerSample
      header.put("data".toByteArray())
      header.putInt(dataLength)
      return header.array()
    }

    private fun generateLevelUpWav(): ByteArray {
      // Arpeggio: C5 (523Hz), E5 (659Hz), G5 (784Hz), C6 (1046Hz)
      val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
      val noteDuration = 0.09f
      val totalDuration = noteDuration * notes.size
      val numSamples = (SAMPLE_RATE * totalDuration).toInt()
      val pcm = ShortArray(numSamples)

      val noteSamples = (SAMPLE_RATE * noteDuration).toInt()
      for (n in notes.indices) {
        val freq = notes[n]
        val startSample = n * noteSamples
        val endSample = (startSample + noteSamples).coerceAtMost(numSamples)
        for (i in startSample until endSample) {
          val localT = (i - startSample).toFloat() / SAMPLE_RATE
          val progress = (i - startSample).toFloat() / noteSamples
          val envelope = sin(progress * PI) * exp(-progress * 2.5)
          val wave = sin(2.0 * PI * freq * localT) + 0.3 * sin(2.0 * PI * (freq * 2) * localT)
          val sample = (wave * envelope * 24000.0).toInt().coerceIn(-32767, 32767)
          pcm[i] = sample.toShort()
        }
      }

      val byteBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
      pcm.forEach { byteBuffer.putShort(it) }
      val pcmBytes = byteBuffer.array()
      return createWavHeader(pcmBytes.size) + pcmBytes
    }

    private fun generateSuccessWav(): ByteArray {
      // Rising 2-note chime: G5 (784Hz) -> C6 (1046Hz)
      val notes = doubleArrayOf(783.99, 1046.50)
      val noteDuration = 0.12f
      val numSamples = (SAMPLE_RATE * (noteDuration * 2)).toInt()
      val pcm = ShortArray(numSamples)
      val noteSamples = (SAMPLE_RATE * noteDuration).toInt()

      for (n in notes.indices) {
        val freq = notes[n]
        val startSample = n * noteSamples
        val endSample = (startSample + noteSamples).coerceAtMost(numSamples)
        for (i in startSample until endSample) {
          val localT = (i - startSample).toFloat() / SAMPLE_RATE
          val progress = (i - startSample).toFloat() / noteSamples
          val envelope = exp(-progress * 4.0)
          val wave = sin(2.0 * PI * freq * localT)
          pcm[i] = (wave * envelope * 25000.0).toInt().coerceIn(-32767, 32767).toShort()
        }
      }

      val byteBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
      pcm.forEach { byteBuffer.putShort(it) }
      val pcmBytes = byteBuffer.array()
      return createWavHeader(pcmBytes.size) + pcmBytes
    }

    private fun generateFailureWav(): ByteArray {
      val duration = 0.22f
      val numSamples = (SAMPLE_RATE * duration).toInt()
      val pcm = ShortArray(numSamples)

      for (i in 0 until numSamples) {
        val t = i.toFloat() / SAMPLE_RATE
        val progress = i.toFloat() / numSamples
        val freq = 220.0 - (progress * 60.0) // descending soft buzz
        val envelope = exp(-progress * 6.0)
        val wave = sin(2.0 * PI * freq * t)
        pcm[i] = (wave * envelope * 18000.0).toInt().coerceIn(-32767, 32767).toShort()
      }

      val byteBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
      pcm.forEach { byteBuffer.putShort(it) }
      val pcmBytes = byteBuffer.array()
      return createWavHeader(pcmBytes.size) + pcmBytes
    }

    private fun generateTickWav(): ByteArray {
      val duration = 0.04f
      val numSamples = (SAMPLE_RATE * duration).toInt()
      val pcm = ShortArray(numSamples)

      for (i in 0 until numSamples) {
        val t = i.toFloat() / SAMPLE_RATE
        val progress = i.toFloat() / numSamples
        val envelope = exp(-progress * 25.0)
        val wave = sin(2.0 * PI * 1200.0 * t)
        pcm[i] = (wave * envelope * 22000.0).toInt().coerceIn(-32767, 32767).toShort()
      }

      val byteBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
      pcm.forEach { byteBuffer.putShort(it) }
      val pcmBytes = byteBuffer.array()
      return createWavHeader(pcmBytes.size) + pcmBytes
    }

    private fun generateFinishWav(): ByteArray {
      // Fanfare: C5, G5, C6 triumphant chime
      val notes = doubleArrayOf(523.25, 783.99, 1046.50)
      val noteDuration = 0.14f
      val numSamples = (SAMPLE_RATE * (noteDuration * 3)).toInt()
      val pcm = ShortArray(numSamples)
      val noteSamples = (SAMPLE_RATE * noteDuration).toInt()

      for (n in notes.indices) {
        val freq = notes[n]
        val startSample = n * noteSamples
        val endSample = (startSample + noteSamples).coerceAtMost(numSamples)
        for (i in startSample until endSample) {
          val localT = (i - startSample).toFloat() / SAMPLE_RATE
          val progress = (i - startSample).toFloat() / noteSamples
          val envelope = exp(-progress * 3.5)
          val wave = sin(2.0 * PI * freq * localT) + 0.4 * sin(2.0 * PI * (freq * 1.5) * localT)
          pcm[i] = (wave * envelope * 22000.0).toInt().coerceIn(-32767, 32767).toShort()
        }
      }

      val byteBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
      pcm.forEach { byteBuffer.putShort(it) }
      val pcmBytes = byteBuffer.array()
      return createWavHeader(pcmBytes.size) + pcmBytes
    }

    private fun generateSnapWav(): ByteArray {
      // Mechanical plastic snap / pop-back sound
      val duration = 0.09f
      val numSamples = (SAMPLE_RATE * duration).toInt()
      val pcm = ShortArray(numSamples)

      for (i in 0 until numSamples) {
        val t = i.toFloat() / SAMPLE_RATE
        val progress = i.toFloat() / numSamples
        val decay = exp(-progress * 22.0)
        // Two click transients
        val wave1 = sin(2.0 * PI * 1800.0 * t)
        val wave2 = sin(2.0 * PI * 350.0 * t)
        val sample = ((wave1 * 0.7 + wave2 * 0.8) * decay * 31000.0).toInt().coerceIn(-32767, 32767)
        pcm[i] = sample.toShort()
      }

      val byteBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
      pcm.forEach { byteBuffer.putShort(it) }
      val pcmBytes = byteBuffer.array()
      return createWavHeader(pcmBytes.size) + pcmBytes
    }
  }
}
