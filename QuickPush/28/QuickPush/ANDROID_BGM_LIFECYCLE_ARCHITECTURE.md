# 🎵 완벽한 Android BGM & 오디오 생명주기 아키텍처 설계 가이드

이 문서는 안드로이드 앱 개발 시 가장 빈번하게 발생하는 **오디오 먹통(블루투스 해제, 스피커 전환, 장시간 화면 잠금, 최근 앱 목록 전환 후 복귀 시 무음 현상, 모든 앱 닫기 후 재진입 시 침묵 버그)**을 근본적으로 방지하고 완벽하게 복구할 수 있도록 검증된 **아키텍처 설계 원칙과 재사용 가능한 코드 템플릿**을 정리한 가이드입니다.

다른 프로젝트(게임, 유틸리티, 힐링 앱 등)를 개발할 때 그대로 복사하여 적용할 수 있습니다.

---

## 💡 1. 왜 이런 문제가 발생하며, 어떻게 설계해야 하는가?

안드로이드의 `MediaPlayer`는 단순한 Java/Kotlin 객체가 아니라, **OS 네이티브 오디오 서버(`audioserver`) 및 하드웨어 드라이버**와 직접 맞물려 동작합니다. 따라서 다음과 같은 5가지 핵심 설계 원칙을 반드시 지켜야 합니다.

### ① ON_PAUSE vs ON_STOP 분리 (필수 생명주기 규칙)
- **잘못된 설계**: `onPause()`에서 음악을 정지(`pause()`)하는 경우
  - 안드로이드에서는 **설정 팝업, 다이얼로그, 시스템 알림창이 화면 일부를 가릴 때도 `onPause()`가 호출**됩니다.
  - 이로 인해 버튼이나 메뉴를 누를 때마다 음악이 뚝뚝 끊기거나, 복귀 시 오디오 포커스가 꼬이게 됩니다.
- **올바른 설계**:
  - `onPause()`: 음악을 멈추지 않고 그대로 둡니다. (앱 내부 조작 시 BGM 유지)
  - `onStop()`: 사용자가 **홈 버튼을 누르거나, 화면을 잠그거나, 최근 실행 앱(Task Switcher) 목록으로 완전히 빠져나갔을 때만 호출**되므로, **이때만 `pause()`**를 호출하여 위치를 보존합니다.
  - `onResume()`: 앱으로 복귀했을 때 **`resume()`**을 호출하여 듣던 지점부터 매끄럽게 이어 재생합니다.

### ② 동기식 `prepare()` 금지 ➔ 비동기 `prepareAsync()` 사용 (블루투스/스피커 전환 락 방지)
- 블루투스 이어폰이 끊겨 폰 스피커로 출력 경로가 바뀌는 순간(오디오 라우팅 변경), 동기식 `prepare()`를 호출하면 네이티브 오디오 스레드가 락(Lock)에 걸려 영구 무음 상태가 됩니다.
- 반드시 **`prepareAsync()`**를 사용하여 백그라운드 스레드에서 음원을 준비하고, 준비가 완료된 순간(`setOnPreparedListener`)에만 안전하게 `start()`가 실행되도록 해야 합니다.

### ③ onTrimMemory(TRIM_MEMORY_UI_HIDDEN = 20) 간섭 분리 (콜드 스타트 무음 방지) [중요!]
- **문제 현상**: 모든 앱 닫기 후 새로 시작하거나 앱 구동 직후 시스템이 백그라운드 잔여 프로세스를 정리하며 `onTrimMemory(level=20)`를 호출할 때가 있습니다.
- **치명적 실수**: `onTrimMemory`에서 `bgmPlayer.pause()`를 호출하면, 비동기 준비 중이던 플레이어가 `isPausedByLifecycle = true`로 오염되어, 불과 수 밀리초 뒤 음원 준비가 끝나도 `start()`가 무시되어 영구 무음 상태가 됩니다.
- **해결책**:
  - `onTrimMemory`는 UI 비트맵/캐시 메모리만 정리하고, BGM 일시정지는 이미 전담하고 있는 `onStop()`에만 맡깁니다.
  - `BgmPlayer` 내부에 `shouldPlayOnPrepared` 플래그를 두어, `resume()`이 불린 상태에서 음원 준비가 끝나면 어떤 레이스 컨디션에서도 반드시 100% 자동 재생되도록 보장합니다.

### ④ 오디오 덕킹(Ducking) 후 100% 원래 볼륨 복원 보장 [중요!]
- **문제 현상**: 카카오톡이나 알림음이 울릴 때 시스템이 `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`을 전달합니다. 이때 `setVolume(volume * 0.3f)`로 기준 볼륨 변수 자체를 깎아버리면, 복귀 후 원래 볼륨으로 돌아오지 않고 소리가 영구적으로 작아집니다.
- **해결책**:
  - `baseVolume = 0.35f`를 별도로 안전하게 보존하고, 덕킹 시에는 일시적 플래그(`isDucked = true`)만 적용합니다.
  - `AUDIOFOCUS_GAIN` 또는 `resume()` 복귀 시 항상 기준 볼륨(`baseVolume`)으로 100% 복원합니다.

### ⑤ 상태 머신(State Machine) & 자가 복구(Auto-Recovery)
- 플레이어의 상태를 4가지(`IDLE`, `PREPARING`, `PLAYING`, `PAUSED`)로 명확히 관리합니다.
- 장시간 절전이나 OS 메모리 회수로 인해 네이티브 오디오 트랙이 손상되더라도, `resume()` 시점에 이를 감지하여 **자동으로 새 플레이어를 재할당하고 트랙을 다시 시작하는 자가 복구 메커니즘**을 갖춰야 합니다.

---

## 🛠️ 2. 다른 프로젝트에 그대로 복사해서 쓸 수 있는 완성형 BGM 플레이어

### 📁 `BgmPlayer.kt`
```kotlin
package com.example.app.audio // 프로젝트 패키지명에 맞게 변경

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.random.Random

class BgmPlayer(private val context: Context) {

  // 1. 상태 머신 정의: 비동기 충돌 원천 차단
  private enum class PlayerState { IDLE, PREPARING, PLAYING, PAUSED }

  private var mediaPlayer: MediaPlayer? = null
  private var state = PlayerState.IDLE
  private var playlist: List<String> = emptyList()
  private var currentTrackIndex = 0
  private var hasCompletedFirstCycle = false
  private var isPausedByLifecycle = false
  private var shouldPlayOnPrepared = false

  // 볼륨 관리: 알림 수신(Ducking) 후에도 기준 볼륨을 절대 훼손하지 않음
  private var baseVolume = 0.35f
  private var isDucked = false

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  private var focusRequest: AudioFocusRequest? = null

  private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      AudioManager.AUDIOFOCUS_GAIN -> {
        // 알림음 등이 끝나고 포커스를 돌려받았을 때 원래 볼륨 원복
        isDucked = false
        applyEffectiveVolume()
        if (isMusicEnabled && !isPausedByLifecycle) {
          resume()
        }
      }
      AudioManager.AUDIOFOCUS_LOSS -> pause()
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
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
      if (!value) pause() else resume()
    }

  init {
    loadPlaylist()
  }

  private fun loadPlaylist() {
    val musicFiles = mutableListOf<String>()

    // 1) 외부 폴더(필요 시) 스캔
    try {
      val externalDir = File(context.getExternalFilesDir(null), "Music")
      if (externalDir.exists() && externalDir.isDirectory) {
        val files = externalDir.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }
        files?.forEach { musicFiles.add("file:" + it.absolutePath) }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error scanning external music folder", e)
    }

    // 2) assets/music 번들 폴더 스캔
    try {
      val assetList = context.assets.list("music")
      assetList?.filter { it.endsWith(".mp3", ignoreCase = true) }?.forEach {
        musicFiles.add("asset:music/$it")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error listing asset music files", e)
    }

    playlist = musicFiles
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
    if (playlist.isEmpty()) {
      loadPlaylist()
      if (playlist.isEmpty()) return
    }
    currentTrackIndex = index % playlist.size

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

        setOnPreparedListener { mp ->
          state = PlayerState.PAUSED
          applyEffectiveVolume()
          if (isMusicEnabled && (shouldPlayOnPrepared || !isPausedByLifecycle)) {
            shouldPlayOnPrepared = false
            isPausedByLifecycle = false
            requestAudioFocus()
            try {
              mp.start()
              state = PlayerState.PLAYING
            } catch (e: Exception) {
              Log.w(TAG, "Failed to start player onPrepared", e)
              onTrackEnd()
            }
          }
        }

        setOnCompletionListener { onTrackEnd() }

        setOnErrorListener { _, what, extra ->
          Log.w(TAG, "MediaPlayer error what=$what extra=$extra. Recovering next track...")
          onTrackEnd()
          true
        }

        state = PlayerState.PREPARING
        prepareAsync() // UI 스레드 멈춤 원천 차단
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

  fun resume() {
    isPausedByLifecycle = false
    shouldPlayOnPrepared = true
    isDucked = false
    applyEffectiveVolume()
    if (!isMusicEnabled) return

    val player = mediaPlayer
    if (player != null) {
      when (state) {
        PlayerState.PREPARING -> return
        PlayerState.PLAYING -> {
          applyEffectiveVolume()
          return
        }
        PlayerState.PAUSED -> {
          requestAudioFocus()
          try {
            applyEffectiveVolume()
            player.start()
            state = PlayerState.PLAYING
          } catch (e: Exception) {
            Log.w(TAG, "Failed to resume existing player, re-creating track", e)
            playCurrentTrack()
          }
        }
        PlayerState.IDLE -> playCurrentTrack()
      }
    } else {
      playCurrentTrack()
    }
  }

  fun pause() {
    isPausedByLifecycle = true
    shouldPlayOnPrepared = false
    abandonAudioFocus()
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
        am.requestAudioFocus(focusRequest!!)
      } else {
        @Suppress("DEPRECATION")
        am.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error requesting audio focus", e)
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

  fun release() {
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
```

---

## 📱 3. Activity / Lifecycle 연결 템플릿

```kotlin
class MainActivity : ComponentActivity() {

  override fun onResume() {
    super.onResume()
    // 1. 앱 복귀 시 듣던 위치부터 매끄럽게 재개
    viewModel.onResume() // 내부에서 bgmPlayer.resume() 호출
  }

  override fun onPause() {
    super.onPause()
    // 중요: onPause()에서는 BGM을 끄지 않습니다! (다이얼로그 팝업 시 끊김 방지)
  }

  override fun onStop() {
    super.onStop()
    // 2. 홈으로 완전히 빠져나가거나 화면이 꺼졌을 때만 BGM 위치 보존 및 일시정지
    viewModel.onStop() // 내부에서 bgmPlayer.pause() 호출
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    // 3. 중요: onTrimMemory()에서 BGM을 pause하지 마세요! 비트맵/그래픽 메모리만 정리합니다.
    viewModel.onTrimMemory(level)
  }

  override fun onDestroy() {
    super.onDestroy()
    // 4. 앱 프로세스 완전 종료 시 안전 릴리즈
    viewModel.onDestroy() // 내부에서 bgmPlayer.release() 호출
  }
}
```
