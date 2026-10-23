package com.krdondon.quickpush.audio

import android.content.Context
import android.media.AudioAttributes
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
import kotlinx.coroutines.delay

class SoundManager(private val context: Context) {

  private var soundPool: SoundPool? = null

  // 개별 사운드 ID별 로딩 완료 상태를 스레드 안전하게 정확히 추적
  private val loadedSoundIds: MutableSet<Int> = Collections.synchronizedSet(HashSet<Int>())

  // 4중 라운드 로빈(Round-Robin) 풀링: 초고속 연타나 연속 불빛 재생 시에도 소리가 씹히지 않도록 독립 채널 분산
  private val soundNormalPops = IntArray(4)
  private val soundCrystalPops = IntArray(4)
  private var popIndex = 0

  private var soundLevelUp = 0
  private var soundSuccess = 0
  private var soundFailure = 0
  private var soundTimerTick = 0
  private var soundTimerFinish = 0
  private var soundSnapBack = 0

  var isSoundEnabled: Boolean = true
  var soundStyle: SoundStyle = SoundStyle.CLASSIC_POP

  init {
    try {
      val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

      soundPool = SoundPool.Builder()
        .setMaxStreams(16) // 동시 재생 스트림 16개로 확장하여 보이스 드랍 방지
        .setAudioAttributes(audioAttributes)
        .build()

      soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
        if (status == 0) {
          loadedSoundIds.add(sampleId)
          Log.d("SoundManager", "Sample $sampleId loaded successfully")
        } else {
          Log.w("SoundManager", "Sample $sampleId failed to load, status=$status")
        }
      }

      loadSynthesizedSounds()
    } catch (e: Exception) {
      Log.e("SoundManager", "Error initializing SoundPool", e)
    }
  }

  private fun loadSynthesizedSounds() {
    val pool = soundPool ?: return
    try {
      val cacheDir = context.cacheDir

      // 44.1kHz 네이티브 오디오 파일 생성 (리샘플링 지연 및 렉 0 달성)
      val popFile = getOrCreateWav(cacheDir, "pop_normal_44k.wav") { generatePopWav(isCrystal = false) }
      val crystalFile = getOrCreateWav(cacheDir, "pop_crystal_44k.wav") { generatePopWav(isCrystal = true) }
      val levelUpFile = getOrCreateWav(cacheDir, "level_up_44k.wav") { generateLevelUpWav() }
      val successFile = getOrCreateWav(cacheDir, "success_44k.wav") { generateSuccessWav() }
      val failureFile = getOrCreateWav(cacheDir, "failure_44k.wav") { generateFailureWav() }
      val tickFile = getOrCreateWav(cacheDir, "tick_44k.wav") { generateTickWav() }
      val finishFile = getOrCreateWav(cacheDir, "finish_44k.wav") { generateFinishWav() }
      val snapFile = getOrCreateWav(cacheDir, "snap_44k.wav") { generateSnapWav() }

      // 4중 라운드 로빈 로딩: 동일 사운드를 4개 채널에 병렬 등록하여 초고속 연타 시에도 무조건 100% 발청
      for (i in 0 until 4) {
        soundNormalPops[i] = pool.load(popFile.absolutePath, 1)
        soundCrystalPops[i] = pool.load(crystalFile.absolutePath, 1)
      }

      soundLevelUp = pool.load(levelUpFile.absolutePath, 1)
      soundSuccess = pool.load(successFile.absolutePath, 1)
      soundFailure = pool.load(failureFile.absolutePath, 1)
      soundTimerTick = pool.load(tickFile.absolutePath, 1)
      soundTimerFinish = pool.load(finishFile.absolutePath, 1)
      soundSnapBack = pool.load(snapFile.absolutePath, 1)
    } catch (e: Exception) {
      Log.e("SoundManager", "Failed to synthesize and load sound wav files", e)
    }
  }

  /**
   * 기억 모드 자동 시연 시작 전, 효과음이 실제로 로드되었는지 확인하고 대기합니다.
   * 로딩 전 재생 요청으로 인한 간헐적 침묵을 100% 방지합니다.
   */
  suspend fun awaitPopSoundsReady(maxWaitMs: Long = 1000L): Boolean {
    val startTime = SystemClock.elapsedRealtime()
    val candidates = if (soundStyle == SoundStyle.CRYSTAL_POP) soundCrystalPops else soundNormalPops
    while (SystemClock.elapsedRealtime() - startTime < maxWaitMs) {
      val readyCount = candidates.count { it != 0 && loadedSoundIds.contains(it) }
      if (readyCount >= 1) return true
      delay(30L)
    }
    return candidates.any { it != 0 && loadedSoundIds.contains(it) }
  }

  fun playBubblePop(priority: Int = 1): Boolean {
    if (!isSoundEnabled) return false
    val pool = soundPool ?: return false

    val candidates = if (soundStyle == SoundStyle.CRYSTAL_POP) soundCrystalPops else soundNormalPops
    // 로딩이 완료된 사운드 ID만 정확히 필터링 (미완료 ID 호출로 인한 묵음 원천 차단)
    val availableIds = candidates.filter { it != 0 && loadedSoundIds.contains(it) }

    if (availableIds.isNotEmpty()) {
      popIndex = (popIndex + 1) % availableIds.size
      val targetSoundId = availableIds[popIndex]
      val pitch = 0.96f + Random.nextFloat() * 0.12f
      val streamId = pool.play(targetSoundId, 1.0f, 1.0f, priority, 0, pitch)

      if (streamId != 0) {
        return true
      } else {
        // 특정 스트림 재생 실패 시 다른 가용 ID로 즉시 1회 Fallback 재시도
        for (fallbackId in availableIds) {
          if (fallbackId != targetSoundId) {
            val retryStream = pool.play(fallbackId, 1.0f, 1.0f, priority, 0, 1.0f)
            if (retryStream != 0) return true
          }
        }
      }
    } else {
      Log.w("SoundManager", "playBubblePop called but no pop sound loaded yet in loadedSoundIds")
    }
    return false
  }

  fun playLevelUp() {
    if (!isSoundEnabled) return
    if (soundLevelUp != 0 && loadedSoundIds.contains(soundLevelUp)) {
      soundPool?.play(soundLevelUp, 1.0f, 1.0f, 2, 0, 1.0f)
    }
  }

  fun playSuccess() {
    if (!isSoundEnabled) return
    soundPool?.play(soundSuccess, 0.9f, 0.9f, 1, 0, 1.0f)
  }

  fun playFailure() {
    if (!isSoundEnabled) return
    soundPool?.play(soundFailure, 0.7f, 0.7f, 1, 0, 1.0f)
  }

  fun playTimerTick() {
    if (!isSoundEnabled) return
    soundPool?.play(soundTimerTick, 0.6f, 0.6f, 1, 0, 1.0f)
  }

  fun playTimerFinish() {
    if (!isSoundEnabled) return
    soundPool?.play(soundTimerFinish, 1.0f, 1.0f, 2, 0, 1.0f)
  }

  fun playSnapBack() {
    if (!isSoundEnabled) return
    soundPool?.play(soundSnapBack, 1.0f, 1.0f, 2, 0, 1.0f)
  }

  private var ttsMediaPlayer: MediaPlayer? = null
  private var cachedGameOverTtsFiles: List<String> = emptyList()

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
   * 게임 오버 시 Game_Over_TTS 폴더 안의 음성 파일 중 무작위 1개를 씹힘 없이 안정적으로 완벽 재생합니다.
   */
  fun playGameOverTts() {
    if (!isSoundEnabled) return

    val ttsFiles = scanGameOverTtsFiles()
    if (ttsFiles.isEmpty()) {
      // assets 폴더에 음성 파일이 없을 경우 fallback으로 기존 failure 사운드 출력
      playFailure()
      return
    }

    val selectedFile = ttsFiles.random()
    val assetPath = "Game_Over_TTS/$selectedFile"

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
        prepare()
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
        start()
      }
      ttsMediaPlayer = player
      Log.d("SoundManager", "Successfully played Game_Over_TTS: $assetPath")
    } catch (e: Exception) {
      Log.e("SoundManager", "Failed to play Game_Over_TTS: $assetPath", e)
      playFailure()
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
    if (!file.exists() || file.length() == 0L) {
      val data = generator()
      FileOutputStream(file).use { it.write(data) }
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

    private fun generatePopWav(isCrystal: Boolean): ByteArray {
      val durationSec = if (isCrystal) 0.12f else 0.08f
      val numSamples = (SAMPLE_RATE * durationSec).toInt()
      val pcm = ShortArray(numSamples)

      for (i in 0 until numSamples) {
        val t = i.toFloat() / SAMPLE_RATE
        val progress = i.toFloat() / numSamples

        val sample = if (isCrystal) {
          // Crystal ping: fundamental 950Hz + harmonic 1900Hz with bell decay
          val decay = exp(-progress * 9.0)
          val wave1 = sin(2.0 * PI * 950.0 * t)
          val wave2 = 0.5 * sin(2.0 * PI * 1900.0 * t)
          ((wave1 + wave2) * decay * 28000.0).toInt().coerceIn(-32767, 32767)
        } else {
          // Silicone pop: swift downward pitch plunge (700Hz -> 100Hz) with pop envelope
          val freq = 700.0 - (progress * 580.0)
          val decay = exp(-progress * 14.0)
          val wave = sin(2.0 * PI * freq * t)
          (wave * decay * 30000.0).toInt().coerceIn(-32767, 32767)
        }
        pcm[i] = sample.toShort()
      }

      val byteBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
      pcm.forEach { byteBuffer.putShort(it) }
      val pcmBytes = byteBuffer.array()

      val header = createWavHeader(pcmBytes.size)
      return header + pcmBytes
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
