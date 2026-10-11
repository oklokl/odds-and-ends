package com.krdondon.quickpush.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import kotlin.random.Random

class BgmPlayer(private val context: Context) {

  private enum class PlayerState { IDLE, PREPARING, PLAYING, PAUSED }

  private var mediaPlayer: MediaPlayer? = null
  private var state = PlayerState.IDLE
  private var playlist: List<String> = emptyList()
  private var currentTrackIndex = 0
  private var hasCompletedFirstCycle = false
  private var isPausedByLifecycle = false
  private var volume = 0.35f // 잔잔하고 효과음(팝소리)이 묻히지 않는 부드러운 배경음 볼륨

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
    loadPlaylist()
  }

  private fun loadPlaylist() {
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
    volume = vol.coerceIn(0f, 1f)
    try {
      mediaPlayer?.setVolume(volume, volume)
    } catch (_: Exception) {}
  }

  fun playTrack(index: Int) {
    if (playlist.isEmpty()) {
      loadPlaylist()
      if (playlist.isEmpty()) return
    }
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

        setVolume(volume, volume)

        // 비동기 준비 완료 콜백 (블루투스 오디오 루팅 변경 및 스피커 전환 시 네이티브 락 방지)
        setOnPreparedListener { mp ->
          state = PlayerState.PAUSED
          if (isMusicEnabled && !isPausedByLifecycle) {
            try {
              mp.start()
              state = PlayerState.PLAYING
            } catch (e: Exception) {
              Log.w(TAG, "Failed to start player onPrepared", e)
              onTrackEnd()
            }
          }
        }

        // 곡 종료 시: 1회차 순차 재생 -> 그 후 무한 셔플
        setOnCompletionListener {
          onTrackEnd()
        }

        // 에러 발생 시 앱이 먹통되지 않고 다음 곡으로 즉시 자동 회복
        setOnErrorListener { _, what, extra ->
          Log.w(TAG, "MediaPlayer error: what=$what, extra=$extra. Recovering next track...")
          onTrackEnd()
          true
        }

        state = PlayerState.PREPARING
        prepareAsync() // UI 스레드 멈춤(렉) 원천 차단
      }
      mediaPlayer = player
    } catch (e: Exception) {
      Log.e(TAG, "Error initiating track: $trackPath", e)
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
   */
  fun resume() {
    isPausedByLifecycle = false
    if (!isMusicEnabled) return

    val player = mediaPlayer
    if (player != null) {
      when (state) {
        PlayerState.PREPARING, PlayerState.PLAYING -> return
        PlayerState.PAUSED -> {
          try {
            player.start()
            state = PlayerState.PLAYING
          } catch (e: Exception) {
            Log.w(TAG, "Failed to resume existing player, re-creating track", e)
            playCurrentTrack()
          }
        }
        PlayerState.IDLE -> {
          playCurrentTrack()
        }
      }
    } else {
      playCurrentTrack()
    }
  }

  /**
   * 홈 화면 이동, 화면 잠금, 최근 앱(Task Switcher) 목록 이동 시 (onStop):
   * 백그라운드 소리를 멈추면서 위치(Position)를 온전히 보존합니다.
   */
  fun pause() {
    isPausedByLifecycle = true
    val player = mediaPlayer ?: return
    try {
      if (state == PlayerState.PLAYING || player.isPlaying) {
        player.pause()
        state = PlayerState.PAUSED
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error pausing player", e)
    }
  }

  fun stop() {
    pause()
  }

  fun release() {
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
