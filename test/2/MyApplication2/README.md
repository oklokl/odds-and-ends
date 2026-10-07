# Floating Countdown Timer App (플로팅 카운트다운 타이머)

다른 앱 위에 항상 표시되는 플로팅(오버레이) 기능과 백그라운드 카운트다운 및 알람 기능을 지원하는 안드로이드 앱입니다. Jetpack Compose와 Foreground Service, WindowManager를 활용하여 구현되었습니다.

---

## 주요 기능 (Features)

1. **메인 화면 타이머 설정 및 실시간 카운트다운**
   - 분·초 단위로 타이머 시간을 자유롭게 설정 가능
   - `+1분`, `+3분`, `+5분`, `+10초` 간편 추가 버튼 제공
   - 메인 화면에서 실시간으로 줄어드는 카운트다운 시계(`00:00`) 확인 가능
   - 앱 내에서 직접 시작, 일시중지, 리셋 가능

2. **다른 앱 위로 그리기 (Floating Overlay)**
   - 시스템 오버레이 권한(`SYSTEM_ALERT_WINDOW`)을 활용하여 다른 앱을 사용 중일 때도 화면 위에 타이머 위젯 표시
   - 타이머 위젯을 터치하여 화면 어디로든 자유롭게 드래그 이동 가능
   - 위젯 내에서 시작/중지, 리셋, 닫기 버튼 조작 가능

3. **자동 오버레이 전환 (Auto-Show/Hide)**
   - 타이머 실행 중 홈 화면으로 나가거나 다른 앱으로 전환할 때(`onUserLeaveHint`), 플로팅 타이머 위젯이 자동으로 화면 위에 나타남
   - 앱으로 다시 돌아오면 플로팅 창이 숨겨지고 메인 화면과 동기화됨

4. **포그라운드 서비스 및 알림바(상단바) 연동**
   - 포그라운드 서비스(`Foreground Service`)를 통해 앱이 백그라운드에 있거나 종료되어도 타이머가 끊김 없이 안정적으로 동작
   - 알림바에 남은 시간이 실시간으로 표시되며, 알림바 내 버튼을 통해 타이머 제어 가능

5. **알람음 재생 및 5분 자동 종료**
   - 시간이 `00:00`에 도달하면 `res/raw/alarm_bell.wav` 알람음이 반복 재생됨 (`AudioAttributes.USAGE_ALARM` 적용)
   - 알람 울림 시 플로팅 창, 알림바, 메인 화면 어디서든 **[알람 끄기]** 버튼을 눌러 즉시 중지 가능
   - 방치 시 최대 5분 후 자동 종료되는 안전 타임아웃 기능 포함

---

## 기술 스택 (Tech Stack)

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Background Processing**: Foreground Service, Handler, BroadcastReceiver
- **Window Management**: WindowManager (`TYPE_APPLICATION_OVERLAY`)
- **Audio**: MediaPlayer (`res/raw/alarm_bell.wav` & System Alarm Ringtone Fallback)

---

## 프로젝트 구조 (Project Structure)

- `MainActivity.kt`: 사용자 UI, 시간 설정, 상태 관리 및 서비스 통신을 위한 BroadcastReceiver 처리
- `FloatingTimerService.kt`: 백그라운드 카운트다운, 포그라운드 알림, WindowManager 오버레이 뷰 관리 및 알람 재생을 담당하는 단일 진실 공급원(Single Source of Truth) 서비스
- `floating_timer_layout.xml`: 플로팅 타이머 위젯 레이아웃
- `button_bg.xml` / `button_alarm_bg.xml` / `floating_bg.xml`: 위젯 버튼 및 배경 커스텀 디자인 Drawable
