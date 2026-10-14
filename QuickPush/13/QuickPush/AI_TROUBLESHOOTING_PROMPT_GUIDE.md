# 🤖 범용 Android 트러블슈팅 AI 프롬프트 가이드

이 문서는 안드로이드 앱 개발 시 **모든 프로젝트에서 공통적으로 발생할 수 있는 고질적인 문제(오디오 중단, 생명주기 크래시, UI 잘림, 비동기 준비 레이스 컨디션, 덕킹 영구 볼륨 감쇠 등)**를 AI(Gemini, Claude, ChatGPT 등)에게 정확하게 전달하여 **한 번에 근본적인 해결 코드를 작성하도록 지시하는 표준 프롬프트 모음**입니다.

특정 앱 이름에 종속되지 않은 범용적인 명령어 형식이므로, 문제가 발생한 프로젝트에 그대로 복사해서 AI에게 입력하시면 됩니다.

---

## 🎵 1. 앱을 나갔다 오거나 유튜브/절전 모드 복귀 시 배경음악(MediaPlayer)이 멈추는 문제

> **문제 현상**: 홈 버튼을 누르거나, 유튜브/릴스/전화 등 다른 미디어를 사용하다가 돌아오거나, 최근 실행 앱(Task Switcher) 목록에서 복귀했을 때 음악이 끊긴 채 다시 나오지 않는 현상

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

## 🔇 2. 모든 앱 닫기(Cold Start) 후 재진입 시 onTrimMemory 간섭으로 음악이 침묵하는 문제 [핵심!]

> **문제 현상**: 최근 앱 목록에서 '모두 닫기'를 누르고 앱을 새로 켰을 때, BGM 음원이 준비되는 찰나(수십 ms 이내)에 시스템이 `onTrimMemory(level=20)`를 호출하여 플레이어가 일시정지 플래그(`isPausedByLifecycle = true`)로 오염되어 음원이 준비되어도 영원히 재생되지 않는 버그

### 📋 AI에게 복사해서 전달할 명령어
```text
[onTrimMemory 간섭 분리 및 비동기 준비(prepareAsync) 레이스 컨디션 해결 요청]

모든 앱 닫기 후 앱을 콜드 스타트했을 때, 안드로이드 시스템이 시작 직후 잔여 프로세스 정리로 인해 onTrimMemory(TRIM_MEMORY_UI_HIDDEN = 20)를 호출합니다.
이때 onTrimMemory 내부에서 bgmPlayer.pause()를 호출하면, 비동기 준비 중이던 MediaPlayer의 내부 플래그가 isPausedByLifecycle = true로 바뀌어 onPrepared 완료 시 start()가 무시되고 음악이 영구 침묵합니다.

다음과 같이 수정해 주세요:
1. onTrimMemory(level)에서는 비트맵, 그래픽 캐시 등 UI 메모리만 정리하고, bgmPlayer.pause() 호출을 완전히 제거해 주세요. (오디오 정지는 이미 Activity.onStop()에서 정확하게 전담합니다.)
2. BgmPlayer 내부에 shouldPlayOnPrepared 플래그를 도입하여, Activity.onResume()이 불린 뒤 음원 준비(onPrepared)가 끝났다면 어떤 비동기 시스템 이벤트가 발생했더라도 100% 무조건 mp.start()가 실행되도록 보장해 주세요.
```

---

## 🔉 3. 알림음(카톡, 문자 등) 수신 후 BGM 볼륨이 영구적으로 작아지는 문제 (오디오 덕킹 버그)

> **문제 현상**: 스마트폰에 카카오톡이나 문자 알림이 오고 난 뒤, 또는 장시간 대기 후 앱에 복귀했을 때 BGM 소리가 0.3배로 영구적으로 깎여서 거의 들리지 않는 현상

### 📋 AI에게 복사해서 전달할 명령어
```text
[오디오 포커스 덕킹(Ducking) 후 100% 원본 볼륨 복원 아키텍처 요청]

알림 수신 시 시스템이 AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK을 발생시킵니다.
기존 코드에서 setVolume(volume * 0.3f)을 직접 호출하면 기준 볼륨 변수 자체가 영구 삭감되어, 포커스를 돌려받은 뒤나 앱 복귀 시 원래 볼륨으로 복원되지 않고 소리가 영구히 작아집니다.

다음과 같이 개선해 주세요:
1. 기준 볼륨 변수(baseVolume = 0.35f)와 덕킹 상태 플래그(isDucked = false)를 엄격히 분리해 주세요.
2. AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK 수신 시에는 isDucked = true로 임시 감쇠만 적용하고, baseVolume 변수는 절대 변경하지 마세요.
3. AUDIOFOCUS_GAIN 수신 및 Activity.onResume() 복귀 시에는 isDucked = false로 해제하고, 항상 mediaPlayer.setVolume(baseVolume, baseVolume)을 호출하여 100% 원래 기준 볼륨으로 깨끗하게 원상 복원해 주세요.
```

---

## 💥 4. 화면 회전이나 백그라운드 전환 시 앱이 죽는 문제 (무한 재귀 / StackOverflow)

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

## 📐 5. 기기 화면 높이에 따라 하단 UI / 그리드가 잘리는 문제

> **문제 현상**: 기기 해상도나 화면 비율에 따라 하단 버튼, 카드, 그리드가 잘려서 일부 요소가 보이지 않거나 인터랙션이 불가능한 현상

### 📋 AI에게 복사해서 전달할 명령어
```text
[Jetpack Compose 반응형 동적 뷰포트 레이아웃 구현 요청]

현재 앱이 다양한 기기 해상도(태블릿, 폴더블, 세로가 짧은 폰 등)에서 화면 높이에 따라 하단 UI나 그리드가 잘려서 일부 요소가 보이지 않습니다.

다음 원칙에 따라 화면 크기에 맞춰 동적으로 크기가 조절되는 반응형 레이아웃을 구현해 주세요:
1. BoxWithConstraints를 최상단에 활용하여 현재 사용 가능한 화면 너비(maxWidth)와 높이(maxHeight)를 동적으로 측정해 주세요.
2. 고정된 크기(Dp) 대신, 사용 가능한 높이/너비에 비례하여 셀의 크기(sizeDp)와 간격(spacingDp)을 자동으로 계산하는 반응형 그리드 계산 로직을 분리해 주세요.
3. 컴팩트 화면(높이 440dp 미만)인 경우 내부 패딩과 마진을 자동으로 축소하는 모드를 지원해 주세요.
```
