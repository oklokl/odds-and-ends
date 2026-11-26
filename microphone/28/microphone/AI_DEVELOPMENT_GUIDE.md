# 🤖 AI & 개발자를 위한 프로젝트 핵심 가이드 및 트러블슈팅 팁

> **이 문서는 향후 AI 어시스턴트(Claude, GPT, Gemini 등) 및 개발자가 본 프로젝트(`microphone`)를 유지보수하거나 새 기능을 추가할 때 발생할 수 있는 시행착오를 방지하기 위해 작성된 핵심 기술 가이드입니다.**

---

## 📌 1. Gradle & AGP 9.x / 10.0 빌드 시스템 절대 규칙

### ⚠️ [CRITICAL] Built-in Kotlin 적용 상태 유지
본 프로젝트는 **AGP 9.0+의 기본 사양인 Built-in Kotlin(`android.builtInKotlin=true`)과 신규 공개 DSL(`android.newDsl=true`)**을 사용하고 있습니다.

1. **`org.jetbrains.kotlin.android` 플러그인을 절대 다시 추가하지 마십시오.**
   * 만약 `build.gradle.kts`나 `libs.versions.toml`에 `id("org.jetbrains.kotlin.android")`를 추가하면, 구형 플러그인이 `BaseExtension`을 참조하여 다음과 같은 치명적인 에러가 발생합니다:
     ```text
     ClassCastException: ApplicationExtensionImpl cannot be cast to BaseExtension
     ```
   * Kotlin 컴파일은 AGP가 내장 기능으로 직접 처리하므로 `plugins` 블록에는 `alias(libs.plugins.android.application)`와 Compose 플러그인(`alias(libs.plugins.kotlin.compose)`)만 적용되어야 합니다.

2. **`android.newDsl=false` 또는 `android.builtInKotlin=false` 같은 임시 우회 플래그를 추가하지 마십시오.**
   * AGP 10.0(2026년 중반 이후)에서는 이 opt-out 플래그들이 영구 제거되므로, 반드시 최신 표준 DSL 인터페이스(`ApplicationExtension`)를 유지해야 합니다.
   * `build.gradle.kts`에서는 임시 우회 문법(`configure<ApplicationExtension>`) 대신 표준 `android { ... }` 블록을 그대로 사용하면 됩니다.

3. **`kotlin.compilerOptions.jvmTarget`은 별도로 설정하지 않아도 됩니다.**
   * Built-in Kotlin 환경에서는 `android.compileOptions.targetCompatibility` (Java 11) 설정값과 JVM 바이트코드 타깃이 자동으로 연동됩니다.

---

## 🎵 2. MP3 인코딩 & 16 KB Page-size 호환성 가이드

### ⚠️ [CRITICAL] Pure Java LAME 인코더 및 스텁 클래스 보존
Android OS는 하드웨어 가속 AAC 인코더는 기본 탑재하고 있지만, **기본 MP3 인코더(`audio/mpeg`)는 AOSP 표준에 포함되어 있지 않습니다** (대부분의 삼성, 픽셀 기기에 MP3 인코더 부재).

1. **파이프라인 동작 방식 (`AudioConverter.kt`)**:
   * 녹음 중에는 시스템의 하드웨어 가속 `MediaRecorder`를 통해 고음질 AAC(`.m4a`)로 임시 녹음합니다.
   * 사용자가 저장 시 포맷이 MP3인 경우, `AudioConverter.convertM4aToMp3()`가 백그라운드 코루틴에서 실행됩니다.
   * `MediaExtractor` + `MediaCodec`(AAC 디코더)로 PCM 16비트 오디오를 추출하면서, 순수 Java LAME 인코더(`jump3r`)로 파이프라인 전달하여 고음질 MP3 파일로 즉시 인코딩합니다.
   * 변환이 성공적으로 끝나면 임시 `.m4a` 파일은 즉시 디스크에서 삭제됩니다.

2. **16 KB 페이지 크기(Android 15+) 요건 준수**:
   * C/C++ NDK 바이너리(`.so` 파일)가 포함된 외부 라이브러리(ffmpeg, lame-jni 등)를 절대 무분별하게 추가하지 마십시오. 16 KB 페이지 크기 불일치로 Google Play 등록 및 Android 15+ 기기에서 크래시가 발생할 수 있습니다.
   * 현재 사용 중인 `de.sciss:jump3r`는 순수 Java 바이트코드 라이브러리이므로 16 KB 요건을 100% 만족합니다.

3. **`javax.sound.sampled.AudioFormat.java` 스텁 클래스 절대 삭제 금지**:
   * 위치: `app/src/main/java/javax/sound/sampled/AudioFormat.java`
   * 배경: `jump3r` 라이브러리의 lowlevel LAME 인코더는 내부적으로 `AudioFormat` 클래스를 매개변수로 사용합니다. 그러나 Android SDK(Dalvik/ART)에는 데스크톱 Java의 `java.desktop` 모듈이 없습니다.
   * 따라서 이 가벼운 순수 POJO 스텁 클래스가 Android에서 `jump3r`를 구동시키는 핵심 브릿지 역할을 합니다. 절대로 삭제하지 마십시오.

---

## 📂 3. 저장소 정책 & 파일 내보내기 (Scoped Storage)

### 💡 왜 'Music'이 아닌 'Download' 폴더로 내보내는가?
* 사용자가 스마트폰의 기본 '파일(Files)' 앱을 열면 **가장 첫 화면으로 나타나는 기본 디렉터리가 바로 `Downloads`**입니다.
* 기존에 `Music/` 폴더로 내보냈을 때는 사용자가 파일 앱의 깊은 경로(`Audio` 또는 기기 저장공간 `Music/`)를 찾지 못해 "파일이 안 옮겨진다"고 오해하는 UX 문제가 발생했습니다.
* **해결책**:
  * `RecordingRepository.exportRecordingToDownloads()`에서 `MediaStore.Downloads` API를 사용하여 공용 `Download/` 폴더로 직접 복사합니다.
  * 복사 후 `MediaScannerConnection.scanFile()`을 호출하여 시스템 파일 앱에 즉시 갱신 반영되도록 보장합니다.

### 🗑️ 파일 삭제 및 기기 저장 공간 확보 원칙
* 앱 전용 저장소(`/Android/data/com.krdonon.microphone/files/Music/krdondon_mic/`)에 저장된 파일은 사용자가 앱 목록에서 '완전 삭제'하거나 '휴지통 비우기'를 수행할 때 `file.delete()`를 통해 **스마트폰 디스크에서 실제 바이트가 영구 삭제**되어야 합니다.
* 사용자가 이미 `Download/` 폴더로 내보낸 파일은 사용자의 개인 보관용 복사본이므로, 앱 목록에서 삭제해도 Download 폴더의 복사본은 보존되고 앱 내부 공간만 정리됩니다.

---

## 🎧 4. 오디오 재생 & 모달 팝업 & A-B 구간 반복 아키텍처

### 1. 상태 관리 (`PlaybackStateManager.kt`)
* 재생 상태는 전역 StateFlow 싱글톤인 `PlaybackStateManager`에서 관리됩니다:
  * `currentRecording`: 현재 재생 중인 `RecordingFile`
  * `isPlaying`: 재생 / 일시정지 여부
  * `currentPosition`: 현재 재생 위치(ms)
  * `duration`: 전체 길이(ms)
  * `repeatA`, `repeatB`, `isRepeatActive`: A-B 구간 반복 상태

### 2. 백그라운드 재생 서비스 (`PlaybackService.kt`)
* `MediaPlayer`를 백그라운드 포그라운드 서비스에서 실행하여 화면이 꺼져도 재생이 유지됩니다.
* **진행 시간 폴링 루프**: 100ms마다 `mediaPlayer.currentPosition`을 읽어 `PlaybackStateManager.updatePosition()`을 호출합니다.
* **구간 반복 루프**: `isRepeatActive`가 true이고 `currentPosition >= repeatB`인 경우, `mediaPlayer.seekTo(repeatA)`를 호출하여 지정된 A 지점으로 즉시 점프합니다.
* **재생 완료 처리 (`onPlaybackFinished`)**: 오디오가 끝까지 재생되었을 때 팝업을 닫지 않고, 위치를 0초로 돌려놓고 일시정지(`isPlaying = false`) 상태로 대기하여 사용자가 언제든 다시 재생하거나 시크바를 조작할 수 있도록 합니다.

### 3. 모달 다이얼로그 팝업 (`AudioPlayerPopup.kt`)
* ⚠️ **하단 고정 바가 아닌 중앙 `Dialog`를 사용하는 이유**:
  * 스마트폰 하단 소프트웨어 내비게이션 바(뒤로가기, 홈 버튼, 제스처 영역)와의 겹침/가림 현상을 원천 방지하기 위함입니다.
  * 뒷배경이 반투명하게 어두워지는 딤드(Dimmed) 효과가 자동 적용되어 사용자가 오디오 제어(시크바, A-B 구간 반복)에 완벽히 집중할 수 있습니다.
  * 외부 어두운 배경 터치 시 또는 우측 상단 `✕` 터치 시 안전하게 재생이 정지되며 팝업이 닫힙니다.

---

## 🎙️ 5. 상단 / 하단 마이크 및 전화 차단 구현 원리

1. **상단 / 하단 마이크 소스 매핑 (`AudioRecorder.kt`)**:
   * Android `MediaRecorder`는 하드웨어 포트를 직접 지정하는 enum이 없습니다.
   * **상단 마이크**: `MediaRecorder.AudioSource.CAMCORDER` (대부분의 기기에서 상단/후면 마이크 지향)
   * **하단 마이크**: `MediaRecorder.AudioSource.MIC` (기본 통화용 하단 마이크)

2. **녹음 중 전화 차단 (`RecordingService.kt`)**:
   * `READ_PHONE_STATE` 및 `ANSWER_PHONE_CALLS` 권한을 통해 `TelephonyManager.CALL_STATE_RINGING` 감지.
   * 벨소리가 울리는 즉시 `telephonyManager.endCall()` 리플렉션을 호출하여 전화를 거절함으로써 녹음의 연속성을 유지합니다. (통화 내용 녹음이나 통화 기록 저장은 일절 수행하지 않음).

---

## 🛡️ 6. Google Play 정책 및 온디바이스(로컬) 원칙
* 본 앱은 **인터넷 통신 권한(`INTERNET`)이 아예 선언되어 있지 않습니다.**
* 광고 SDK(AdMob), 분석 SDK(Firebase Analytics, GA) 등 어떠한 외부 트래커도 추가하지 마십시오.
* 개인정보 처리방침은 `app/src/main/assets/microphone_updated.html`에 완전한 한국어 전문으로 유지되어야 합니다.

---

## 💡 7. 빠른 검증 명령어
코드 수정 후에는 반드시 아래 Gradle 명령어들을 통해 검증을 수행하십시오:
```bash
# 1. 컴파일 및 유닛 테스트 (LAME MP3 인코딩 검증)
./gradlew app:testDebugUnitTest

# 2. 디버그 APK 빌드 검증
./gradlew app:assembleDebug

# 3. 릴리스 APK (R8 / ProGuard 난독화 최적화) 빌드 검증
./gradlew app:assembleRelease
```
