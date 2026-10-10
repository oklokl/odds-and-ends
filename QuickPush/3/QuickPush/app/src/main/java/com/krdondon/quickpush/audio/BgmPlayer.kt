package com.krdondon.quickpush.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

class BgmPlayer(private val context: Context) {

  private var mediaPlayer: MediaPlayer? = null
  private var playlist: List<String> = emptyList()
  private var currentTrackIndex = 0
  private var hasCompletedFirstCycle = false
  private var isPausedByUser = false
  private var lastPositionMs = 0
  private var volume = 0.35f // 잔잔하고 버튼 소리가 묻히지 않는 부드러운 배경음 볼륨

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

    // 1. Check external or app-specific external files dir (e.g. set/Music or custom music folder)
    try {
      val externalSetDir = File(context.getExternalFilesDir(null), "Music")
      if (externalSetDir.exists() && externalSetDir.isDirectory) {
        val files = externalSetDir.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }
        files?.forEach { musicFiles.add("file:" + it.absolutePath) }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error scanning external music folder", e)
    }

    // 2. Scan bundled assets/music directory
    try {
      val assetList = context.assets.list("music")
      assetList?.filter { it.endsWith(".mp3", ignoreCase = true) }?.forEach {
        musicFiles.add("asset:music/$it")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error listing asset music files", e)
    }

    playlist = musicFiles
    Log.d(TAG, "Loaded playlist (${playlist.size} tracks): $playlist")
  }

  fun setVolume(vol: Float) {
    volume = vol.coerceIn(0f, 1f)
    mediaPlayer?.setVolume(volume, volume)
  }

  fun playCurrentTrack() {
    if (!isMusicEnabled || playlist.isEmpty()) return

    releasePlayer()

    val trackPath = playlist[currentTrackIndex]
    try {
      mediaPlayer = MediaPlayer().apply {
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
        prepare()

        if (lastPositionMs > 0) {
          seekTo(lastPositionMs)
          lastPositionMs = 0
        }

        setOnCompletionListener {
          advanceToNextTrack()
          playCurrentTrack()
        }

        start()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error playing track: $trackPath", e)
      advanceToNextTrack()
    }
  }

  private fun advanceToNextTrack() {
    lastPositionMs = 0
    if (!hasCompletedFirstCycle) {
      currentTrackIndex++
      if (currentTrackIndex >= playlist.size) {
        hasCompletedFirstCycle = true
        // First cycle finished: shuffle playlist for continuous randomized playback
        playlist = playlist.shuffled()
        currentTrackIndex = 0
      }
    } else {
      currentTrackIndex = (currentTrackIndex + 1) % playlist.size
    }
  }

  fun pause() {
    try {
      mediaPlayer?.let {
        if (it.isPlaying) {
          lastPositionMs = it.currentPosition
          it.pause()
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error pausing BGM", e)
    }
  }

  fun resume() {
    if (!isMusicEnabled || playlist.isEmpty()) return
    try {
      if (mediaPlayer != null) {
        mediaPlayer?.start()
      } else {
        playCurrentTrack()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error resuming BGM", e)
      playCurrentTrack()
    }
  }

  fun stop() {
    try {
      mediaPlayer?.let {
        lastPositionMs = it.currentPosition
        it.stop()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error stopping BGM", e)
    }
  }

  fun release() {
    releasePlayer()
  }

  private fun releasePlayer() {
    try {
      mediaPlayer?.stop()
      mediaPlayer?.release()
      mediaPlayer = null
    } catch (e: Exception) {
      Log.w(TAG, "Error releasing MediaPlayer", e)
    }
  }

  companion object {
    private const val TAG = "BgmPlayer"
  }
}
