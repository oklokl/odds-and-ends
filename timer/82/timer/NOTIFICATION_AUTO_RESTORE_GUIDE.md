# Timer 앱 — 알림바 삭제 시 자동 복원 및 알람 끄기 유지보수 설명서

> **목적:** 향후 AI나 개발자가 알림바 관련 코드를 수정할 때, **알림 삭제 시 동작 복원 로직**과 **알람 울림 시 컨트롤 보장 규칙**을 망가뜨리지 않도록 방지하는 지침서입니다.  
> **기준 프로젝트:** 2026-09-10 구현 완료본  
> **패키지:** `com.krdonon.timer`

---

## 1. 해결하고자 한 문제의 배경

Android 14 (API 34) 이상부터는 구글의 시스템 정책 변경으로 인해, **Foreground Service(포그라운드 서비스)의 Ongoing 알림이라도 사용자가 스와이프하거나 '모두 지우기'를 눌러 알림창에서 제거**할 수 있게 되었습니다.

이로 인해 다음과 같은 심각한 사용자 경험 문제가 발생했습니다.

1. **알람이 계속 울리는데 끌 방법이 없음**:
   - 타이머 종료 알람이나 요일 알람이 소리/진동으로 울리는 도중 사용자가 당황하여 알림창의 알림을 옆으로 쓸어 넘기거나 `모두 지우기`를 누르면 알림 카드가 사라짐.
   - 백그라운드 서비스는 계속 소리와 진동을 재생 중인데 알림창에 제어 카드가 없어 사용자가 알람을 끌 수 없는 상황 발생.
2. **스톱워치나 타이머가 돌고 있는데 알림 컨트롤 바가 사라짐**:
   - 타이머 카운트다운 또는 스톱워치 측정 중에 알림을 실수로 지우면 화면에 들어가지 않고는 알림창에서 상태를 보거나 일시정지/정지할 수 없음.
3. **정지된 알림은 지워져야 함**:
   - 사용자가 작업을 완전히 '중지'하거나 '초기화'한 상태에서는 알림을 지웠을 때 정상적으로 사라져야 하는데, 무조건 다시 띄우면 사용자가 알림을 영영 지울 수 없는 버그가 생김.

---

## 2. 핵심 설계 원칙 (3가지 규칙)

```text
1. 사용자가 직접 지웠을 때만 이벤트 감지 (Notification deleteIntent)
2. 1초 뒤 현재 실제 동작 상태(카운트 중 / 울리는 중)를 정밀 검사
3. 살아있는 작업만 알림바 자동 복원, 완전 종료된 작업은 삭제 유지
```

---

## 3. 관련 핵심 파일 목록

```text
app/src/main/java/com/krdonon/timer/NotificationDismissReceiver.kt   [신규: 삭제 이벤트 수신]
app/src/main/java/com/krdonon/timer/ClockService.kt                  [타이머/스톱워치/보조타이머 복원]
app/src/main/java/com/krdonon/timer/ClockNotificationManager.kt      [24시간 시계 알림 복원]
app/src/main/java/com/krdonon/timer/alarm/AlarmService.kt            [타이머 종료 알람 복원 & 알람 끄기 버튼]
app/src/main/java/com/krdonon/timer/alarm/WeekdayAlarmService.kt     [요일 알람 복원 & 알람 끄기 버튼]
app/src/main/AndroidManifest.xml                                     [리시버 등록]
```

---

## 4. 세부 동작 구조

### (1) `deleteIntent` 등록
모든 알림 빌더(`NotificationCompat.Builder`)에 `setDeleteIntent()`를 등록합니다.
- 코드로 `NotificationManager.cancel()`을 호출한 경우에는 안드로이드 시스템이 `deleteIntent`를 발송하지 않습니다.
- **오직 사용자가 알림창에서 스와이프하거나 '모두 지우기'를 눌렀을 때만** `deleteIntent`가 동작합니다.

```kotlin
val deleteIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
    action = NotificationDismissReceiver.ACTION_DISMISS_XXX
}
val deletePi = PendingIntent.getBroadcast(
    context, reqCode, deleteIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
builder.setDeleteIntent(deletePi)
```

### (2) `NotificationDismissReceiver.kt`
- Manifest에 `exported="false"`로 안전하게 등록되어 있으며, 알림 닫힘 인텐트를 수신하여 각 전담 서비스의 `handleNotificationDismissed()`로 전달합니다.

### (3) 각 서비스의 상태 판단 및 1초 지연 복원
사용자가 알림창에서 카드를 넘겼을 때 시스템 UI 애니메이션이 자연스럽게 끝나도록 **1초(`delay(1000L)`) 뒤** 상태를 확인합니다.

#### A. `AlarmService.kt` (타이머 종료 알람)
- 검사: `prefs.getBoolean(KEY_RINGING, false)`
- 울리는 중이면 ➔ `NotificationManagerCompat.notify()`로 알림 카드 즉시 복원
- 알림 카드에 `[알람 끄기]` 원터치 액션 버튼이 기본 제공되어 스와이프하지 않고 바로 끌 수 있음.

#### B. `WeekdayAlarmService.kt` (요일 반복 알람)
- 검사: `isRingingNow == true`
- 울리는 중이면 ➔ 알림 카드 즉시 복원 (`[알람 끄기]` 버튼 포함)

#### C. `ClockService.kt` (타이머 / 스톱워치 / 스톱워치2 / 보조타이머)
- **타이머**: `(timerJob != null) && !isTimerPaused` 또는 `isTimerPaused` 상태인 경우에만 `notifyTimer()`로 복원
  - 완전 중지(`stopTimer`) 상태이면 복원하지 않음.
- **스톱워치 1번**: `stopwatchJob != null`인 경우에만 `notifyStopwatch()`로 복원
  - 초기화/정지 상태이면 복원하지 않음.
- **스톱워치 2번**: `stopwatch2Job != null`인 경우에만 `notifyStopwatch2()`로 복원
- **보조 타이머**: `extraTimers` 중 실행 중인(`job != null && endElapsed > now`) 타이머만 복원

#### D. `ClockNotificationManager.kt` (24시간 시계 모드)
- 검사: `isActive(context) == true`
- 시계 모드가 ON 상태이면 ➔ 1초 뒤 알림 복원
- 시계 모드를 끈 상태이면 ➔ 복원하지 않고 종료 유지

---

## 5. 향후 AI 및 개발자가 절대 하지 말아야 할 것

1. **`setDeleteIntent`를 임의로 제거하지 말 것**:
   - 알림 빌더 리팩터링 시 `setDeleteIntent`가 누락되면 Android 14+ 기기에서 사용자가 알림을 끄지 못하는 치명적 버그가 재발합니다.
2. **지연 시간(`1000L`)을 즉시(`0L`)로 바꾸지 말 것**:
   - 사용자가 알림을 스와이프하는 손가락 모션 도중에 알림이 즉시 재게시되면 화면이 덜덜 떨리거나(Jitter) 스와이프가 씹히는 현상이 발생합니다. 1초 여유를 두어야 부드럽게 재등장합니다.
3. **상태 검사(`isRinging`, `timerJob != null` 등)를 생략하고 무조건 복원하지 말 것**:
   - 사용자가 앱 내에서 타이머를 껐는데도 지운 알림이 계속 다시 나타나 좀비 알림이 됩니다. 반드시 현재 활성 상태인지 확인한 뒤 복원해야 합니다.
4. **`[알람 끄기]` 액션 버튼을 제거하지 말 것**:
   - 알람 울림 알림(타이머/요일)에는 알림창을 내린 즉시 해제할 수 있도록 `ACTION_STOP` 버튼이 필수적입니다.
