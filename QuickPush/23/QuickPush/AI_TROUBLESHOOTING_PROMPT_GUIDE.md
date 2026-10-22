# 🤖 범용 Android 트러블슈팅 AI 프롬프트 가이드

이 문서는 안드로이드 앱 개발 시 **모든 프로젝트에서 공통적으로 발생할 수 있는 고질적인 문제(오디오 중단, 생명주기 크래시, UI 잘림, 가로 모드 다이얼로그 잘림, 백그라운드 배터리 소모, 장시간 절전 복귀 시 상태 잠김, SoundPool 비동기 로딩 누락 등)**를 AI(Gemini, Claude, ChatGPT 등)에게 정확하게 전달하여 **한 번에 근본적인 해결 코드를 작성하도록 지시하는 표준 프롬프트 모음**입니다.

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

## 🔇 2. 모든 앱 닫기(Cold Start) 후 재진입 시 onTrimMemory 간섭으로 음악이 침묵하는 문제

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

## 📱 4. 스마트폰 가로 모드(Landscape)에서 다이얼로그 하단 버튼이 잘리는 문제

> **문제 현상**: 스마트폰 가로 모드에서 세로 높이가 좁아 결과 창이나 종료 창의 하단 [닫기], [다시 도전] 버튼이 화면 바닥 밖으로 잘려 누를 수 없는 현상

### 📋 AI에게 복사해서 전달할 명령어
```text
[Jetpack Compose 가로 모드 다이얼로그 verticalScroll 및 들썩임 힌트 애니메이션 구현 요청]

스마트폰 가로 모드(Landscape)에서 화면 높이가 좁아 다이얼로그 팝업(결과창, 설정창, 종료창)의 하단 버튼들이 잘려 누를 수 없습니다.

다음 원칙에 따라 개선해 주세요:
1. 다이얼로그 내부 Column에 Modifier.verticalScroll(rememberScrollState())를 적용하고, Card의 최대 높이를 heightIn(max = if (isLandscape) 330.dp else 650.dp)로 설정하여 손가락으로 밀어 올릴 수 있게 해주세요.
2. 가로 모드 진입 시(LaunchedEffect) scrollState.animateScrollTo(60) -> animateScrollTo(0) 바운스 애니메이션을 0.3초간 실행하여, 사용자가 보는 즉시 '위로 밀어서 올릴 수 있는 창이구나'라고 직관적으로 알 수 있는 힌트 모션을 제공해 주세요.
3. 가로 모드일 때는 아이콘 크기와 위아래 패딩/스페이서를 컴팩트하게 축소하여 스크롤 없이도 버튼이 최대한 한눈에 보이도록 최적화해 주세요.
```

---

## 🔋 5. 백그라운드 배터리 과소비(CPU 점유) 및 장시간(2시간) 절전 후 복구 잠김(Deadlock) 문제

> **문제 현상**: 홈으로 나갔는데도 배터리 사용량에 CPU/백그라운드 시간이 많이 찍히거나, 2시간 이상 방치 후 앱을 다시 켰을 때 게임 요소가 비정상적으로 생성되거나 화면 터치가 먹통으로 잠기는 현상

### 📋 AI에게 복사해서 전달할 명령어
```text
[백그라운드 코루틴 취소(배터리 0%) 및 프로세스 재시작 시 상태 클린 재싱크 아키텍처 요청]

1. 백그라운드 배터리 최적화 (CPU 0%):
   - Activity.onStop() 시점에 ViewModel에서 돌고 있는 모든 타이머/루프 코루틴(Job)을 즉시 cancel()해 주세요.
   - 백그라운드에서 delay 루프가 돌면서 CPU를 소모하는 현상을 원천 차단해 주세요.

2. 프로세스 종료(Process Kill) 대비 상태 보존 및 복구 대기:
   - onStop() 시점에 현재 진행 중인 게임 모드, 레벨, 점수, 남은 시간을 SharedPreferences에 영구 저장해 주세요.
   - 장시간 후 앱이 새로 켜질 때(init), 이전 상태를 복원하고 곧바로 게임을 시작하지 않고 [게임 일시정지] 팝업을 띄운 채 안전하게 대기하도록 해주세요.

3. 복구 시 모드별 완전 무결 클린 재싱크 (2중 안전장치):
   - 프로세스 재시작 시 버블판이나 게임 요소가 깨진 상태로 복구되지 않도록, 모드별 정상 상태(스피드 모드는 정확히 3개 불빛 점등, 돌파 모드는 목표 불빛 등)로 버블판을 완전히 클린하게 재구성(Re-sync)해 주세요.
   - 플레이 도중이나 복귀 시 불빛 개수가 부족해지면 실시간으로 채워주는 자가 치유(Self-Healing)를 구현해 주세요.

4. 터치 락 방지 자가 치유:
   - onResume() 시점에 다이얼로그가 떠 있지 않은데 내부 플래그(isPaused = true)만 잠겨있다면, 즉시 isPaused = false로 자동 교정하여 터치 먹통을 방지해 주세요.
```

---

## 🔊 6. SoundPool 비동기 로딩 미완료 ID 호출로 인한 간헐적 효과음 누락 및 연타 씹힘 문제 [NEW!]

> **문제 현상**: 자동 시연(기억력 게임 등) 중 버튼이 켜지는데 소리가 간헐적으로 안 나거나, 버튼을 빠르게 두 번 누를 때 효과음이 씹히는 현상

### 📋 AI에게 복사해서 전달할 명령어
```text
[SoundPool 개별 로딩 상태 추적, 준비 대기 및 다중 라운드 로빈 풀링 구현 요청]

1. 개별 사운드 ID 로딩 완료 추적 (핵심):
   - setOnLoadCompleteListener는 사운드마다 개별 호출됩니다. 하나의 사운드만 로드되어도 isLoaded = true로 퉁치지 마세요.
   - loadedSoundIds = Collections.synchronizedSet(HashSet<Int>())를 생성하여 실제 status == 0으로 완료된 soundID만 정확히 등록해 주세요.
   - playBubblePop() 등 효과음 재생 시, 반드시 loadedSoundIds에 등록된 유효한 ID만 골라서 pool.play()를 호출하세요. 아직 로드되지 않은 ID를 호출하면 Android SoundPool은 소리를 내지 않고 침묵합니다.

2. 다중 슬롯 라운드 로빈(Round-Robin) 풀링:
   - 단일 soundID만 등록하면 빠른 연타나 연속 재생 시 네이티브 채널 경쟁(Voice Drop)으로 소리가 씹힙니다.
   - 빈번한 효과음은 IntArray(4)로 4개의 독립 슬롯을 등록하고, 라운드 로빈(0->1->2->3->0)으로 번갈아 재생해 주세요.
   - pool.play() 결과 streamId == 0(실패)인 경우, 로드 완료된 다른 슬롯으로 즉시 1회 Fallback 재시도하도록 해주세요.

3. 자동 시연 시작 전 사운드 준비 대기:
   - 자동 시연 루프(기억력 모드 등)에 들어가기 전, awaitPopSoundsReady(maxWaitMs) 함수를 호출하여 필요한 효과음이 SoundPool에 최소 1개 이상 로드 완료될 때까지 비동기로 안전하게 대기한 뒤 불빛을 켜주세요.
   - 시연 효과음은 priority = 3으로 높여 다른 시스템 사운드에 밀리지 않도록 보장해 주세요.
```

---

## 💥 7. 화면 회전이나 백그라운드 전환 시 앱이 죽는 문제 (무한 재귀 / StackOverflow)

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

## 📐 8. 기기 화면 높이에 따라 하단 UI / 그리드가 잘리는 문제

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
