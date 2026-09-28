# ExorcismPrayer Android 앱 구조 분석

## 개요
이 프로젝트는 **Jetpack Compose 기반의 안드로이드 오디오 재생 앱**입니다.  
주요 목적은 여러 기도문 오디오(`res/raw/*.mp3`)를 순차적으로 재생하고, 각 항목의 제목과 본문(설명/기도문 텍스트)을 함께 보여주는 것입니다.

기술적으로는 다음 구성을 사용합니다.

- **UI**: Jetpack Compose
- **오디오 재생**: AndroidX Media3 (`ExoPlayer`, `MediaSession`, `MediaController`)
- **백그라운드 재생 서비스**: `MediaSessionService` 기반 포그라운드 서비스
- **리소스 구성**: 문자열/색상/테마/XML 설정 + `raw` 오디오 파일

---

## 프로젝트 핵심 구조

```text
ExorcismPrayer/
├─ app/
│  ├─ src/
│  │  ├─ main/
│  │  │  ├─ java/com/krdondon/exorcismprayer/
│  │  │  │  ├─ MainActivity.kt
│  │  │  │  ├─ MediaData.kt
│  │  │  │  ├─ MediaScreen.kt
│  │  │  │  ├─ PlaybackService.kt
│  │  │  │  └─ ui/theme/
│  │  │  │     ├─ Color.kt
│  │  │  │     ├─ Theme.kt
│  │  │  │     └─ Type.kt
│  │  │  ├─ AndroidManifest.xml
│  │  │  └─ res/
│  │  │     ├─ drawable/
│  │  │     ├─ mipmap-anydpi-v26/
│  │  │     ├─ raw/
│  │  │     ├─ values/
│  │  │     └─ xml/
│  │  ├─ androidTest/
│  │  └─ test/
│  └─ build.gradle.kts
├─ build.gradle.kts
├─ settings.gradle.kts
└─ gradle/libs.versions.toml
```

---

## Kotlin 파일 구조 목록 및 설명

### 1. `app/src/main/java/com/krdondon/exorcismprayer/MainActivity.kt`
**역할:** 앱의 진입점(Activity)이며, Compose UI를 띄우고 `PlaybackService`와 연결하는 컨트롤 허브입니다.

**주요 기능**
- 앱 시작 시 `PlaybackService`를 foreground service로 실행
- `MediaController`를 비동기로 생성하여 서비스의 `MediaSession`에 연결
- 현재 재생 상태(`isPlaying`)와 현재 미디어 인덱스(`currentMediaIndex`)를 Compose 상태로 관리
- `MediaScreen`에 재생 상태와 이벤트 콜백 전달
- Activity 종료 시 서비스 정리

**구조 설명**
- `onCreate()`
  - `enableEdgeToEdge()` 적용
  - `PlaybackService` 시작
  - Compose UI 초기화
- Lifecycle 처리
  - `ON_START`에서 `MediaController` 생성
  - `ON_STOP`에서 `MediaController` 해제
- `Player.Listener`
  - 트랙 변경 시 현재 인덱스 갱신
  - 재생/정지 상태 변경 시 UI 상태 갱신

**비고**
- 서비스와 UI를 직접 결합하지 않고 `MediaController`를 통해 제어하는 점이 구조적으로 깔끔합니다.
- `onDestroy()`에서 `stopService()`를 호출하므로 Activity가 완전히 종료될 때 재생 서비스도 함께 종료되도록 설계되어 있습니다.

---

### 2. `app/src/main/java/com/krdondon/exorcismprayer/MediaData.kt`
**역할:** 재생할 기도 콘텐츠의 데이터 모델과 정적 목록을 정의합니다.

**포함 내용**
- `data class MediaItem`
  - `title`: 제목
  - `resourceId`: `res/raw` 오디오 리소스 ID
  - `description`: 화면에 표시할 본문/설명
- `mediaList`
  - 실제 재생 항목 목록
  - 각 항목은 제목 + mp3 리소스 + 텍스트 설명으로 구성

**구조적 의미**
- UI와 재생 로직이 이 파일의 데이터를 참조하므로, 사실상 앱의 콘텐츠 원본(source of truth) 역할을 합니다.
- 기도문 추가/수정 시 가장 먼저 손대야 할 파일입니다.

**연결 리소스**
- `R.raw.prayer_a_0` ~ `R.raw.prayer_b_4` 등의 mp3와 매핑됩니다.

---

### 3. `app/src/main/java/com/krdondon/exorcismprayer/MediaScreen.kt`
**역할:** 실제 화면 UI를 담당하는 Compose Composable입니다.

**주요 기능**
- 현재 선택된 기도 제목 표시
- 현재 선택된 기도문 본문(설명) 표시
- 이전 / 재생·정지 / 다음 버튼 제공
- 현재 인덱스가 바뀌면 스크롤을 맨 위로 이동
- 최초 진입 시 `mediaController`에 `mediaList`를 Media3 `MediaItem` 목록으로 세팅
- 반복 재생 모드를 `REPEAT_MODE_ONE`으로 설정

**구성 요소**
- 상단: 현재 기도 제목 박스
- 중앙: 본문 스크롤 영역
- 하단: 이전 / 재생(정지) / 다음 버튼 3개

**중요 포인트**
- 이 앱은 XML 레이아웃을 쓰지 않고, 화면이 전부 Compose 코드로 작성되어 있습니다.
- `mediaController.mediaItemCount == 0`일 때만 미디어 목록을 주입하므로 중복 초기화를 방지합니다.
- `android.resource://패키지명/리소스ID` URI를 사용해 로컬 raw 리소스를 재생합니다.

---

### 4. `app/src/main/java/com/krdondon/exorcismprayer/PlaybackService.kt`
**역할:** 백그라운드 오디오 재생을 담당하는 포그라운드 서비스입니다.

**주요 기능**
- `ExoPlayer` 생성 및 오디오 속성 설정
- `MediaSession` 생성
- 알림 채널 생성 및 foreground notification 표시
- 외부(`MainActivity`의 `MediaController`)에서 제어할 수 있도록 세션 제공

**구조 설명**
- `onCreate()`
  - 알림 채널 생성
  - `ExoPlayer` 초기화
  - `MediaSession` 생성
  - `startForeground()` 호출
- `createNotification()`
  - 앱 이름과 상태 텍스트를 표시하는 알림 생성
- `onGetSession()`
  - 서비스에 접속한 컨트롤러에게 `MediaSession` 제공
- `onDestroy()`
  - `player`, `mediaSession` 해제

**중요 포인트**
- 오디오 앱에서 필요한 백그라운드 재생 구조가 반영되어 있습니다.
- 알림의 내용 텍스트는 현재 `준비 중...`으로 고정되어 있으며, 재생 중 곡명 동기화 로직은 구현되어 있지 않습니다.

---

### 5. `app/src/main/java/com/krdondon/exorcismprayer/ui/theme/Color.kt`
**역할:** Compose 테마용 색상 상수를 정의합니다.

**설명**
- 기본 템플릿에서 생성된 Material 색상 세트가 들어 있습니다.
- 실제 화면 배경은 여기보다 `res/values/colors.xml`의 `background_color`가 더 직접적으로 사용됩니다.

---

### 6. `app/src/main/java/com/krdondon/exorcismprayer/ui/theme/Theme.kt`
**역할:** 앱의 Compose MaterialTheme 구성을 담당합니다.

**설명**
- 다크/라이트 컬러 스킴 선택
- Android 12 이상에서 Dynamic Color 지원
- `Typography` 적용

**구조적 의미**
- Compose 전역 테마 진입점입니다.
- 실제 UI 색상은 일부만 테마를 따르고, 일부는 직접 `Color.White`, `Color.Black` 등을 사용하고 있습니다.

---

### 7. `app/src/main/java/com/krdondon/exorcismprayer/ui/theme/Type.kt`
**역할:** Compose Typography 설정 파일입니다.

**설명**
- `bodyLarge` 텍스트 스타일을 정의
- 기본 Material 3 타이포그래피 확장용 구조

---

### 8. `app/src/test/java/com/krdondon/exorcismprayer/ExampleUnitTest.kt`
**역할:** 기본 샘플 단위 테스트 파일입니다.

**설명**
- 프로젝트 생성 시 포함되는 예제 테스트로 보이며, 현재 앱 핵심 로직 검증과는 직접적인 관련이 없습니다.

---

### 9. `app/src/androidTest/java/com/krdondon/exorcismprayer/ExampleInstrumentedTest.kt`
**역할:** 기본 샘플 계측 테스트 파일입니다.

**설명**
- 디바이스/에뮬레이터에서 실행되는 예제 테스트입니다.
- 현재 기능 분석 기준에서는 부가 파일입니다.

---

## XML 파일 구조 목록 및 설명

### 1. `app/src/main/AndroidManifest.xml`
**역할:** 앱 컴포넌트 및 권한 선언의 중심 파일입니다.

**주요 선언 내용**
- 권한
  - `FOREGROUND_SERVICE`
  - `POST_NOTIFICATIONS`
  - `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- 서비스
  - `.PlaybackService`
  - `foregroundServiceType="mediaPlayback"`
- 액티비티
  - `.MainActivity`
  - `MAIN/LAUNCHER` 인텐트 필터

**구조적 의미**
- 이 앱이 단순 화면 앱이 아니라, 백그라운드 재생 가능한 미디어 앱이라는 점을 보여줍니다.

---

### 2. `app/src/main/res/values/strings.xml`
**역할:** 문자열 리소스 정의 파일입니다.

**내용**
- `app_name = "구마경"`

**설명**
- 앱 이름 관리용 최소 구성입니다.

---

### 3. `app/src/main/res/values/colors.xml`
**역할:** XML 리소스 색상 정의 파일입니다.

**주요 값**
- 기본 템플릿 색상들
- `background_color = #CCE4C3`

**설명**
- 실제 `MediaScreen`에서 배경색으로 `background_color`를 사용합니다.

---

### 4. `app/src/main/res/values/themes.xml`
**역할:** Android View 시스템용 앱 테마 정의 파일입니다.

**설명**
- `Theme.ExorcismPrayer`가 `android:Theme.Material.Light.NoActionBar`를 상속합니다.
- Compose 앱이지만, 매니페스트 수준의 기본 앱 테마는 여전히 XML로 지정됩니다.

---

### 5. `app/src/main/res/xml/backup_rules.xml`
**역할:** 앱 백업 정책 정의 파일입니다.

**설명**
- 현재는 샘플 상태에 가까우며, 실질적인 include/exclude 규칙은 거의 비어 있습니다.
- 자동 백업 세부 정책이 필요할 때 수정할 수 있는 자리입니다.

---

### 6. `app/src/main/res/xml/data_extraction_rules.xml`
**역할:** Android 12+ 데이터 추출/복원 규칙 정의 파일입니다.

**설명**
- 현재는 기본 템플릿 수준입니다.
- 클라우드 백업 및 기기 간 데이터 이전 정책을 세부 제어할 때 활용됩니다.

---

### 7. `app/src/main/res/drawable/ic_launcher_background.xml`
**역할:** 런처 아이콘 배경 벡터 리소스입니다.

**설명**
- 앱 아이콘 구성 요소 중 배경 부분입니다.
- 기능 로직과는 직접 관련이 없습니다.

---

### 8. `app/src/main/res/drawable/ic_launcher_foreground.xml`
**역할:** 런처 아이콘 전경 벡터 리소스입니다.

**설명**
- 앱 아이콘 구성 요소 중 전경 부분입니다.
- 기능 로직과는 직접 관련이 없습니다.

---

### 9. `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
**역할:** 적응형(adaptive) 런처 아이콘 정의 파일입니다.

**설명**
- foreground/background 리소스를 조합해 런처 아이콘을 구성합니다.

---

### 10. `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
**역할:** 원형 런처 아이콘 정의 파일입니다.

**설명**
- round icon 지원 런처에서 사용됩니다.

---

## `res/raw` 오디오 리소스 목록

이 앱의 실제 콘텐츠는 아래 mp3 파일들과 `MediaData.kt`의 매핑으로 구성됩니다.

- `app/src/main/res/raw/prayer_a_0.mp3`
- `app/src/main/res/raw/prayer_a_1.mp3`
- `app/src/main/res/raw/prayer_a_2.mp3`
- `app/src/main/res/raw/prayer_a_3.mp3`
- `app/src/main/res/raw/prayer_a_4.mp3`
- `app/src/main/res/raw/prayer_b_1.mp3`
- `app/src/main/res/raw/prayer_b_2.mp3`
- `app/src/main/res/raw/prayer_b_3.mp3`
- `app/src/main/res/raw/prayer_b_4.mp3`

오디오 파일 이름 체계상, A/B 그룹으로 나뉜 기도 시퀀스를 재생하는 구조로 보입니다.

---

## 앱 동작 흐름 정리

1. 사용자가 앱 실행
2. `MainActivity`가 시작되면서 `PlaybackService`를 foreground service로 실행
3. Activity가 `MediaController`를 생성하여 서비스의 `MediaSession`에 연결
4. `MediaScreen`이 `mediaList`를 Media3 재생 목록으로 등록
5. 사용자가 이전 / 재생 / 다음 버튼으로 항목 제어
6. 선택된 항목의 제목과 기도문 본문이 화면에 표시됨
7. 실제 오디오는 `res/raw`의 mp3에서 재생됨

---

## 빌드 및 기술 스택 요약

### Gradle / Android 설정
- `minSdk = 26`
- `targetSdk = 36`
- `compileSdk = 36`
- Java/Kotlin JVM Toolchain = 11
- 버전명: `6.0`
- 버전코드: `6`

### 주요 라이브러리
- `androidx.activity:activity-compose`
- `androidx.compose.material3`
- `androidx.media3:media3-exoplayer`
- `androidx.media3:media3-session`
- `androidx.media3:media3-ui`

---

## 구조적 평가

### 장점
- Compose + Media3 조합으로 최신 안드로이드 앱 구조를 따르고 있음
- UI(`MediaScreen`), 데이터(`MediaData`), 재생 서비스(`PlaybackService`) 역할 분리가 비교적 명확함
- 로컬 리소스 기반이라 네트워크 의존성이 없음
- MediaSession 기반이라 확장성이 좋음

### 특징
- 화면 레이아웃 XML이 없는 **완전 Compose 중심 구조**
- XML은 매니페스트, 색상, 아이콘, 백업 설정 같은 보조 리소스 위주
- 실제 핵심 기능은 대부분 Kotlin 코드에 집중됨

### 개선 포인트
- `PlaybackService` 알림 텍스트를 현재 재생 중인 제목과 동기화하면 사용성이 좋아질 수 있음
- `POST_NOTIFICATIONS` 권한 요청 흐름이 코드에 직접 보이지 않아 Android 13+ 대응 점검이 필요함
- `Player.Listener` 등록 후 해제 로직을 더 명확히 하면 메모리 관리 측면에서 안정성이 올라갈 수 있음
- 현재 콘텐츠 데이터가 코드에 하드코딩되어 있으므로, 항목 수가 늘어나면 JSON/DB/리소스 분리 방식도 고려할 수 있음

---

## 결론
이 프로젝트는 **기도문 오디오를 재생하는 단일 목적형 Android 앱**이며, 구조는 다음 세 축으로 요약됩니다.

- **MainActivity**: 서비스 연결 및 상태 관리
- **MediaScreen**: 사용자 화면 및 재생 조작 UI
- **PlaybackService**: 실제 오디오 재생과 백그라운드 유지

즉, 이 앱의 본질은 **로컬 mp3 기도문 플레이어 + 텍스트 뷰어**입니다.  
또한 레이아웃 XML 중심 프로젝트가 아니라, **Compose 중심 Kotlin 프로젝트**라는 점이 가장 큰 구조적 특징입니다.
