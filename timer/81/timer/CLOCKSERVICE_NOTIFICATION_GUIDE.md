# Timer 앱 — `ClockService` 알림 충돌 방지 유지보수 설명서

> **중요:** `ClockService.kt` 또는 타이머/스톱워치 알림 코드를 수정하기 전에 이 문서를 먼저 읽을 것.  
> 이 문서는 **메인 타이머와 스톱워치를 동시에 실행했을 때 알림 카드가 서로 우위를 차지하려고 갱신·확장·축소되는 문제**가 다시 발생하지 않도록 하기 위한 유지보수 규칙이다.
>
> 기준 프로젝트: 2026-09-10 알림 독립화 수정본  
> 패키지: `com.krdonon.timer`

---

## 1. 가장 중요한 결론

이 앱은 메인 타이머와 스톱워치가 동시에 실행될 수 있다.

동시에 실행될 때 반드시 다음 구조를 유지해야 한다.

```text
메인 타이머
    └─ Foreground Service의 대표(Leader) 알림

스톱워치
    └─ 별도의 Notification ID를 사용하는 독립 ongoing 알림

스톱워치 2
    └─ 별도의 Notification ID를 사용하는 독립 ongoing 알림
```

그리고 **시간 숫자를 움직이기 위해 매초 Notification 전체를 다시 `notify()`하지 않는다.**

타이머/스톱워치의 시간 표시는 가능한 한 `RemoteViews Chronometer`가 SystemUI에서 직접 움직이게 한다.

이 두 원칙이 핵심이다.

```text
1. Foreground 대표 알림은 안정적으로 하나만 유지
2. 나머지는 고유 ID의 별도 ongoing 알림
3. 타이머/스톱워치에는 서로 다른 notification group key 사용
4. Chronometer가 스스로 시간을 표시하므로 매초 notify() 금지
```

---

## 2. 과거에 실제로 발생했던 문제

메인 타이머와 스톱워치를 각각 따로 사용할 때는 정상처럼 보였다.

하지만 **둘을 동시에 실행한 뒤 알림창을 열어 두면**, 실제폰과 가상폰 모두에서 다음과 같은 문제가 나타났다.

- 타이머 알림과 스톱워치 알림이 서로 대표 알림이 되려는 것처럼 움직임
- 한쪽이 펼쳐지거나 접히는 동안 다른 쪽이 다시 갱신됨
- 알림 카드의 위치/높이가 반복해서 변함
- SystemUI가 두 ongoing 알림을 계속 다시 레이아웃하는 것처럼 보임
- 사용자 눈에는 두 알림이 서로 "충돌"하거나 "우위를 차지하려는" 것처럼 보임

특히 Samsung One UI 같은 SystemUI에서는 같은 앱의 ongoing 알림이 빠르게 반복 갱신될 경우 이런 현상이 더 두드러질 수 있다.

### 근본 원인

기존 구조에서는 타이머와 스톱워치가 진행 시간을 보여주기 위해 **주기적으로 Notification 전체를 재게시**했다.

즉 대략 다음과 같은 일이 반복될 수 있었다.

```text
타이머 notify()
→ SystemUI 알림 레이아웃 재계산

스톱워치 notify()
→ SystemUI 알림 레이아웃 재계산

타이머 notify()
→ 다시 재계산

스톱워치 notify()
→ 다시 재계산
```

Foreground 알림 리더까지 변경되거나 같은 앱의 ongoing 알림이 비슷한 속성으로 반복 게시되면 SystemUI가 대표 알림/정렬/확장 상태를 계속 다시 판단할 수 있다.

---

## 3. 현재 핵심 수정 파일

가장 중요한 파일:

```text
app/src/main/java/com/krdonon/timer/ClockService.kt
```

현재 문제 해결의 핵심 로직은 이 파일에 있다.

알림 디자인 관련 파일:

```text
app/src/main/res/layout/notification_timer_compact.xml
app/src/main/res/layout/notification_timer_expanded.xml
app/src/main/res/layout/notification_stopwatch_compact.xml
app/src/main/res/layout/notification_stopwatch_expanded.xml
```

단, 알림 충돌 문제의 본질은 XML 디자인보다 **`ClockService.kt`의 Foreground/Notification ID/group/갱신 방식**에 있다.

---

## 4. Foreground Leader 구조를 함부로 없애지 말 것

현재 서비스에는 다음 상태가 있다.

```kotlin
private var isInForeground = false
private var foregroundLeader: Leader? = null
private var currentForegroundId: Int = -1

enum class Leader {
    TIMER,
    STOPWATCH,
    STOPWATCH2,
    EXTRA
}
```

리더별 Foreground ID:

```kotlin
private fun fgIdFor(leader: Leader): Int = when (leader) {
    Leader.TIMER -> FOREGROUND_ID_TIMER
    Leader.EXTRA -> FOREGROUND_ID_EXTRA
    Leader.STOPWATCH -> FOREGROUND_ID_STOPWATCH
    Leader.STOPWATCH2 -> FOREGROUND_ID_STOPWATCH2
}
```

이 구조를 단순화한다는 이유로 삭제하거나 모든 기능을 동일 Foreground ID 하나로 무조건 덮어쓰면 안 된다.

---

## 5. 동시에 실행될 때 메인 타이머를 Foreground Leader로 우선한다

현재 `ensureForeground()`의 핵심 정책은 다음과 같다.

```kotlin
val shouldPromote = !isInForeground ||
        (preferred == Leader.TIMER && foregroundLeader != Leader.TIMER)
```

즉:

- Foreground Service가 아직 없으면 필요한 기능이 리더가 될 수 있음
- **메인 타이머가 시작되면 타이머가 Foreground Leader로 승격될 수 있음**
- 스톱워치가 나중에 시작됐다는 이유만으로 타이머의 Foreground 리더 자리를 계속 빼앗지 않음

이 정책은 의도된 것이다.

### 예시 A — 타이머 먼저 시작

```text
타이머 시작
→ TIMER = Foreground Leader

스톱워치 시작
→ TIMER는 Leader 유지
→ 스톱워치는 별도 ongoing Notification
```

### 예시 B — 스톱워치 먼저 시작

```text
스톱워치 시작
→ 처음에는 STOPWATCH가 Foreground Leader가 될 수 있음

그 후 타이머 시작
→ TIMER가 Foreground Leader로 승격
→ 기존 스톱워치는 별도 ongoing Notification ID로 즉시 복원
```

이 흐름이 매우 중요하다.

---

## 6. 리더가 바뀌어도 이전 알림을 없애 버리지 말 것

`ensureForeground()`에는 이전 리더를 일반 ongoing 알림으로 복원하는 코드가 있다.

개념적으로:

```kotlin
val previousLeader = foregroundLeader

startOrSwitchForeground(preferred, initial)

when (previousLeader) {
    Leader.STOPWATCH -> {
        // 스톱워치가 계속 실행 중이면 별도 NID_STOPWATCH로 복원
    }

    Leader.STOPWATCH2 -> {
        // 스톱워치2가 계속 실행 중이면 별도 NID_STOPWATCH2로 복원
    }

    Leader.EXTRA -> {
        // 보조 타이머가 있으면 요약 알림 복원
    }

    else -> Unit
}
```

현재 실제 핵심 코드는 다음 형태이다.

```kotlin
Leader.STOPWATCH -> if (stopwatchJob != null) {
    cancelSafe(FOREGROUND_ID_STOPWATCH)
    notifySafe(
        NID_STOPWATCH,
        buildStopwatchNotification(currentStopwatchElapsedMs())
    )
}

Leader.STOPWATCH2 -> if (stopwatch2Job != null) {
    cancelSafe(FOREGROUND_ID_STOPWATCH2)
    notifySafe(
        NID_STOPWATCH2,
        buildStopwatch2Notification(currentStopwatch2ElapsedMs())
    )
}
```

### 왜 필요한가?

Foreground 리더를 스톱워치 → 타이머로 변경할 때 기존 Foreground 스톱워치 알림을 그냥 제거해 버리면 사용자는 실행 중인 스톱워치 알림을 잃는다.

반대로 기존 Foreground 알림을 그대로 남겨 놓고 새 일반 알림까지 게시하면 스톱워치가 두 개 나타날 수 있다.

따라서:

```text
기존 Foreground ID 취소
→ 동일 스톱워치를 일반 전용 Notification ID로 재게시
```

순서를 유지한다.

---

## 7. Notification ID를 서로 섞지 말 것

현재 ID 정책:

```kotlin
const val FOREGROUND_ID_TIMER = 42
const val FOREGROUND_ID_EXTRA = 43
const val FOREGROUND_ID_STOPWATCH = 44
const val FOREGROUND_ID_STOPWATCH2 = 45

private const val NID_TIMER = FOREGROUND_ID_TIMER
private const val NID_STOPWATCH = 1002
private const val NID_STOPWATCH2 = 1003
```

### 핵심 의미

메인 타이머는:

```text
NID_TIMER == FOREGROUND_ID_TIMER
```

로 통일한다.

이유는 타이머 자체가 Foreground/일반 ID를 따로 사용하면 같은 타이머가 두 개 보이는 문제가 생길 수 있기 때문이다.

반면 스톱워치는 타이머와 동시에 보여야 하므로:

```text
FOREGROUND_ID_STOPWATCH = 44
NID_STOPWATCH = 1002
```

처럼 Foreground용과 일반 ongoing용을 구분한다.

스톱워치2도 동일하다.

```text
FOREGROUND_ID_STOPWATCH2 = 45
NID_STOPWATCH2 = 1003
```

### 절대 하지 말 것

다음을 하지 않는다.

```kotlin
NID_STOPWATCH = FOREGROUND_ID_TIMER
```

또는 모든 알림에:

```kotlin
notify(42, ...)
```

같은 ID를 사용하지 않는다.

Notification ID가 같으면 **새 알림이 별도 카드로 생기는 것이 아니라 기존 알림을 교체**한다.

---

## 8. Notification Group도 서로 분리해야 한다

현재 group key:

```kotlin
private const val TIMER_NOTIFICATION_GROUP =
    "main_timer_notification_group"

private const val STOPWATCH_NOTIFICATION_GROUP =
    "stopwatch_notification_group"

private const val STOPWATCH2_NOTIFICATION_GROUP =
    "stopwatch2_notification_group"

private const val EXTRA_GROUP =
    "extra_timer_group"
```

타이머 알림에는:

```kotlin
.setGroup(TIMER_NOTIFICATION_GROUP)
```

스톱워치에는:

```kotlin
.setGroup(STOPWATCH_NOTIFICATION_GROUP)
```

스톱워치2에는:

```kotlin
.setGroup(STOPWATCH2_NOTIFICATION_GROUP)
```

를 사용한다.

### 매우 중요

타이머와 스톱워치를 다음처럼 같은 group으로 묶지 않는다.

```kotlin
.setGroup("clock_group")
```

같은 앱에서 실행되는 알림이라는 이유로 무조건 같은 group에 넣으면 안 된다.

현재 요구사항은:

```text
타이머 = 독립 카드
스톱워치 = 독립 카드
스톱워치2 = 독립 카드
```

이다.

즉 **그룹 키도 의도적으로 분리되어 있다.**

---

## 9. 스톱워치 시간을 매초 `notify()`로 갱신하지 말 것

현재 스톱워치 Job은 약 100ms마다 루프를 돌더라도 Notification을 매초 다시 게시하지 않는다.

현재 핵심:

```kotlin
private fun startStopwatchJob() {
    stopwatchJob?.cancel()

    stopwatchJob = scope.launch {
        var last = 0L

        while (isActive) {
            val t = System.currentTimeMillis()

            if (t - last >= 1000) {
                // Chronometer가 SystemUI에서 직접 증가한다.
                // 알림 전체를 매초 재게시하지 않는다.
                persistStopwatchState()
                last = t
            }

            delay(100)
        }
    }
}
```

스톱워치2도 같은 원칙이다.

```kotlin
private fun startStopwatch2Job() {
    stopwatch2Job?.cancel()

    stopwatch2Job = scope.launch {
        var last = 0L

        while (isActive) {
            val t = System.currentTimeMillis()

            if (t - last >= 1000) {
                persistStopwatch2State(
                    running = true,
                    accumulatedMs = 0L
                )
                last = t
            }

            delay(100)
        }
    }
}
```

### 잘못된 수정 예

향후 AI가 아래와 같이 바꾸면 안 된다.

```kotlin
while (isActive) {
    notifyStopwatch(currentElapsed)
    delay(1000)
}
```

또는:

```kotlin
notificationManager.notify(
    NID_STOPWATCH,
    buildStopwatchNotification(...)
)
```

를 매초 반복하면 안 된다.

시간은 `Chronometer`가 알아서 움직인다.

---

## 10. RemoteViews Chronometer를 유지할 것

스톱워치 알림은 정적인 TextView 숫자를 1초마다 갱신하는 것이 아니다.

현재 `buildRunningStopwatchNotification()`에서:

```kotlin
setChronometer(
    R.id.stopwatch_notification_chronometer,
    chronometerBase,
    "%s",
    true
)
```

를 사용한다.

base는 `SystemClock.elapsedRealtime()` 기준이다.

개념:

```kotlin
val chronometerBase =
    if (stableBaseElapsed > 0L) {
        stableBaseElapsed
    } else {
        SystemClock.elapsedRealtime() - elapsedMs
    }
```

Chronometer가 SystemUI에서 직접 증가하므로:

- 서비스의 1초 루프와 UI 표시가 어긋나는 문제 감소
- Notification 재게시 횟수 감소
- 알림 카드 재레이아웃 감소
- 타이머/스톱워치 충돌 가능성 감소

### 하지 말 것

정확한 Chronometer를 다시:

```text
TextView + 매초 setText + notify()
```

방식으로 되돌리지 않는다.

---

## 11. 스톱워치 알림 헤더의 작은 시간은 사용하지 않는다

현재 스톱워치 Notification Builder는:

```kotlin
.setShowWhen(false)
.setUsesChronometer(false)
```

를 사용한다.

이것은 Notification 헤더의 작은 Chronometer를 끄기 위한 것이다.

실제 시간은 Custom RemoteViews 내부의 Chronometer 하나만 사용한다.

이전에는:

```text
위 작은 SystemUI 시간
아래 "경과 시간 00:00:11"
```

처럼 시간이 두 번 보였고, 아래 숫자가 약 1초 늦어 보였다.

현재는 중복을 제거했다.

이 설정을 특별한 이유 없이 되돌리지 않는다.

---

## 12. `notifyStopwatch()`의 ID 선택 규칙 유지

현재:

```kotlin
private fun notifyStopwatch(elapsedMs: Long) {
    val id =
        if (foregroundLeader == Leader.STOPWATCH)
            FOREGROUND_ID_STOPWATCH
        else
            NID_STOPWATCH

    if (id == FOREGROUND_ID_STOPWATCH) {
        cancelSafe(NID_STOPWATCH)
    }

    notifySafe(id, buildStopwatchNotification(elapsedMs))
}
```

의도:

- 스톱워치가 현재 Foreground Leader이면 Foreground ID 사용
- 타이머가 Leader라면 스톱워치는 NID_STOPWATCH 사용
- 같은 스톱워치가 Foreground/일반 두 장으로 중복 표시되지 않도록 반대쪽 ID 정리

스톱워치2도 같은 규칙이다.

---

## 13. `onChannelPossiblyIdle()`의 우선순위를 변경할 때 주의

현재 실행 중인 작업을 다시 판단할 때 우선순위는 대략:

```text
1. 메인 타이머
2. 스톱워치
3. 스톱워치2
4. 보조 타이머
5. 아무 것도 없음 → Foreground 종료
```

핵심 코드 구조:

```kotlin
when {
    timerRunning || isTimerPaused -> {
        // TIMER를 Foreground Leader로
    }

    stopwatchRunning -> {
        // TIMER가 없으면 STOPWATCH가 Leader 가능
    }

    stopwatch2Running -> {
        // 앞의 기능들이 없으면 STOPWATCH2
    }

    extraRunning -> {
        // EXTRA
    }

    else -> {
        // 전체 정리 후 stopSelf()
    }
}
```

이 순서를 단순 리팩터링하면서 바꾸면 동시 실행 시 대표 알림 정책이 달라질 수 있다.

특히 사용자의 현재 의도는 **타이머가 동시에 실행 중이면 메인 타이머가 Foreground 대표 역할을 하는 것**이다.

---

## 14. 알림 정렬을 강제로 만들기 위해 매초 재게시하지 말 것

사용자가 원하는 시각적 의도:

```text
첫 번째: 타이머
두 번째: 스톱워치
```

하지만 Android의 최종 알림 카드 정렬은 SystemUI가 결정하는 부분도 있다.

따라서 "항상 첫 번째 줄에 타이머를 올리겠다"는 이유로:

```kotlin
notify(timer)
notify(stopwatch)
notify(timer)
notify(stopwatch)
```

같은 방식으로 반복 게시하면 안 된다.

이렇게 하면 과거의 충돌 문제가 다시 생길 수 있다.

현재 안정성 우선 정책은:

```text
타이머 = Foreground Leader
스톱워치 = 별도 ongoing
각각 독립 ID/group
불필요한 재게시 없음
```

이다.

---

## 15. 타이머 Notification도 불필요한 재게시를 최소화할 것

메인 타이머 역시 Custom `Chronometer`를 사용한다.

타이머의 남은 시간은 SystemUI Chronometer가 직접 카운트다운하도록 구성되어 있다.

따라서 단순히 숫자가 1초 줄었다는 이유만으로 전체 Notification을 매초 다시 만들어야 하는 것은 아니다.

Notification을 재게시해야 하는 대표적인 경우는 다음과 같은 **실제 상태 변경**이다.

```text
- 타이머 시작
- 일시정지
- 재개
- 종료
- 알림 레이아웃/상태 자체가 변경되는 경우
- Foreground Leader 전환
- 중요한 메타데이터가 실제로 변경된 경우
```

단순 시간 흐름은 Chronometer에 맡긴다.

---

## 16. `startOrSwitchForeground()` 수정 시 주의

현재 핵심:

```kotlin
private fun startOrSwitchForeground(
    leader: Leader,
    notification: Notification
)
```

이 함수는 Foreground ID를 리더별로 결정하고, 이전 Foreground ID와 새 ID가 다르면 이전 것을 정리한다.

```kotlin
if (
    isInForeground &&
    currentForegroundId != -1 &&
    currentForegroundId != newId
) {
    cancelSafe(currentForegroundId)
}
```

그 후 `startForeground()`를 호출하고:

```kotlin
isInForeground = true
foregroundLeader = leader
currentForegroundId = newId
```

를 갱신한다.

### 위험한 수정

다음 상태 변수 중 일부만 변경하거나 서로 불일치하게 만들지 말 것.

```text
isInForeground
foregroundLeader
currentForegroundId
```

세 값은 실제 Foreground 상태와 일치해야 한다.

---

## 17. "코드를 깨끗하게 만들겠다"며 통합하면 안 되는 부분

다음처럼 보일 수 있다.

```text
FOREGROUND_ID_TIMER
FOREGROUND_ID_STOPWATCH
FOREGROUND_ID_STOPWATCH2
NID_STOPWATCH
NID_STOPWATCH2
group key 여러 개
```

겉으로는 중복 코드처럼 보여도 **의도적으로 분리한 것**이다.

AI가 "ID가 너무 많으니 하나로 통합하겠습니다"라고 판단하면 안 된다.

또:

```text
TIMER_NOTIFICATION_GROUP
STOPWATCH_NOTIFICATION_GROUP
STOPWATCH2_NOTIFICATION_GROUP
```

도 하나로 합치지 않는다.

---

## 18. PendingIntent도 서로 덮어쓰지 않도록 유지

알림을 눌렀을 때:

- 타이머 알림 → 타이머 화면
- 스톱워치 알림 → 스톱워치 화면

으로 가야 한다.

현재 `mainPendingIntent()`는 action에 따라 requestCode를 구분한다.

```kotlin
val requestCode = when (action) {
    MainActivity.ACTION_OPEN_TIMER -> 2001
    MainActivity.ACTION_OPEN_STOPWATCH -> 2002
    else -> 2000
}
```

PendingIntent는 requestCode와 Intent 특성이 같으면 서로 재사용/덮어쓰기될 수 있으므로 이 분리를 함부로 제거하지 않는다.

---

## 19. 수정 후 반드시 해야 할 회귀 테스트

`ClockService.kt` 또는 알림 관련 코드를 수정했다면 실제폰과 가능하면 가상폰에서 아래 테스트를 수행한다.

### 테스트 A — 타이머 단독

```text
1. 메인 타이머 시작
2. 알림창 펼침
3. 20~30초 관찰
```

확인:

- 타이머 알림이 하나만 존재
- 숫자가 정상적으로 감소
- 카드가 반복해서 접혔다 펼쳐지지 않음
- 같은 타이머 알림이 2개 생기지 않음

### 테스트 B — 스톱워치 단독

```text
1. 스톱워치 시작
2. 알림창 펼침
3. 20~30초 관찰
```

확인:

- 스톱워치 알림 하나
- Chronometer가 직접 증가
- 매초 카드 전체가 깜빡이거나 재배치되지 않음

### 테스트 C — 타이머 먼저, 스톱워치 나중

**가장 중요**

```text
1. 메인 타이머 시작
2. 스톱워치 시작
3. 알림창 완전히 펼침
4. 최소 30초 관찰
```

정상:

```text
타이머 = 계속 존재
스톱워치 = 별도 카드로 계속 존재
두 카드가 서로 사라지거나 교체되지 않음
반복적인 확장/축소 없음
```

### 테스트 D — 스톱워치 먼저, 타이머 나중

```text
1. 스톱워치 시작
2. 알림창 확인
3. 메인 타이머 시작
4. 알림창에서 최소 30초 관찰
```

정상:

- 타이머가 Foreground Leader로 승격
- 스톱워치는 별도 ongoing 알림으로 계속 유지
- 스톱워치가 사라지지 않음
- 스톱워치가 2개 생기지 않음

### 테스트 E — 타이머 종료 후 스톱워치 유지

```text
1. 타이머 + 스톱워치 동시 실행
2. 타이머만 종료
```

정상:

- 타이머 알림 제거
- 스톱워치 계속 실행
- 필요하면 스톱워치가 Foreground 리더 역할을 이어받음
- 스톱워치 알림 중복 없음

### 테스트 F — 스톱워치 종료 후 타이머 유지

정상:

- 스톱워치 알림만 제거
- 타이머는 계속 정상 카운트다운
- Foreground Service 유지

### 테스트 G — 스톱워치2

일반 스톱워치뿐 아니라 `전환` 화면의 두 번째 스톱워치도 동일하게 테스트한다.

```text
타이머 + 스톱워치2
스톱워치2 + 타이머
```

두 경우 모두 독립 알림을 유지해야 한다.

---

## 20. 문제 재발 시 먼저 확인할 항목

### 증상: 타이머와 스톱워치 알림이 번갈아 움직임

확인:

```text
startStopwatchJob()
startStopwatch2Job()
타이머 갱신 루프
```

에서 `notify()`가 1초마다 호출되도록 다시 바뀌지 않았는지 확인.

---

### 증상: 타이머를 시작하면 스톱워치 알림이 사라짐

확인:

```text
ensureForeground()
previousLeader 복원 로직
NID_STOPWATCH
```

타이머로 Leader를 변경한 뒤 기존 스톱워치를 `NID_STOPWATCH`로 다시 게시하고 있는지 확인.

---

### 증상: 스톱워치가 두 장 나타남

확인:

```text
FOREGROUND_ID_STOPWATCH
NID_STOPWATCH
cancelSafe(...)
```

Foreground → 일반 또는 일반 → Foreground 전환 때 반대쪽 ID를 먼저 취소하고 있는지 확인.

---

### 증상: 타이머가 두 장 나타남

확인:

```kotlin
private const val NID_TIMER = FOREGROUND_ID_TIMER
```

가 유지되는지 확인.

타이머 Foreground ID와 일반 ID를 별도로 만들지 않는다.

---

### 증상: 두 알림이 하나의 묶음/대표 알림처럼 동작

확인:

```text
TIMER_NOTIFICATION_GROUP
STOPWATCH_NOTIFICATION_GROUP
STOPWATCH2_NOTIFICATION_GROUP
```

가 서로 다른 값인지 확인.

---

### 증상: 시간은 맞지만 알림 카드가 계속 흔들림

Notification 전체를 시간 표시 목적으로 너무 자주 다시 게시하고 있을 가능성이 높다.

**Chronometer가 움직일 수 있는 숫자는 Chronometer에 맡긴다.**

---

## 21. AI가 `ClockService.kt`를 수정할 때 따라야 할 절차

향후 AI는 `ClockService.kt`를 수정하기 전에 다음을 먼저 확인한다.

```text
1. 이번 수정이 타이머/스톱워치 Notification ID에 영향을 주는가?
2. Foreground Leader를 변경하는가?
3. notification group key를 변경하는가?
4. notify() 호출 빈도를 증가시키는가?
5. RemoteViews Chronometer를 TextView 갱신 방식으로 바꾸는가?
6. startForeground() ID를 변경하는가?
7. 타이머와 스톱워치가 동시에 실행되는 경우를 고려했는가?
```

위 질문 중 하나라도 `예`라면 반드시 **동시 실행 테스트**까지 해야 한다.

---

## 22. 향후 AI에게 바로 보여 줄 요약 지시문

> **ClockService 알림 유지보수 규칙**
>
> 이 앱은 메인 타이머와 스톱워치를 동시에 실행할 수 있다. 과거에는 타이머/스톱워치 Notification을 주기적으로 다시 `notify()`하면서 Samsung One UI와 가상폰 SystemUI에서 두 ongoing 알림이 서로 대표 알림을 차지하는 것처럼 확장/축소·재배치되는 충돌이 발생했다.
>
> 따라서 메인 타이머를 Foreground Leader로 우선 유지하고, 실행 중인 스톱워치/스톱워치2는 각각 고유한 일반 ongoing Notification ID로 독립 유지한다. 타이머와 스톱워치에는 서로 다른 notification group key를 사용한다.
>
> 스톱워치 시간은 `RemoteViews Chronometer`가 `SystemClock.elapsedRealtime()` 기준으로 직접 증가하므로 시간 표시를 위해 매초 `notify()`하지 않는다. 타이머의 Chronometer도 같은 원칙으로 불필요한 Notification 전체 재게시를 최소화한다.
>
> `FOREGROUND_ID_*`, `NID_STOPWATCH`, `NID_STOPWATCH2`, `TIMER_NOTIFICATION_GROUP`, `STOPWATCH_NOTIFICATION_GROUP`, `STOPWATCH2_NOTIFICATION_GROUP`, `ensureForeground()`, `startOrSwitchForeground()`, `onChannelPossiblyIdle()`를 단순화하거나 하나로 통합하지 않는다.
>
> 수정 후에는 반드시 `타이머→스톱워치`, `스톱워치→타이머`, `타이머+스톱워치2`, 한쪽만 종료하는 경우를 실제 알림창을 열어 둔 상태에서 테스트한다.

---

## 23. 현재 기준값 요약

```kotlin
const val FOREGROUND_ID_TIMER = 42
const val FOREGROUND_ID_EXTRA = 43
const val FOREGROUND_ID_STOPWATCH = 44
const val FOREGROUND_ID_STOPWATCH2 = 45

private const val NID_TIMER = FOREGROUND_ID_TIMER
private const val NID_STOPWATCH = 1002
private const val NID_STOPWATCH2 = 1003
```

Group:

```kotlin
private const val TIMER_NOTIFICATION_GROUP =
    "main_timer_notification_group"

private const val STOPWATCH_NOTIFICATION_GROUP =
    "stopwatch_notification_group"

private const val STOPWATCH2_NOTIFICATION_GROUP =
    "stopwatch2_notification_group"

private const val EXTRA_GROUP =
    "extra_timer_group"
```

이 값들은 특별한 이유 없이 변경하거나 통합하지 않는다.

---

## 24. 마지막 안전 규칙

현재 알림 구조에서 가장 위험한 변경은 다음 세 가지다.

```text
1. 모든 알림 ID를 하나로 통합
2. 타이머와 스톱워치를 같은 group key로 통합
3. 시간 숫자를 보여주기 위해 매초 Notification 전체를 재게시
```

이 세 가지는 **과거 알림 충돌 문제를 다시 만들 가능성이 높다.**

현재 안정화 구조는 다음 조합이다.

```text
Foreground Leader 하나
+ 독립 Notification ID
+ 독립 notification group
+ SystemUI Chronometer
+ 상태 변경 시에만 필요한 Notification 갱신
```

`ClockService.kt`를 수정할 때는 이 조합을 하나의 설계 단위로 보고 유지할 것.

---

## 25. 2026-09-10 추가 — 현재 시각 `시계` 알림은 ClockService Foreground 경쟁에서 분리

2번째(가로형) 스톱워치 화면에 `시계` 모드가 추가되었다.

관련 파일:

```text
app/src/main/java/com/krdonon/timer/ClockNotificationManager.kt
app/src/main/res/layout/notification_clock_compact.xml
app/src/main/res/layout/notification_clock_expanded.xml
```

### 핵심 설계

`시계`는 타이머/스톱워치처럼 경과 시간을 서비스에서 계산할 필요가 없다. 현재 시각은 Android `TextClock`이 SystemUI에서 직접 표시할 수 있다.

따라서 시계 알림을 `ClockService.Leader`에 추가하여 Foreground 알림 자리를 경쟁시키지 않는다.

```text
ClockService
  - 메인 타이머
  - 스톱워치
  - 스톱워치2
  - 보조 타이머

ClockNotificationManager (별도)
  - 현재 시각 알림
```

시계 알림은 다음 값을 독립적으로 사용한다.

```text
Notification ID : 1004
Channel         : wall_clock_display_channel
Group           : wall_clock_notification_group
```

이 ID/Channel/Group을 ClockService의 타이머 또는 스톱워치 값과 합치지 않는다.

### 매우 중요 — 시계 시간을 갱신하기 위해 매초 notify() 하지 말 것

시계 알림 XML은 `TextClock`을 사용한다.

```xml
<TextClock
    android:format12Hour="HH:mm:ss"
    android:format24Hour="HH:mm:ss" />
```

`TextClock`이 SystemUI에서 직접 현재 시간을 갱신하므로 다음과 같은 루프를 만들면 안 된다.

```kotlin
// 금지 예시
while (true) {
    notificationManager.notify(1004, buildClockNotification())
    delay(1000)
}
```

이렇게 바꾸면 과거의 타이머/스톱워치 알림 충돌과 동일한 SystemUI 재배치 문제가 다시 생길 수 있다.

### 앱 본문과 알림의 표시 정밀도가 다른 이유

가로형 스톱워치 화면의 시계 모드는:

```text
HH:mm:ss.SSS
예: 23:04:12.527
```

처럼 밀리초까지 화면에서 갱신한다.

반면 알림은:

```text
HH:mm:ss
예: 23:04:12
```

까지만 표시한다. 이것은 의도된 설계다. 알림에서 밀리초를 실시간으로 표시하기 위해 Notification 전체를 빠르게 재게시하면 알림 안정성이 크게 나빠질 수 있기 때문이다.

### 향후 AI 수정 규칙

- `ClockNotificationManager`를 단순화를 이유로 `ClockService`의 Foreground Leader 로직에 합치지 않는다.
- 시계 알림 ID `1004`를 `42`, `44`, `45`, `1002`, `1003` 등 기존 타이머/스톱워치 ID와 공유하지 않는다.
- 시계의 `wall_clock_notification_group`을 타이머/스톱워치 group과 합치지 않는다.
- 현재 시각 갱신 목적으로 반복 `notify()`를 추가하지 않는다.
- 알림의 `TextClock`을 정적인 `TextView` + 1초 반복 갱신 방식으로 바꾸지 않는다.
- 시계와 타이머/스톱워치를 동시에 켠 상태에서 알림창을 30초 이상 열어 회귀 테스트한다.
