# 가로형 스톱워치 — `시계` 모드 유지보수 설명서

> 2026-09-10 추가 기능. 향후 `StopwatchSecondFragment.kt`를 수정할 때 먼저 읽는다.

## 목적

2번째 가로형 스톱워치의 숫자 영역을 그대로 사용하여 현재 시각을 24시간 형식으로 표시한다.

```text
23:00:00.000
```

시계 버튼은 기존 버튼 행의 가장 앞에 있으며, QuarterTurnLayout 때문에 실제 세로 휴대폰 화면에서는 `초기화` 버튼 위쪽에 보인다.

## 모드 관계

`시계` 모드와 `스톱워치2 실행`은 같은 숫자 영역에서 동시에 실행하지 않는다.

- 스톱워치2 실행 중 `시계`를 누르면 스톱워치2를 현재 값에서 일시정지한다.
- 시계 모드에 들어가면 현재 시각 `HH:mm:ss.SSS`를 표시한다.
- 시계 모드에서 `시작`을 누르면 시계 모드를 종료하고, 기존 스톱워치2를 처음부터 또는 일시정지된 값에서 시작/재개한다.
- 시계 버튼을 다시 누르면 시계 모드만 끄고 일시정지된 스톱워치 값으로 돌아간다.
- `초기화`는 시계 모드에서는 비활성화한다. 현재 시각에는 초기화 개념이 없기 때문이다.

## 화면꺼짐 방지

시계와 스톱워치가 같은 `화면꺼짐 방지 / 방지 해제` 상태를 공유한다.

```kotlin
val activeDisplay = viewModel.isClockMode || viewModel.isRunning
rootView.keepScreenOn = keepScreenEnabled && activeDisplay
```

시계 모드에서도 화면꺼짐 방지를 켤 수 있어야 한다.

## 현재 시각의 기준

앱 화면의 시계는 `java.time.LocalTime.now()`를 사용한다.

- 휴대폰의 현재 지역 시간 기준
- 24시간 형식 고정
- 밀리초까지 표시
- 시스템 시간이 변경되면 현재 시각도 자연스럽게 따라감

스톱워치의 `SystemClock.elapsedRealtime()`과 혼동하지 않는다.

```text
시계       = wall clock / LocalTime / 현재 시각
스톱워치   = monotonic clock / elapsedRealtime / 경과 시간
```

둘은 목적이 완전히 다르다.

## 시계 알림

시계 알림은 `ClockNotificationManager.kt`가 독립적으로 관리한다.

`ClockService`의 Foreground Leader 구조에 시계를 끼워 넣지 않는다.

알림은 `TextClock`을 사용해 SystemUI에서 직접 초 단위로 갱신하며, 매초 `notify()`하지 않는다.

자세한 충돌 방지 규칙은 `CLOCKSERVICE_NOTIFICATION_GUIDE.md`를 함께 참고한다.

## 주요 파일

```text
app/src/main/java/com/krdonon/timer/StopwatchSecondFragment.kt
app/src/main/java/com/krdonon/timer/StopwatchSecondViewModel.kt
app/src/main/java/com/krdonon/timer/ClockNotificationManager.kt
app/src/main/res/layout/activity_stopwatch_second.xml
app/src/main/res/layout-land/activity_stopwatch_second.xml
app/src/main/res/layout/notification_clock_compact.xml
app/src/main/res/layout/notification_clock_expanded.xml
app/src/main/res/values/styles.xml
```

## 회귀 테스트

1. 가로형 스톱워치에서 `시계` 버튼을 누른다.
2. 본문이 `HH:mm:ss.SSS` 형태로 현재 시간을 표시하는지 확인한다.
3. 시계 버튼이 활성 상태(보라색)로 보이는지 확인한다.
4. 시계 알림이 독립 카드로 나타나는지 확인한다.
5. 타이머를 동시에 실행하고 두 알림이 흔들리거나 서로 덮어쓰지 않는지 30초 이상 확인한다.
6. 일반 스톱워치 알림도 동시에 실행해 충돌이 없는지 확인한다.
7. 시계 모드에서 `화면꺼짐 방지`를 켰을 때 화면 유지가 적용되는지 확인한다.
8. 시계 모드에서 `시작`을 누르면 시계 알림이 사라지고 스톱워치2가 정상 시작/재개되는지 확인한다.
9. 시계 버튼을 다시 눌러 OFF 했을 때 일시정지된 스톱워치 숫자로 돌아가는지 확인한다.
10. `전환` 버튼으로 클래식 화면으로 갔다 돌아와도 시계 모드/알림이 유지되는지 확인한다.

## 절대 하지 말 것

- 시계 현재 시각 계산에 `SystemClock.elapsedRealtime()`을 사용하지 않는다.
- 스톱워치 경과 시간 계산에 `LocalTime` 또는 `System.currentTimeMillis()`를 대신 사용하지 않는다.
- 시계 알림을 갱신하려고 16ms/100ms/1초 반복 `notify()`를 만들지 않는다.
- 시계 notification ID/group을 타이머 또는 스톱워치와 공유하지 않는다.
- `QuarterTurnLayout` 회전 구조를 시계 기능 때문에 변경하지 않는다.
