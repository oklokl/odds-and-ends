# 🎵 완벽한 Android BGM & 오디오 생명주기 아키텍처 설계 가이드

이 문서는 안드로이드 앱 개발 시 가장 빈번하게 발생하는 **오디오 먹통(블루투스 해제, 스피커 전환, 장시간 화면 잠금, 최근 앱 목록 전환 후 복귀 시 무음 현상)**을 근본적으로 방지하고 완벽하게 복구할 수 있도록 검증된 **아키텍처 설계 원칙과 재사용 가능한 코드 템플릿**을 정리한 가이드입니다.

다른 프로젝트(게임, 유틸리티, 힐링 앱 등)를 개발할 때 그대로 복사하여 적용할 수 있습니다.

---

## 💡 1. 왜 이런 문제가 발생하며, 어떻게 설계해야 하는가?

안드로이드의 `MediaPlayer`는 단순한 Java/Kotlin 객체가 아니라, **OS 네이티브 오디오 서버(`audioserver`) 및 하드웨어 드라이버**와 직접 맞물려 동작합니다. 따라서 다음과 같은 3가지 핵심 설계 원칙을 지켜야 합니다.

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

### ③ 상태 머신(State Machine) & 자가 복구(Auto-Recovery)
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
import android.media.MediaPlayer
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
  private var volume = 0.35f // 효과음이 묻히지 않는 최적의 잔잔한 BGM 볼륨

  var isMusicEnabled: Boolean = true
    set(value) {
      field = value
      if (!value) pause() else resume()
    }

  init {
    loadPlaylist()
  }

  /**
   * assets/music 또는 외부 폴더에서 음원을 스캔하여 플레이리스트를 구성합니다.
   */
  private fun loadPlaylist() {
    val musicFiles = mutableListOf<String>()

    // 1) 외부 폴더(필요 시) 스캔
    try {
      val externalDir = File(context.getExternalFilesDir(null), "Music")
      if (externalDir.exists() && externalDir.isDirectory) {
        val files = externalDir.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }
        files?.forEach { musicFiles.add("file:" + it.absolutePath) }
      }
    } catch (_: Exception) {}

    // 2) assets/music 번들 음원 스캔
    try {
      val assetList = context.assets.list("music")
      assetList?.filter { it.endsWith(".mp3", ignoreCase = true) }?.forEach {
        musicFiles.add("asset:music/$it")
      }
    } catch (_: Exception) {}

    playlist = musicFiles
  }

  fun setVolume(vol: Float) {
    volume = vol.coerceIn(0f, 1f)
    try {
      mediaPlayer?.setVolume(volume, volume)
    } catch (_: Exception) {}
  }

  /**
   * 지정된 인덱스의 트랙을 비동기 준비 후 재생합니다.
   */
  fun playTrack(index: Int) {
    if (playlist.isEmpty()) {
      loadPlaylist()
      if (playlist.isEmpty()) return
    }
    currentTrackIndex = index % playlist.size

    // 기존 리소스 안전 해제
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

        // [핵심] 비동기 준비 완료 콜백 (블루투스/스피커 전환 락 방지)
        setOnPreparedListener { mp ->
          state = PlayerState.PAUSED
          if (isMusicEnabled && !isPausedByLifecycle) {
            try {
              mp.start()
              state = PlayerState.PLAYING
            } catch (e: Exception) {
              onTrackEnd()
            }
          }
        }

        // 곡 종료 시: 1회차 순차 재생 -> 그 후 무한 셔플
        setOnCompletionListener {
          onTrackEnd()
        }

        // 에러 발생 시 자동 다음 곡 회복
        setOnErrorListener { _, _, _ ->
          onTrackEnd()
          true
        }

        state = PlayerState.PREPARING
        prepareAsync() // [핵심] UI 렉 방지 및 비동기 준비
      }
      mediaPlayer = player
    } catch (e: Exception) {
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
   * [핵심] 앱 복귀 시 (onResume):
   * 기존 플레이어가 살아있으면 그 자리에서 즉시 이어 재생하고,
   * 절전/장시간 대기로 플레이어가 손상되었거나 IDLE이면 즉시 새 트랙을 준비하여 복구합니다.
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
            playCurrentTrack()
          }
        }
        PlayerState.IDLE -> playCurrentTrack()
      }
    } else {
      playCurrentTrack()
    }
  }

  /**
   * [핵심] 앱 화면을 벗어날 때 (onStop):
   * 홈 버튼, 화면 잠금, 최근 앱 전환 시 호출되어 위치를 온전히 보존합니다.
   */
  fun pause() {
    isPausedByLifecycle = true
    val player = mediaPlayer ?: return
    try {
      if (state == PlayerState.PLAYING || player.isPlaying) {
        player.pause()
        state = PlayerState.PAUSED
      }
    } catch (_: Exception) {}
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
    } catch (_: Exception) {}
    mediaPlayer = null
  }
}
```

---

## 📱 3. Activity / ViewModel 생명주기 연결 템플릿

### 📁 `MainActivity.kt`
```kotlin
class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onResume() {
    super.onResume()
    // 앱으로 복귀했을 때 음악 재개 (기존 위치 이어 재생 또는 자동 복구)
    viewModel.bgmPlayer.resume()
  }

  override fun onPause() {
    super.onPause()
    // [중요] 다이얼로그나 설정 팝업 시 음악이 끊기지 않도록 여기서는 음악을 멈추지 않습니다!
  }

  override fun onStop() {
    super.onStop()
    // [중요] 홈 버튼, 화면 잠금, 최근 앱(Task Switcher) 목록으로 완전히 나갈 때만 정지!
    viewModel.bgmPlayer.pause()
  }
}
```

---

## 🎯 4. 다른 프로젝트 적용 체크리스트

1. **음원 파일 위치**:
   - `app/src/main/assets/music/` 디렉터리에 `.mp3` 파일들을 넣으면 별도 설정 없이 자동 인식됩니다.
2. **소리 볼륨 밸런스**:
   - 게임 버튼 클릭 효과음(SoundPool)이 묻히지 않도록 기본 BGM 볼륨은 `0.35f ~ 0.4f`를 유지하는 것이 가장 자연스럽습니다.
3. **재생 로직 보장**:
   - 1회차는 모든 수록곡이 순서대로 정주행되고, 2회차부터는 직전 곡과 겹치지 않는 무한 랜덤 셔플로 이어져 사용자가 질리지 않고 음악을 감상할 수 있습니다.
