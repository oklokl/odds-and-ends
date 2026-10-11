# 🤖 범용 Android 트러블슈팅 AI 프롬프트 가이드

이 문서는 안드로이드 앱 개발 시 **모든 프로젝트에서 공통적으로 발생할 수 있는 고질적인 문제(오디오 중단, 생명주기 크래시, UI 잘림, SDK 경고 등)**를 AI(Gemini, Claude, ChatGPT 등)에게 정확하게 전달하여 **한 번에 근본적인 해결 코드를 작성하도록 지시하는 표준 프롬프트 모음**입니다.

특정 앱 이름에 종속되지 않은 범용적인 명령어 형식이므로, 문제가 발생한 프로젝트에 그대로 복사해서 AI에게 입력하시면 됩니다.

---

## 🎵 1. 앱을 나갔다 오거나 절전 모드 복귀 시 배경음악(MediaPlayer)이 멈추는 문제

> **문제 현상**: 홈 버튼을 누르거나, 다른 앱을 쓰다가 돌아오거나, 최근 실행 앱(Task Switcher) 목록에서 복귀했을 때 음악이 끊긴 채 다시 나오지 않는 현상

### 📋 AI에게 복사해서 전달할 명령어
```text
[Android MediaPlayer 생명주기 및 자동 복구(Auto-Recovery) 구현 요청]

현재 앱에서 홈 화면으로 나갔다 오거나, 최근 실행 앱(Task Switcher) 목록에서 복귀하거나, 절전 모드 복귀 시 배경음악(MediaPlayer)이 다시 재생되지 않고 무음으로 멈추는 현상이 있습니다.

다음 원칙에 따라 오디오 매니저와 Activity 생명주기를 점검하고 코드를 개선해 주세요:

1. Activity 수명주기 연동:
   - onPause()에서 음악을 일시정지하고, onResume()에서 재생을 재개하도록 일관되게 맞춰주세요.
   - onStop()이나 onTrimMemory()에서 오디오 플레이어 인스턴스를 강제로 파괴하거나 비정상적인 상태로 만들지 않도록 분리해 주세요.

2. 절전 복귀 시 오디오 트랙 자동 복구 (Auto-Recovery):
   - 장시간 백그라운드 대기 시 OS에 의해 네이티브 오디오 트랙이 릴리즈될 수 있습니다.
   - onResume() 시점에 기존 MediaPlayer가 유효하지 않거나 에러 상태(IllegalStateException 등)일 경우, 이를 예외 처리로 감지하여 즉시 새 MediaPlayer를 재할당하고 직전 재생 위치(lastPositionMs)부터 이어서 자동 복원 재생하도록 구현해 주세요.

3. 에러 리스너 안전망:
   - MediaPlayer의 setOnErrorListener를 등록하여 내부 오류 발생 시 플레이어를 안전하게 릴리즈하고 재시작하여 앱이 먹통이 되지 않도록 해주세요.
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
