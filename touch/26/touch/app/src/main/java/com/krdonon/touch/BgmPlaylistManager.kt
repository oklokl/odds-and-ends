package com.krdonon.touch

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * app/src/main/assets/music 디렉터리의 음원 파일들을 자동으로 인식하여
 * 1) 첫 번째 사이클: 00_sstouch.mp3부터 목록 순서대로 정주행 재생
 * 2) 마지막 곡 종료 이후: 무한 랜덤(Random Shuffle) 재생
 * 3) Activity/Compose 생명주기와 완벽하게 연동하는 BGM 재생 관리자.
 */
class BgmPlaylistManager(private val context: Context) {

    private enum class PlayerState {
        IDLE,
        PREPARING,
        PLAYING,
        PAUSED,
        ERROR,
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playlist: List<String> = emptyList()
    private var currentTrackIndex = 0
    private var playerState = PlayerState.IDLE
    private var isPausedByLifecycle = false
    private var isUserEnabled = true

    // 첫 번째 사이클(목록 순서대로 정주행) 완료 여부 플래그
    private var isInitialSequentialCompleted = false

    init {
        loadPlaylistFromAssets()
    }

    /**
     * assets/music 디렉터리 내의 모든 음원 파일(.mp3, .wav, .ogg, .m4a, .aac 등)을 불러와
     * 대소문자 무관 알파벳/숫자 순(00_sstouch.mp3가 맨 앞)으로 정렬하여 재생 목록을 구성합니다.
     */
    fun loadPlaylistFromAssets() {
        val supportedExtensions = setOf("mp3", "wav", "ogg", "m4a", "aac")
        playlist = try {
            val fileList = context.assets.list("music")?.filter { fileName ->
                val ext = fileName.substringAfterLast('.', "").lowercase()
                ext in supportedExtensions
            }?.sortedWith(String.CASE_INSENSITIVE_ORDER) ?: emptyList()

            fileList.map { "music/$it" }
        } catch (_: Exception) {
            emptyList()
        }
        Log.d("BgmPlaylistManager", "Playlist loaded (${playlist.size} tracks): $playlist")
    }

    /**
     * BGM 스위치(ON/OFF) 상태 변경 처리
     */
    fun setEnabled(enabled: Boolean) {
        isUserEnabled = enabled
        if (!enabled) {
            pause()
        } else if (!isPausedByLifecycle) {
            resume()
        }
    }

    /**
     * 지정한 인덱스의 음원을 재생합니다.
     */
    private fun playTrack(index: Int) {
        if (playlist.isEmpty()) return
        currentTrackIndex = index.coerceIn(0, playlist.size - 1)
        val assetPath = playlist[currentTrackIndex]

        Log.d("BgmPlaylistManager", "playTrack [$currentTrackIndex]: $assetPath (randomMode=$isInitialSequentialCompleted)")

        // 기존 플레이어 리스너 및 리소스 안전 정리
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.setOnErrorListener(null)
        mediaPlayer?.setOnPreparedListener(null)
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        playerState = PlayerState.IDLE

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setVolume(0.5f, 0.5f)

                try {
                    val afd: AssetFileDescriptor = context.assets.openFd(assetPath)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                } catch (_: IOException) {
                    val cacheFile = File(context.cacheDir, "bgm_temp_${File(assetPath).name}")
                    if ((!cacheFile.exists()) || (cacheFile.length() == 0L)) {
                        context.assets.open(assetPath).use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    setDataSource(cacheFile.absolutePath)
                }

                setOnPreparedListener { mp ->
                    playerState = PlayerState.PAUSED
                    if (isUserEnabled && !isPausedByLifecycle) {
                        try {
                            mp.start()
                            playerState = PlayerState.PLAYING
                        } catch (_: Exception) {
                            playerState = PlayerState.ERROR
                        }
                    }
                }

                setOnCompletionListener {
                    playerState = PlayerState.PAUSED
                    onTrackCompleted()
                }

                setOnErrorListener { _, what, extra ->
                    Log.w("BgmPlaylistManager", "MediaPlayer error: what=$what, extra=$extra")
                    playerState = PlayerState.ERROR
                    onTrackCompleted()
                    true
                }

                // UI 스레드 블로킹 방지를 위한 비동기 준비
                playerState = PlayerState.PREPARING
                prepareAsync()
            }

            mediaPlayer = player
        } catch (e: Exception) {
            Log.e("BgmPlaylistManager", "Failed to setup player for $assetPath", e)
            playerState = PlayerState.ERROR
            onTrackCompleted()
        }
    }

    /**
     * 한 곡의 재생이 끝났을 때의 전환 로직:
     * 1) 첫 번째 사이클: 00_sstouch -> Fading Alley Light -> ... -> 마지막 곡까지 순차 재생
     * 2) 마지막 곡이 끝나면 isInitialSequentialCompleted = true 전환 후 무한 랜덤 재생
     */
    private fun onTrackCompleted() {
        if (playlist.isEmpty()) return

        if (!isInitialSequentialCompleted) {
            if (currentTrackIndex >= (playlist.size - 1)) {
                // 마지막 곡 종료: 1회차 순차 정주행 완료 -> 다음부터 무한 랜덤 모드로 진입!
                isInitialSequentialCompleted = true
                Log.d("BgmPlaylistManager", "Initial sequential playback completed! Switching to infinite random mode.")
                playNextRandomTrack()
            } else {
                // 목록 순서대로 다음 트랙 재생
                playTrack(currentTrackIndex + 1)
            }
        } else {
            // 이후부터는 무한 랜덤 재생
            playNextRandomTrack()
        }
    }

    /**
     * 직전 재생된 곡과 연속으로 겹치지 않도록 랜덤하게 다음 곡 선택 및 재생
     */
    private fun playNextRandomTrack() {
        if (playlist.isEmpty()) return
        if (playlist.size == 1) {
            playTrack(0)
            return
        }

        var nextIndex: Int
        do {
            nextIndex = Random.nextInt(playlist.size)
        } while (nextIndex == currentTrackIndex)

        playTrack(nextIndex)
    }

    /**
     * 정지되었던 지점부터 이어서 재생하거나, 시작 전이면 첫 곡(00_sstouch) 재생
     */
    fun resume() {
        if (!isUserEnabled) return

        when (playerState) {
            PlayerState.PREPARING -> {
                // 비동기 준비 중이므로 추가 조작하지 않고 onPrepared 완료 시 자동 재생 대기
                return
            }
            PlayerState.PLAYING -> {
                // 이미 재생 중
                return
            }
            PlayerState.PAUSED -> {
                mediaPlayer?.let { player ->
                    try {
                        player.start()
                        playerState = PlayerState.PLAYING
                    } catch (_: Exception) {
                        playTrack(currentTrackIndex)
                    }
                } ?: playTrack(currentTrackIndex)
            }
            PlayerState.IDLE, PlayerState.ERROR -> {
                // 최초 시작 시 목록의 0번째 트랙(00_sstouch.mp3)부터 재생
                playTrack(currentTrackIndex)
            }
        }
    }

    /**
     * 일시 정지 (현재 재생 위치 보존)
     */
    fun pause() {
        if (playerState == PlayerState.PLAYING) {
            try {
                mediaPlayer?.pause()
                playerState = PlayerState.PAUSED
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 앱을 닫거나 홈 화면으로 나갈 때 (onStop):
     * 음악을 잠시 정지하고 재생 위치를 보존합니다.
     */
    fun onPauseOrStop() {
        isPausedByLifecycle = true
        pause()
    }

    /**
     * 다시 앱으로 돌아올 때 (onStart / onResume):
     * 정지되었던 지점부터 음악이 다시 이어서 재생됩니다.
     */
    fun onResumeOrStart() {
        isPausedByLifecycle = false
        if (isUserEnabled) {
            resume()
        }
    }

    /**
     * 앱이 완전히 종료될 때 (onDestroy):
     * MediaPlayer 네이티브 및 메모리 리소스를 해제합니다.
     */
    fun release() {
        playerState = PlayerState.IDLE
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.setOnErrorListener(null)
        mediaPlayer?.setOnPreparedListener(null)
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }
    }
}
