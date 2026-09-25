# 내일 타이머 (Tomorrow Timer)

**내일 타이머**는 ‘오늘부터 내일(다음날)까지 이어질 수 있는 장시간 타이머’와 **클래식/가로형 듀얼 스톱워치**, **24시간 실시간 시계**, 그리고 **그룹 관리 요일 알람**을 종합적으로 제공하는 안드로이드 올인원 클락 앱입니다.

- 타이머는 **예상 종료 시각(예: 내일 1월 4일 오후 2:40)** 을 화면과 상단 알림에서 직관적으로 보여주어 “언제 울리는지”를 즉시 파악할 수 있습니다.
- 스톱워치는 일반 세로형 스톱워치뿐만 아니라, 대형 숫자로 시원하게 볼 수 있는 **90도 회전 가로형 독립 스톱워치(QuarterTurnLayout)** 를 지원합니다.
- 가로형 스톱워치 화면에서 원터치로 **24시간 밀리초 시계(`HH:mm:ss.SSS`)** 모드로 전환할 수 있습니다.
- 진행중 알림바(Compact / Expanded)에서 **일시정지 / 재개 / 초기화** 등 컨트롤을 지원하며, 알림을 탭하면 해당 화면으로 즉시 진입합니다.
- **타이머와 스톱워치, 시계 알림을 동시에 실행해도 알림 카드가 서로 밀어내거나 깜빡이지 않도록 독립적인 알림 채널/ID/그룹 아키텍처**로 설계되었습니다.

- 패키지: `com.krdonon.timer`
- 지원 환경: **Android 8.0 (API 26) ~ Android 16 (API 37)**

---

## 1) 주요 기능

### 1. 타이머 (메인 & 다중 보조 타이머)
- **예상 종료 시각 표시**: 오늘/내일, 월/일, 요일, 오전/오후까지 정확히 안내
- **밀리초(천분의 1초) 표기**: `HH:MM:SS.mmm`
- **빠른 프리셋**: 기본 프리셋(10분 / 15분 / 30분) 및 숫자패드 하단 가로 스크롤 프리셋(5분 / 10분 / 20분 / 30분 / 40분 / 50분)
- **독립 보조 타이머(Extra Timer)**:
  - 메인 타이머 동작/임시값을 그대로 유지한 채 새 시간을 입력하여 보조 타이머를 무제한 추가 가능
  - 각 보조 타이머별 독립적인 카운트다운 및 관리
- **안정적인 상태 복원**: 앱을 나갔다 돌아오거나 화면 회전 시에도 메인 타이머 임시 입력값(draft)과 진행 상태 완벽 복원

### 2. 스톱워치 (클래식 & 가로형 듀얼 모드)
- **1번 클래식 스톱워치**: 표준 세로 화면, 시작 / 정지 / 랩 기록 / 초기화, 화면꺼짐 방지
- **2번 가로형 독립 스톱워치 (전환 화면)**:
  - `QuarterTurnLayout`을 적용하여 세로 화면 안에서 90도 회전된 대형 숫자로 시간 표시
  - 시스템 자동회전 상태 및 세로 고정 상태 모두에서 이중 회전 없이 최적화된 뷰 제공
  - 1번 스톱워치와 완벽히 독립된 측정 및 랩 타임 관리
- **장시간 측정 지원**: 24시간에서 리셋되지 않고 100시간, 120시간 이상도 연속 측정 가능

### 3. 24시간 시계 모드 (신규 기능)
- 2번째 스톱워치 화면에서 **`시계` 캡슐 버튼**을 탭하여 활성화
- 벽시계 기준 현재 시각을 밀리초 단위 24시간 형식(`HH:mm:ss.SSS`)으로 실시간 표시
- **안전한 모드 전환**: 스톱워치가 실행 중일 때 `시계`를 누르면 스톱워치 시간을 일시정지 상태로 보존하고 시계 모드로 전환되며, `시작`을 누르면 기존 스톱워치 측정으로 자연스럽게 복귀
- **독립 시계 알림 (`ClockNotificationManager`)**:
  - 알림창에 24시간 현재 시각(`HH:mm:ss`)을 표시하는 독립 알림 카드 지원
  - SystemUI `TextClock`을 사용하여 배터리 소모와 불필요한 알림 재게시(notify) 없는 무충돌 동작

### 4. 화면꺼짐 방지 (Keep Screen On)
- 스톱워치(클래식 및 2번째)와 시계 모드에서 화면이 꺼지지 않도록 원터치 토글 지원
- 화면이나 하단 탭을 벗어나면 시스템 기본 설정으로 안전하게 자동 복원

### 5. 알림 독립화 및 무충돌 Foreground 아키텍처
- **Foreground Leader 정책**: 메인 타이머를 Foreground Service Leader(ID: 42)로 우선 지정
- **독립 Ongoing 알림**: 스톱워치(ID: 1002), 스톱워치2(ID: 1003), 시계(ID: 1004)는 별도의 고유 ID와 독립 그룹 키로 발행
- **SystemUI 위젯 직접 구동**: 시간 갱신을 위해 매초 `notify()`를 반복 호출하지 않고, `RemoteViews Chronometer`와 `TextClock`이 SystemUI 상에서 직접 흐르도록 구현하여 **삼성 One UI 등에서 알림 카드가 확장/축소되거나 번갈아 튀는 충돌 현상을 원천 방지**

### 6. 요일 알람 (그룹 관리 및 커스텀 사운드)
- 요일별/시간별 알람 생성 및 개별 ON/OFF
- **그룹(라벨) 관리**: 그룹별 묶음 표시, 그룹 단위 일괄 ON/OFF, 다중 선택 그룹 이동/삭제
- **요일 ‘매주’ 버튼**: 월~일 요일 전체 일괄 선택 및 해제
- **미니 달력 팝업**: 알람 편집 중 날짜를 즉시 확인할 수 있는 제스처 지원 달력
- **커스텀 MP3 알람음**: 사용자가 보유한 MP3 파일을 선택하여 알람 사운드로 지정 (파일 삭제 또는 접근 불가 시 기본 내장 사운드로 자동 복구)
- **잠금화면 풀스크린 알람**: 잠금화면 위에서도 즉시 알람 해제 및 다시 울림(스누즈) 가능 (`AlarmActivity`, `AlarmAgainActivity`)

### 7. 내부 로그 및 유휴 상태 자동 정리 (`AppLog`)
- 반복되는 상태 변화는 억제 요약(`[suppressed=N]`)하여 저장 공간 절약
- 앱이 40분 이상 완전 유휴 상태(동작 중인 타이머/스톱워치 없음)일 경우 임시 로그 자동 정리

---

## 2) 사용 방법

### 타이머 사용법
1. 하단 탭에서 **타이머**를 선택합니다.
2. 빠른 프리셋(10분/15분/30분) 또는 숫자패드로 시간을 설정합니다.
3. **시작**을 누르면 카운트다운이 시작되며 예상 종료 시각이 화면과 상단 알림에 나타납니다.
4. 다른 타이머를 추가하고 싶다면 **추가** 버튼을 눌러 보조 타이머를 생성할 수 있습니다.

### 스톱워치 및 가로형/시계 모드 사용법
1. 하단 탭에서 **스톱워치**를 선택합니다.
2. 기본 클래식 화면에서 **시작**, **기록(랩)**, **초기화**를 사용합니다.
3. **전환** 버튼을 누르면 대형 숫자의 **2번째 가로형 스톱워치**로 변경됩니다.
4. 2번째 스톱워치에서 **시계** 버튼을 누르면 현재 시각(`HH:mm:ss.SSS`)이 실시간으로 표시되는 **24시간 시계 모드**로 전환됩니다.
5. 시계 모드에서 **시작**을 누르면 시계 모드가 종료되고 기존 스톱워치가 즉시 이어집니다.
6. 장시간 켜두어야 할 때는 **화면꺼짐 방지**를 켭니다.

### 요일 알람 및 그룹 사용법
1. 하단 탭에서 **알림**을 선택합니다.
2. 우측 하단 **+** 버튼으로 새 알람을 등록합니다.
3. 알람 추가 화면에서 시간/요일을 선택하고, **소리 변경**에서 내 MP3 파일을 지정할 수 있습니다.
4. 알람 목록의 그룹 스위치를 누르면 해당 그룹의 모든 알람을 한 번에 켜거나 끌 수 있습니다.
5. 알람 항목을 길게 누르면 선택 모드로 진입하여 다른 그룹으로 이동하거나 일괄 삭제할 수 있습니다.

---

## 3) 프로젝트 구조

### Kotlin 소스 코드 (`app/src/main/java/com/krdonon/timer`)
- `MainActivity.kt`: 하단 탭 네비게이션 관리, 마지막 탭 상태 저장/복원
- `ClockService.kt`: 백그라운드 타이머/스톱워치 코루틴 서비스, 알림 리더 및 충돌 방지 제어
- `ClockNotificationManager.kt`: 24시간 실시간 시계 모드 전용 독립 알림 관리자 (`TextClock`)
- `TimerFragment.kt`, `TimerViewModel.kt`: 타이머 메인 UI, 보조 타이머 리스트, 임시값 복원
- `StopwatchFragment.kt`, `StopwatchViewModel.kt`: 클래식 스톱워치 화면, 1번/2번 화면 전환 및 화면 방향 제어
- `StopwatchSecondFragment.kt`, `StopwatchSecondViewModel.kt`: 2번째 가로형 스톱워치 UI, 24시간 시계 모드, 랩 관리
- `NumberPadFragment.kt`: 타이머 시간 입력 숫자패드 및 가로 스크롤 프리셋
- `widget/QuarterTurnLayout.kt`: 세로 액티비티 안에서 자식 레이아웃을 90도 회전 및 터치 좌표 역변환하는 커스텀 컨테이너
- `alarm/AlarmActivity.kt`, `alarm/AlarmAgainActivity.kt`: 타이머/요일 알람 울림 화면 (잠금화면 대응)
- `alarm/AlarmService.kt`, `alarm/AlarmReceiver.kt`: 정밀 알람 매니저 수신 및 사운드/진동 재생
- `alarmclock/AlarmListFragment.kt`, `alarmclock/AlarmEditActivity.kt`: 요일 알람 목록 및 편집 화면
- `alarmclock/AlarmGroupedAdapter.kt`, `alarmclock/GroupStore.kt`: 알람 그룹 관리 모델 및 어댑터
- `alarmclock/MiniCalendarDialogFragment.kt`: 알람 편집용 미니 캘린더 팝업
- `AppLog.kt`, `IdleLogCleanupReceiver.kt`: 내부 디버그 로깅 및 유휴 상태 정리

### 주요 레이아웃 리소스 (`app/src/main/res/layout`)
- `activity_main.xml`: 메인 컨테이너 및 하단 바
- `fragment_timer.xml`: 메인 타이머 및 보조 타이머 뷰
- `fragment_stopwatch.xml`: 클래식 스톱워치 뷰 및 2번째 스톱워치 컨테이너
- `activity_stopwatch_second.xml`: 90도 회전 가로형 스톱워치 및 시계 화면
- `fragment_number_pad.xml`: 숫자패드 및 프리셋 뷰
- `notification_timer_compact.xml`, `notification_timer_expanded.xml`: 타이머 알림 뷰
- `notification_stopwatch_compact.xml`, `notification_stopwatch_expanded.xml`: 스톱워치 알림 뷰
- `notification_clock_compact.xml`, `notification_clock_expanded.xml`: 24시간 시계 알림 뷰
- `activity_alarm.xml`, `activity_alarm_again.xml`: 전체 화면 알람 뷰
- `fragment_alarm_list.xml`, `activity_alarm_edit.xml`: 알람 목록 및 편집 뷰

### 유지보수 가이드 문서
- [CLOCKSERVICE_NOTIFICATION_GUIDE.md](CLOCKSERVICE_NOTIFICATION_GUIDE.md): 알림 충돌 방지, Foreground Leader 구조 및 Notification ID/Group 관리 규칙
- [STOPWATCH_ORIENTATION_GUIDE.md](STOPWATCH_ORIENTATION_GUIDE.md): QuarterTurnLayout 90도 회전 및 화면 방향 고정/복원 규칙
- [CLOCK_MODE_GUIDE.md](CLOCK_MODE_GUIDE.md): 2번째 스톱워치 시계 모드 및 동작 규칙
- [NOTIFICATION_AUTO_RESTORE_GUIDE.md](NOTIFICATION_AUTO_RESTORE_GUIDE.md): 알림 스와이프/삭제 시 자동 복원 및 알람 끄기 보장 규칙

---

## 4) 시스템 권한 및 Google Play 정책 대응

- `POST_NOTIFICATIONS` (Android 13+): 진행중인 타이머/스톱워치/시계 알림 표시
- `FOREGROUND_SERVICE` 및 `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: 백그라운드에서 끊김 없는 서비스 동작 보장
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` (Android 12+ / 14+): 정확한 시간에 알람이 울리도록 보장
- `USE_FULL_SCREEN_INTENT`: 화면이 꺼져 있거나 잠금화면일 때 알람 화면 즉시 표시
- `WAKE_LOCK`: 알람 발생 시 화면 켜짐 유지
- `VIBRATE`: 알람 및 완료 시 진동 제공
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: 장시간 타이머가 절전 모드로 인해 지연되는 현상 방지
- **Google Play Age Signals API** (`com.google.android.play:age-signals:0.0.4`) 탑재
- **최신 안정 SDK 적용**: `androidx.fragment:fragment-ktx:1.9.0` 등 최신 호환 라이브러리 유지

---

## 5) 빌드 및 개발 환경

- **IDE**: Android Studio Meerkat (2026.2.1+) 권장
- **Android Gradle Plugin (AGP)**: `9.4.0`
- **Gradle**: `9.7.1` (Gradle Wrapper)
- **Kotlin**: `2.4.20`
- **JDK / Java Toolchain**: **Java 17**
- **SDK 버전**:
  - `minSdk`: **26** (Android 8.0 Oreo)
  - `targetSdk`: **37** (Android 16)
  - `compileSdk`: **37**

### 빌드 명령어
```bash
# 디버그 빌드 및 단위 테스트
./gradlew :app:assembleDebug testDebugUnitTest

# 릴리즈 빌드 (R8 코드 및 리소스 최적화)
./gradlew :app:assembleRelease
```

---

## 6) 개인정보 처리 및 보안
- 인터넷 연결 권한(`android.permission.INTERNET`)을 사용하지 않는 **100% 오프라인 동작** 앱입니다.
- 사용자의 개인 데이터나 알람 내역, 오디오 파일을 외부 서버로 전송하지 않습니다.
