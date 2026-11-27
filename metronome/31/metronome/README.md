# K메트로놈 (K-Metronome)

안드로이드용 메트로놈 앱입니다. 이 문서는 2026-09-28 기준의 **AudioTrack + PCM WAV 오디오 엔진** 구조를 기준으로 정리했습니다.

> 중요: 예전 문서에 있던 `strong.mp3` / `weak.mp3` 기반 설명은 더 이상 현재 재생 엔진의 기준이 아닙니다. 현재 실제 메트로놈 소리는 `strong_pcm.wav` / `weak_pcm.wav`를 사용합니다.

## 현재 프로젝트 기준

- applicationId: `com.krdonon.metronome`
- minSdk: 26
- targetSdk / compileSdk: 37
- versionCode: 25
- versionName: 25.0
- UI: Jetpack Compose
- 백그라운드 재생: Foreground Service + MediaSession
- 오디오 엔진: `AudioTrack`
- 기본 오디오 포맷: 48 kHz / 16-bit / Stereo / PCM WAV

## 주요 기능

- 박자 수(Beat): 1–16
- 박 단위(Unit): 1, 2, 4, 8, 16
- BPM: 40–440
- 강박 / 약박 분리
- 원형 박자 애니메이션
- 사운드 세트 변경 (`set0`, `set1`, ...)
- 포그라운드 서비스로 백그라운드 재생 유지
- 알림바 일시정지 / 정지
- 강박 플래시 옵션
- 진동 모드
- WakeLock을 사용해 장시간 재생 중 절전 영향 완화

---

# 2026-09 오디오 타이밍 구조 변경

## 왜 바꿨는가

이전 구현에서는 매 박마다 Java/Kotlin 타이머가 `SoundPool.play()`를 호출했습니다. 내부 BPM 계산이 맞더라도 실제 출력 단계에서 다음과 같은 문제가 발생할 수 있었습니다.

- 일정하게 가다가 갑자기 두세 박이 몰려 들림
- `단-다다다-단-다단...`처럼 리듬을 타는 것처럼 들림
- 강박 직전 약박이 빨라진 것처럼 들림
- 어떤 때는 정상이고 어떤 때는 버퍼링처럼 흔들림
- 타이머가 늦은 뒤 다음 박에서 시간을 따라잡으려 하면서 박 간격이 짧아짐
- MP3별 앞부분 무음/인코딩 특성이 실제 타격 시점을 다르게 만듦

메트로놈은 평균 BPM만 맞는 것으로는 부족합니다. **각 클릭 사이 간격이 일정해야** 합니다.

## 현재 해결 방식

현재는 매 박마다 소리를 호출하지 않습니다.

1. `strong_pcm.wav`와 `weak_pcm.wav`를 메모리에 읽습니다.
2. 한 마디 전체를 PCM 샘플 배열로 미리 만듭니다.
3. 각 박의 시작 위치를 샘플 프레임 단위로 계산합니다.
4. 일반적인 경우 `AudioTrack.MODE_STATIC`에 한 마디 전체를 넣습니다.
5. `setLoopPoints()`로 오디오 레이어에서 무한 반복합니다.
6. Java/Kotlin 타이머는 화면, 플래시, 진동 표시용으로만 사용합니다.

즉, 소리의 시간축을 UI/Handler 스케줄링에서 분리했습니다.

### 예: 7/8, 120 BPM

현재 앱의 계산식은 다음과 같습니다.

`한 박 시간 = 60 / BPM × (4 / Unit)`

따라서 Unit=8, BPM=120이면:

- 한 박 = 0.25초 = 250ms
- 48 kHz 기준 한 박 = 12,000 frames
- 7박 한 마디 = 84,000 frames = 1.75초

배치는 다음과 같습니다.

```text
강      약      약      약      약      약      약      다음 강
0ms    250     500     750    1000    1250    1500     1750ms
```

이 위치가 오디오 데이터 안에 미리 고정되므로 UI가 잠깐 바빠져도 클릭 간격이 갑자기 `다다다`로 몰리지 않습니다.

---

# 현재 사운드 파일 구조

```text
app/src/main/assets/sounds/
  ├── set0/
  │   ├── strong_pcm.wav   ← 실제 재생에 사용
  │   ├── weak_pcm.wav     ← 실제 재생에 사용
  │   ├── strong.mp3       ← 원본 보관용, 현재 엔진은 사용하지 않음
  │   └── weak.mp3         ← 원본 보관용, 현재 엔진은 사용하지 않음
  ├── set1/
  │   ├── strong_pcm.wav
  │   ├── weak_pcm.wav
  │   ├── strong.mp3
  │   └── weak.mp3
  └── ...
```

현재 프로젝트에는 `set0`부터 `set11`까지 존재합니다.

새 사운드 세트를 만들 때 핵심 파일은 반드시 아래 두 개입니다.

```text
strong_pcm.wav
weak_pcm.wav
```

권장/요구 형식:

- WAV 컨테이너
- PCM (비압축)
- 48,000 Hz
- 16-bit
- Stereo 2ch
- Little-endian PCM

**확장자만 MP3 → WAV로 바꾸면 안 됩니다.** 실제 PCM WAV로 변환해야 합니다.

---

# 핵심 Kotlin 파일

## `SoundManager.kt`

현재 실제 소리의 정확도를 담당하는 핵심입니다.

주요 책임:

- WAV 검증 및 로딩
- 한 마디 PCM 합성
- 강박/약박 샘플 위치 계산
- 샘플 겹침 시 saturating mix
- `AudioTrack.MODE_STATIC` 반복 재생
- 큰 마디나 기기 제약 시 `MODE_STREAM` 폴백
- 사운드 세트 전환

**이 파일을 예전 SoundPool 방식으로 되돌리지 마십시오.**

## `MetronomeService.kt`

서비스, 상태, 알림, UI용 틱을 관리합니다.

현재 중요한 원칙:

- 실제 일반 오디오는 `SoundManager.startLoop()`가 담당
- Handler 틱은 화면/플래시/진동에 사용
- 오디오 스레드는 `THREAD_PRIORITY_URGENT_AUDIO`
- WakeLock은 재생 중 서비스가 직접 해제할 때까지 유지
- 늦어진 UI 틱을 무리하게 따라잡지 않도록 방어 로직 유지

---

# 앱 권한

현재 `AndroidManifest.xml`에 다음 권한이 있습니다.

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FLASHLIGHT" />
<uses-permission android:name="android.permission.VIBRATE" />
```

카메라 하드웨어 및 플래시는 `required="false"`로 선언되어 있습니다.

> 권한이나 Google Play 정책 관련 설명은 실제 앱 동작과 최신 Play 정책을 다시 확인한 뒤 수정해야 합니다. 문서에 적혀 있다는 이유만으로 정책 승인을 보장하지 않습니다.

---

# 유지보수 시 절대 잊지 말 것

1. 오디오 정확도를 위해 **매 박마다 `SoundPool.play()`를 호출하는 구조로 되돌리지 않는다.**
2. 새 세트는 `strong_pcm.wav`, `weak_pcm.wav`를 만든다.
3. 두 WAV는 반드시 48 kHz / PCM16 / Stereo로 맞춘다.
4. 파일 앞쪽에 불필요한 무음이 길게 있지 않은지 확인한다.
5. UI 애니메이션, 플래시, 로그, SharedPreferences I/O를 오디오 출력 경로에 넣지 않는다.
6. BPM/Unit/Beat 변경 시 한 마디 PCM을 재구성하는 현재 구조를 유지한다.
7. "평균 BPM"만 보지 말고 실제 클릭 간격이 일정한지 귀와 측정으로 확인한다.
8. 특히 7/8, 5/8, 11/8처럼 강박 주기가 눈에 잘 띄는 설정으로 회귀 테스트한다.

자세한 원인 분석은 `AUDIO_TIMING_FIX_NOTES.md`, 사운드 추가 방법은 `SOUND_SET_GUIDE.md`, 업데이트 전 확인 사항은 `MAINTENANCE_CHECKLIST.md`를 참고하십시오.
