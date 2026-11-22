package com.krdondon.quickpush.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

class BgmPlayer(private val context: Context) {

  private enum class PlayerState { IDLE, PREPARING, PLAYING, PAUSED }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var loadJob: Job? = null
  private var mediaPlayer: MediaPlayer? = null
  private var state = PlayerState.IDLE
  @Volatile private var playlist: List<String> = emptyList()
  private var currentTrackIndex = 0
  private var hasCompletedFirstCycle = false
  @Volatile private var isPausedByLifecycle = false
  @Volatile private var isPausedByFocusLoss = false
  @Volatile private var shouldPlayOnPrepared = false
  @Volatile private var isReleased = false

  // 볼륨 관리: 알림음(Ducking)이 와도 원본 기준 볼륨을 절대 훼손하지 않음
  private var baseVolume = 0.35f
  private var isDucked = false

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  private var focusRequest: AudioFocusRequest? = null

  private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    com.krdondon.quickpush.util.AppLogger.logAudio("onAudioFocusChange", true, "focusChange=$focusChange")
    when (focusChange) {
      AudioManager.AUDIOFOCUS_GAIN -> {
        // 알림음/통화 등이 끝나고 포커스를 돌려받았을 때 볼륨 원복 및 자동 이어 재생
        isDucked = false
        isPausedByFocusLoss = false
        applyEffectiveVolume()
        if (isMusicEnabled && !isPausedByLifecycle) {
          resumeInternal(requestFocus = false)
        }
      }
      AudioManager.AUDIOFOCUS_LOSS -> {
        isPausedByFocusLoss = true
        pauseInternal(abandonFocus = true, fromLifecycle = false)
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        // 일시적 포커스 상실 시에는 리스너를 유지해야 이후 AUDIOFOCUS_GAIN 콜백을 수신해 자동 복구 가능
        isPausedByFocusLoss = true
        pauseInternal(abandonFocus = false, fromLifecycle = false)
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        // 일시적인 알림음 재생 시에만 잠깐 볼륨 낮춤 (baseVolume은 안전하게 유지)
        isDucked = true
        applyEffectiveVolume()
      }
    }
  }

  var isMusicEnabled: Boolean = true
    set(value) {
      field = value
      if (!value) {
        pause()
      } else {
        resume()
      }
    }

  init {
    loadJob = scope.launch(Dispatchers.IO) {
      loadPlaylistSync()
    }
  }

  private fun loadPlaylistSync() {
    val musicFiles = mutableListOf<String>()

    // 1. 외부 또는 앱 전용 Music 디렉터리 동적 스캔 (새로운 mp3 추가 시 자동 인식)
    try {
      val externalSetDir = File(context.getExternalFilesDir(null), "Music")
      if (externalSetDir.exists() && externalSetDir.isDirectory) {
        val files = externalSetDir.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }
        files?.forEach { musicFiles.add("file:" + it.absolutePath) }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error scanning external music folder", e)
    }

    // 2. assets/music 번들 디렉터리 스캔
    try {
      val assetList = context.assets.list("music")
      assetList?.filter { it.endsWith(".mp3", ignoreCase = true) }?.forEach {
        musicFiles.add("asset:music/$it")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error listing asset music files", e)
    }

    playlist = musicFiles
    Log.d(TAG, "Loaded playlist (${playlist.size} tracks)")
  }

  fun setVolume(vol: Float) {
    baseVolume = vol.coerceIn(0f, 1f)
    applyEffectiveVolume()
  }

  private fun applyEffectiveVolume() {
    val effective = if (isDucked) baseVolume * 0.3f else baseVolume
    try {
      mediaPlayer?.setVolume(effective, effective)
    } catch (_: Exception) {}
  }

  fun playTrack(index: Int) {
    if (isReleased) return
    if (playlist.isEmpty()) {
      scope.launch {
        withContext(Dispatchers.IO) {
          loadJob?.join()
          if (playlist.isEmpty()) {
            loadPlaylistSync()
          }
        }
        if (playlist.isNotEmpty() && !isReleased) {
          startTrackInternal(index)
        }
      }
      return
    }
    startTrackInternal(index)
  }

  private fun startTrackInternal(index: Int) {
    if (isReleased || playlist.isEmpty()) return
    currentTrackIndex = index % playlist.size

    // 기존 플레이어 리스너 및 리소스 안전 해제
    cleanupPlayer()

    val trackPath = playlist[currentTrackIndex]
    try {
      val player = MediaPlayer().apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        )

        if (trackPath.startsWith("asset:")) {
          val assetRelative = trackPath.removePrefix("asset:")
          val afd: AssetFileDescriptor = context.assets.openFd(assetRelative)
          setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
          afd.close()
        } else if (trackPath.startsWith("file:")) {
          val filePath = trackPath.removePrefix("file:")
          setDataSource(filePath)
        }

        applyEffectiveVolume()

        // 비동기 준비 완료 콜백
        setOnPreparedListener { mp ->
          if (isReleased) {
            cleanupPlayer()
            return@setOnPreparedListener
          }
          state = PlayerState.PAUSED
          com.krdondon.quickpush.util.AppLogger.logAudio("prepareAsync", true, "Track prepared index=$currentTrackIndex, path=$trackPath")
          applyEffectiveVolume()
          if (isMusicEnabled && (shouldPlayOnPrepared || (!isPausedByLifecycle && !isPausedByFocusLoss))) {
            shouldPlayOnPrepared = false
            isPausedByLifecycle = false
            isPausedByFocusLoss = false
            requestAudioFocus()
            try {
              mp.start()
              state = PlayerState.PLAYING
              com.krdondon.quickpush.util.AppLogger.logAudio("start", true, "Playback started index=$currentTrackIndex")
            } catch (e: Exception) {
              Log.w(TAG, "Failed to start player onPrepared", e)
              com.krdondon.quickpush.util.AppLogger.logAudio("start", false, "Failed in onPrepared: ${e.message}")
              onTrackEnd()
            }
          }
        }

        // 곡 종료 시: 1회차 순차 재생 -> 그 후 무한 셔플
        setOnCompletionListener {
          com.krdondon.quickpush.util.AppLogger.logAudio("completion", true, "Track completed index=$currentTrackIndex")
          onTrackEnd()
        }

        // 에러 발생 시 앱이 먹통되지 않고 다음 곡으로 즉시 자동 회복
        setOnErrorListener { _, what, extra ->
          val errDetails = "what=$what, extra=$extra, trackIndex=$currentTrackIndex"
          Log.w(TAG, "MediaPlayer error: $errDetails. Recovering next track...")
          com.krdondon.quickpush.util.AppLogger.logAudio("error", false, errDetails)
          onTrackEnd()
          true
        }

        state = PlayerState.PREPARING
        prepareAsync() // UI 스레드 멈춤(렉) 원천 차단
      }
      mediaPlayer = player
    } catch (e: Exception) {
      Log.e(TAG, "Error initiating track: $trackPath", e)
      com.krdondon.quickpush.util.AppLogger.logAudio("initiateTrack", false, "Exception: ${e.message}")
      onTrackEnd()
    }
  }

  fun playCurrentTrack() {
    playTrack(currentTrackIndex)
  }

  private fun onTrackEnd() {
    if (playlist.isEmpty()) return

    if (!hasCompletedFirstCycle) {
      if (currentTrackIndex >= playlist.size - 1) {
        hasCompletedFirstCycle = true
        playRandomTrack()
      } else {
        playTrack(currentTrackIndex + 1)
      }
    } else {
      playRandomTrack()
    }
  }

  private fun playRandomTrack() {
    if (playlist.isEmpty()) return
    var next: Int
    do {
      next = Random.nextInt(playlist.size)
    } while (next == currentTrackIndex && playlist.size > 1)
    playTrack(next)
  }

  /**
   * 앱 복귀 시 (onStart / onResume):
   * 기존 플레이어가 살아있으면 pause 지점부터 즉시 이어 재생하고,
   * 백그라운드나 절전 모드로 인해 플레이어가 죽어있거나 상태가 비정상(IDLE/ERROR)이면
   * 즉시 현재 트랙을 다시 로드하여 음악을 100% 복구합니다.
   * 복귀 시에는 덕킹 상태를 완전히 해제하고 항상 기준 볼륨(0.35f)으로 100% 원복합니다.
   */
  fun resume() {
    isPausedByLifecycle = false
    isPausedByFocusLoss = false
    resumeInternal(requestFocus = true)
  }

  private fun resumeInternal(requestFocus: Boolean) {
    if (isReleased) return
    shouldPlayOnPrepared = true
    isDucked = false
    applyEffectiveVolume()
    com.krdondon.quickpush.util.AppLogger.logAudio("resume", true, "Called with isMusicEnabled=$isMusicEnabled, state=$state")
    if (!isMusicEnabled) return

    val player = mediaPlayer
    if (player != null) {
      when (state) {
        PlayerState.PREPARING -> {
          com.krdondon.quickpush.util.AppLogger.logAudio("resume", true, "Player is PREPARING, marked shouldPlayOnPrepared=true")
          return
        }
        PlayerState.PLAYING -> {
          applyEffectiveVolume()
          com.krdondon.quickpush.util.AppLogger.logAudio("resume", true, "Player already PLAYING, ensured effective volume")
          return
        }
        PlayerState.PAUSED -> {
          if (requestFocus) requestAudioFocus()
          try {
            applyEffectiveVolume()
            player.start()
            state = PlayerState.PLAYING
            com.krdondon.quickpush.util.AppLogger.logAudio("resume", true, "Player restarted successfully from PAUSED")
          } catch (e: Exception) {
            Log.w(TAG, "Failed to resume existing player, re-creating track", e)
            com.krdondon.quickpush.util.AppLogger.logAudio("resume", false, "Start failed: ${e.message}, falling back to recreate")
            playCurrentTrack()
          }
        }
        PlayerState.IDLE -> {
          com.krdondon.quickpush.util.AppLogger.logAudio("resume", true, "Player IDLE, calling playCurrentTrack()")
          playCurrentTrack()
        }
      }
    } else {
      com.krdondon.quickpush.util.AppLogger.logAudio("resume", true, "Player null, initiating playCurrentTrack()")
      playCurrentTrack()
    }
  }

  /**
   * 홈 화면 이동, 화면 잠금, 최근 앱(Task Switcher) 목록 이동 시 (onStop):
   * 백그라운드 소리를 멈추면서 위치(Position)를 온전히 보존합니다.
   */
  fun pause() {
    isPausedByFocusLoss = false
    pauseInternal(abandonFocus = true, fromLifecycle = true)
  }

  private fun pauseInternal(abandonFocus: Boolean, fromLifecycle: Boolean) {
    if (fromLifecycle) {
      isPausedByLifecycle = true
    }
    shouldPlayOnPrepared = false
    if (abandonFocus) {
      abandonAudioFocus()
    }
    com.krdondon.quickpush.util.AppLogger.logAudio("pause", true, "Called with state=$state, fromLifecycle=$fromLifecycle")
    val player = mediaPlayer ?: return
    try {
      if (state == PlayerState.PLAYING || player.isPlaying) {
        player.pause()
        state = PlayerState.PAUSED
        com.krdondon.quickpush.util.AppLogger.logAudio("pause", true, "Player paused successfully")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error pausing player", e)
      com.krdondon.quickpush.util.AppLogger.logAudio("pause", false, "Error pausing player: ${e.message}")
    }
  }

  private fun requestAudioFocus() {
    val am = audioManager ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (focusRequest == null) {
          focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
            setAudioAttributes(
              AudioAttributes.Builder().run {
                setUsage(AudioAttributes.USAGE_GAME)
                setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                build()
              }
            )
            setAcceptsDelayedFocusGain(true)
            setOnAudioFocusChangeListener(audioFocusChangeListener)
            build()
          }
        }
        val result = am.requestAudioFocus(focusRequest!!)
        com.krdondon.quickpush.util.AppLogger.logAudio("requestAudioFocus", result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, "result=$result")
      } else {
        @Suppress("DEPRECATION")
        val result = am.requestAudioFocus(
          audioFocusChangeListener,
          AudioManager.STREAM_MUSIC,
          AudioManager.AUDIOFOCUS_GAIN
        )
        com.krdondon.quickpush.util.AppLogger.logAudio("requestAudioFocus", result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, "result=$result")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error requesting audio focus", e)
      com.krdondon.quickpush.util.AppLogger.logAudio("requestAudioFocus", false, "Exception: ${e.message}")
    }
  }

  private fun abandonAudioFocus() {
    val am = audioManager ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
      } else {
        @Suppress("DEPRECATION")
        am.abandonAudioFocus(audioFocusChangeListener)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error abandoning audio focus", e)
    }
  }

  fun stop() {
    pause()
  }

  fun release() {
    isReleased = true
    scope.cancel()
    abandonAudioFocus()
    cleanupPlayer()
  }

  private fun cleanupPlayer() {
    state = PlayerState.IDLE
    try {
      mediaPlayer?.setOnCompletionListener(null)
      mediaPlayer?.setOnPreparedListener(null)
      mediaPlayer?.setOnErrorListener(null)
      mediaPlayer?.release()
    } catch (e: Exception) {
      Log.w(TAG, "Error releasing MediaPlayer", e)
    }
    mediaPlayer = null
  }

  companion object {
    private const val TAG = "BgmPlayer"
  }
}
