# 🤖 범용 Android 트러블슈팅 AI 프롬프트 가이드

이 문서는 안드로이드 앱 개발 시 **모든 프로젝트에서 공통적으로 발생할 수 있는 고질적인 문제(오디오 중단, 생명주기 크래시, UI 잘림, SDK 경고 등)**를 AI(Gemini, Claude, ChatGPT 등)에게 정확하게 전달하여 **한 번에 근본적인 해결 코드를 작성하도록 지시하는 표준 프롬프트 모음**입니다.

특정 앱 이름에 종속되지 않은 범용적인 명령어 형식이므로, 문제가 발생한 프로젝트에 그대로 복사해서 AI에게 입력하시면 됩니다.

---

## 🎵 1. 앱을 나갔다 오거나 유튜브/절전 모드 복귀 시 배경음악(MediaPlayer)이 멈추는 문제

> **문제 현상**: 홈 버튼을 누르거나, 유튜브/릴스/전화 등 다른 미디어를 사용하다가 돌아오거나, 최근 실행 앱(Task Switcher) 목록에서 복귀했을 때 음악이 끊긴 채 다시 나오지 않는 현상 (음악 토글 버튼을 껐다 켜야만 소리가 나오는 전형적인 오디오 포커스 상실 증상)

### 📋 AI에게 복사해서 전달할 명령어
```text
[Android MediaPlayer 생명주기, 오디오 포커스(AudioFocus) 및 자동 복구(Auto-Recovery) 구현 요청]

현재 앱에서 홈 화면으로 나갔다 오거나, 유튜브/영상 시청 후 복귀하거나, 최근 실행 앱(Task Switcher) 목록에서 복귀하거나, 절전 모드 복귀 시 배경음악(MediaPlayer)이 다시 재생되지 않고 무음으로 멈추는 현상이 있습니다. (음악 버튼을 껐다 켜야만 소리가 납니다.)

다음 원칙에 따라 오디오 매니저와 Activity 생명주기 및 오디오 포커스를 점검하고 코드를 개선해 주세요:

1. 오디오 포커스(AudioFocusRequest) 획득 및 반환 (핵심!):
   - 유튜브 등 다른 앱이 오디오 포커스를 빼앗아간 뒤 우리 앱으로 복귀했을 때, 시스템 오디오 채널을 다시 활성화할 수 있도록 resume() 및 음악 시작 시 AudioManager.requestAudioFocus(AUDIOFOCUS_GAIN)를 반드시 호출해 주세요.
   - OnAudioFocusChangeListener를 등록하여 오디오 포커스를 상실했을 때 일시정지하고, 포커스를 되찾았을 때(AUDIOFOCUS_GAIN) 자동으로 음악 재생을 재개하도록 해주세요.
   - pause() 및 백그라운드 전환 시에는 abandonAudioFocus()를 호출하여 시스템 리소스를 정상적으로 양보하도록 해주세요.

2. Activity 수명주기 연동:
   - 다이얼로그나 설정 창 등 가벼운 화면 전환 시 음악이 끊기지 않도록, 실제 백그라운드 전환(onStop) 시 일시정지하고 onResume()에서 재생을 재개하도록 균형 있게 맞춰주세요.
   - onTrimMemory()에서 오디오 플레이어 인스턴스를 강제로 파괴하거나 비정상적인 상태로 만들지 않도록 분리해 주세요.

3. 절전 복귀 시 오디오 트랙 자동 복구 (Auto-Recovery):
   - 장시간 백그라운드 대기 시 OS 배터리 최적화로 인해 네이티브 오디오 트랙이 릴리즈될 수 있습니다.
   - onResume() 시점에 기존 MediaPlayer가 유효하지 않거나 에러 상태(IllegalStateException 등)일 경우, 이를 예외 처리로 감지하여 즉시 새 MediaPlayer를 재할당하고 직전 재생 위치(lastPositionMs)부터 이어서 자동 복원 재생하도록 구현해 주세요.

4. 에러 리스너 안전망:
   - MediaPlayer의 setOnErrorListener를 등록하여 내부 오류 발생 시 플레이어를 안전하게 릴리즈하고 다음 곡 또는 현재 곡을 재준비하여 앱이 먹통이 되지 않도록 해주세요.
```

---

## 💥 2. 화면 회전이나 백그라운드 전환 시 앱이 죽는 문제 (무한 재귀 / StackOverflow)

> **문제 현상**: 화면을 돌리거나, 다크 모드를 바꾸거나, 백그라운드 전환 시 `StackOverflowError` 또는 `ActivityThread.handleConfigurationChanged` 관련 크래시 발생

### 📋 AI에게 복사해서 전달할 명령어
```text
[Application 클래스 무한 재귀 및 생명주기 콜백 크래시 점검 요청]

화면 회전, 시스템 설정 변경(다크모드/언어 변경), 또는 백그라운드 전환 시 앱이 강제 종료(StackOverflowError / ActivityThread.handleConfigurationChanged)됩니다.

다음 사항을 점검하고 수정해 주세요:
1. 커스텀 Application 클래스에서 registerComponentCallbacks(this)를 중복 등록하여 자기 자신에게 이벤트를 계속해서 재발행하고 있는지 확인해 주세요.
2. Application 클래스에 불필요하게 오버라이드된 onConfigurationChanged() 등 무한 재귀 루프를 일으키는 코드가 있다면 제거해 주세요.
3. 시스템 메모리 관리(onTrimMemory, onLowMemory)가 필요하다면 재귀 호출 없이 안전하게 동작하도록 정돈해 주세요.
```

---

## 📐 3. 기기 화면 높이에 따라 하단 UI / 그리드가 잘리는 문제

> **문제 현상**: 기기 해상도나 화면 비율에 따라 하단 버튼, 카드, 그리드가 잘려서 일부 요소가 보이지 않거나 인터랙션이 불가능한 현상

### 📋 AI에게 복사해서 전달할 명령어
```text
[뷰포트 반응형 레이아웃 및 상단 접기(Collapsible) UI 구현 요청]

다양한 기기 해상도(스마트폰 세로 모드 기준)에서 하단 UI 요소나 그리드가 화면 아래로 잘려 보이지 않는 문제가 발생합니다.

다음과 같이 반응형 레이아웃 구조로 개선해 주세요:
1. 화면 가용 높이/너비(BoxWithConstraints 또는 Configuration)를 기반으로, 어떤 화면 비율에서도 모든 요소가 스크롤 없이 한 화면에 100% 들어오도록 행/열(rows/cols) 및 크기 계산 로직을 최적화해 주세요.
2. 상단 헤더/대시보드 영역을 사용자가 접고 펼칠 수 있는 토글(접기/펼치기) UI를 만들어 주세요.
3. 상단 영역이 접혔을 때 메인 콘텐츠/게임 보드가 남은 화면 전체를 채우며 유연하게 확대되는 반응형 구조(weight(1f) 등)로 만들어 주세요.
4. 비즈니스 로직(예: 타깃 생성, 클리어 조건)에서도 화면에 실제로 렌더링된 요소(visibleCount) 범위 안에서만 동작하도록 일치시켜 주세요.
```

---

## 📦 4. Google Play Console의 오래된 SDK / 라이브러리 경고 해결

> **문제 현상**: 직접 선언하지 않은 라이브러리인데도 Play Console에서 "오래된 SDK 버전 사용" 경고가 뜰 때

### 📋 AI에게 복사해서 전달할 명령어
```text
[Google Play Console 구버전 라이브러리 전이 의존성(Transitive Dependency) 해결 요청]

Google Play Console에서 특정 AndroidX 라이브러리가 오래되었다는 경고가 표시되고 있습니다.

1. 프로젝트에서 직접 사용 중인 라이브러리뿐만 아니라, Firebase, Play Services 등 다른 상위 라이브러리가 끌고 오는 전이 의존성(Transitive Dependency)을 분석해 주세요.
2. 문제가 되는 오래된 라이브러리를 프로젝트의 libs.versions.toml 및 build.gradle.kts에 최신 안정 버전으로 직접 선언하여, 프로젝트 전체에서 최신 안정 버전으로 강제 해결(Resolution)되도록 설정해 주세요.
3. 기존 코드 컴파일과 빌드에 영향이 없는 최소한의 안전한 최신 버전으로 적용해 주세요.
```

---

## 🔝 5. compileSdk / targetSdk 버전 상향 및 빌드 설정 최적화

> **문제 현상**: Play Store 최신 배포 규정에 맞춰 SDK 버전을 올려야 하거나, 에디터에 알림 경고가 뜰 때

### 📋 AI에게 복사해서 전달할 명령어
```text
[Android SDK 버전(compileSdk/targetSdk) 상향 및 Lint 알림 정리 요청]

앱의 compileSdk와 targetSdk를 최신 안정 버전으로 상향해 주세요.

1. build.gradle.kts에서 compileSdk와 targetSdk를 최신 버전으로 변경해 주세요.
2. targetSdk 변경 시 에디터에 표시되는 EditedTargetSdkVersion 알림 메시지가 빌드에 방해되지 않도록 적절한 주석 어노테이션(//noinspection EditedTargetSdkVersion)을 추가해 주세요.
3. 변경 후 Gradle Sync와 디버그 빌드(assembleDebug), 단위 테스트가 오류 없이 정상 통과하는지 검증해 주세요.
```

---

## ⏱️ 6. 장시간 비활성(또는 N분 만료) 후 데이터/로그 자동 삭제 시 생명주기 함정

> **문제 현상**: N분(예: 40분) 동안 활동이 없거나 앱을 백그라운드에 오래 방치했을 때 데이터를 자동 만료(삭제)해야 하는데, 앱 프로세스가 살아있는 상태(Hot Resume)로 복귀하면 만료 검사가 동작하지 않고 이전 데이터가 영구 보존되는 버그

### 💡 빠지기 쉬운 개발자/AI의 함정 (Pitfall)
1. **Application.onCreate()에만 검사를 넣는 함정**:
   - 대부분의 개발자와 AI는 앱이 켜질 때 실행되는 `Application.onCreate`에만 만료 로직을 넣습니다.
   - 하지만 모바일 환경에서는 사용자가 앱을 완전히 끄지 않고 홈으로 나갔다가 돌아오는 **백그라운드 복귀(Hot Resume: `Activity.onResume`)가 90% 이상**입니다.
   - 따라서 백그라운드에서 몇 시간이 지나도 `Application.onCreate`는 다시 불리지 않으므로 만료 로직이 영원히 실행되지 않습니다!
2. **타임스탬프 계산 시 SharedPreferences/파일 수정시간 오차**:
   - 파일의 실제 마지막 기록 줄에 적힌 시간(`[2026-10-12 10:01:34]`)을 직접 읽지 않고 OS 파일 메타데이터에만 의존하면 동기화 타이밍 차이로 삭제가 누락됩니다.

### 📋 AI에게 복사해서 전달할 명령어
```text
[백그라운드 장시간 대기 후 복귀 시 N분 데이터 만료 검증 로직 구현 요청]

일정 시간(예: 40분) 이상 비활성 상태이거나 앱을 백그라운드에 방치했다가 복귀했을 때, 특정 데이터(임시 캐시, 진단 로그 등)를 자동으로 만료 및 정리(Purge)해야 합니다.

다음 생명주기 함정을 반드시 피해서 구현해 주세요:
1. 앱이 완전히 새로 켜질 때(Cold Start: Application.onCreate)뿐만 아니라, 백그라운드에 떠 있다가 다시 전면으로 복귀할 때(Hot Resume: Activity.onResume)에도 반드시 만료 검사 함수(purgeExpiredDataIfNeeded)를 호출해 주세요.
2. 경과 시간 계산 시 시스템 SharedPreferences와 실제 파일 내부의 마지막 타임스탬프를 함께 비교하여 수동 시간 조작이나 백그라운드 복귀 시에도 정확하게 N분 초과 여부를 감지하도록 해주세요.
3. 중요 오류가 보존되어야 하는 예외 조건이 있다면, 에러 플래그가 없을 때만 깨끗하게 초기화하도록 보호 조건을 넣어주세요.
```

---

## 🛡️ 7. Google Play Console "DEX 코드 최적화 기준점 미만 (난독화 1%)" 경고 해결

> **문제 현상**: AAB 번들을 스토어에 업로드했을 때 "DEX 코드 최적화가 기준점 미만입니다 (난독화 1%)" 빨간색 주의 경고가 뜨는 현상

### 💡 해결 원인
- `build.gradle.kts`의 `release` 빌드 타입에 `isMinifyEnabled = false`로 되어 있어 R8 컴파일러의 난독화 및 트리밍이 동작하지 않았기 때문입니다.
- `isMinifyEnabled = true`, `isShrinkResources = true`를 켜고 `proguard-android-optimize.txt`를 적용하면 DEX 크기가 수십 MB 이상 줄어들고 난독화율이 기준치(25% 이상)를 훌쩍 넘어 해결됩니다.

### 📋 AI에게 복사해서 전달할 명령어
```text
[Google Play DEX 코드 최적화(R8 난독화) 기준점 미달 경고 해결 요청]

Google Play Console에 AAB 번들 업로드 시 "DEX 코드 최적화가 기준점 미만입니다 (난독화 1%)" 경고가 발생합니다.

다음과 같이 R8 최적화를 완벽하게 활성화해 주세요:
1. app/build.gradle.kts의 release 빌드 타입에 isMinifyEnabled = true 및 isShrinkResources = true를 켜주세요.
2. proguardFiles에 getDefaultProguardFile("proguard-android-optimize.txt")와 "proguard-rules.pro"를 지정해 주세요.
3. app/proguard-rules.pro에 앱 내 필수 모델 클래스(Moshi, Retrofit, Age Signals 등)가 리플렉션 오류로 깨지지 않도록 안전한 Keep 규칙을 보강해 주세요.
```

