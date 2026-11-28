# Timer 앱 — 스톱워치 `전환` 화면 방향 유지보수 설명서

> **목적:** 나중에 이 프로젝트를 다시 수정할 때, 스톱워치의 특수한 화면 회전 구조를 일반적인 Android 자동 회전 방식으로 오해해서 다시 망가뜨리지 않도록 하는 유지보수 문서입니다.  
> **기준 프로젝트:** 2026-09-09 수정 완료본  
> **패키지:** `com.krdonon.timer`  
> **핵심 수정 파일:** `app/src/main/java/com/krdonon/timer/StopwatchFragment.kt`

---

## 1. 반드시 먼저 이해할 사용자 요구사항

스톱워치에는 **일반 스톱워치 화면**과 `전환` 버튼으로 들어가는 **두 번째 스톱워치 화면**이 있습니다.

두 번째 스톱워치 화면은 일반적인 Android landscape 레이아웃이 아닙니다.  
**세로(portrait) Activity 안에서 UI 자체를 90도 돌려서 가로형 스톱워치처럼 보이게 만든 구조**입니다.

반드시 아래 두 사용자 환경을 모두 만족해야 합니다.

### A. 휴대폰 시스템이 `세로 화면 고정`인 사용자
- 앱을 세로로 사용한다.
- 스톱워치에서 `전환`을 누른다.
- 두 번째 스톱워치는 기존처럼 **화면 안에서 90도 돌아간 큰 가로형 UI**로 보여야 한다.
- 휴대폰 시스템의 세로 고정 상태에서도 이 동작이 가능해야 한다.

### B. 휴대폰 시스템이 `자동 회전`인 사용자
- 세로 상태에서 스톱워치 `전환`을 누른다.
- 휴대폰을 실제로 가로로 기울인다.
- 두 번째 스톱워치가 **화면 전체를 넓게 사용하는 정상적인 가로 사용 형태**로 보여야 한다.
- 과거 버그처럼 UI가 다시 세로 방향으로 겹쳐 돌아가거나, 화면 한쪽에 좁게 몰리면 안 된다.

즉, `전환` 화면에서는 **앱 내부의 90도 회전만 사용**하고, Android 시스템 자동 회전이 동시에 한 번 더 적용되지 않게 해야 한다.

---

## 2. 과거에 발생했던 버그의 원인

두 번째 스톱워치의 루트 레이아웃은 다음 클래스입니다.

```text
app/src/main/java/com/krdonon/timer/widget/QuarterTurnLayout.kt
```

이 클래스는 자식 UI를 직접 90도 회전합니다.

핵심 구조:

```kotlin
drawMatrix.postRotate(90f)
drawMatrix.postTranslate(width.toFloat(), 0f)
```

또한 단순한 `android:rotation="90"`이 아니라:

- `onMeasure()`에서 width/height 측정 스펙을 교환
- `onLayout()`에서 자식을 가로 화면 기준 크기로 배치
- `dispatchDraw()`에서 Matrix로 90도 회전
- `dispatchTouchEvent()`에서 터치 좌표를 역변환

까지 처리합니다.

따라서 이 화면은 이미 **자체적으로 한 번 회전한 화면**입니다.

### 문제 상황

Android 시스템 자동 회전까지 허용하면:

1. `QuarterTurnLayout`이 UI를 90도 회전
2. 사용자가 휴대폰을 가로로 돌림
3. Activity 자체도 landscape로 다시 회전
4. 결과적으로 회전이 중복 적용됨
5. 스톱워치가 화면 중앙에서 정상적인 가로형으로 보이지 않고 좁고 세로로 보이는 현상이 발생

즉, 원인은 **`QuarterTurnLayout`의 내부 회전 + 시스템 Activity 회전의 중복**입니다.

---

## 3. 2026-09-09에 적용한 해결 방식

수정은 원칙적으로 다음 파일 **1개**에서 처리했습니다.

```text
app/src/main/java/com/krdonon/timer/StopwatchFragment.kt
```

추가 import:

```kotlin
import android.content.pm.ActivityInfo
```

두 번째 스톱워치가 실제로 화면에 표시되는 동안 Activity를 portrait로 유지합니다.

```kotlin
private fun lockHostToPortraitForSecondStopwatch() {
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
```

두 번째 스톱워치를 벗어나면 Android 시스템 설정을 다시 따르게 합니다.

```kotlin
private fun restoreHostOrientationToSystem() {
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
```

### 중요한 의미

`SCREEN_ORIENTATION_PORTRAIT`는 사용자가 보는 UI를 일반 세로 스톱워치로 만들기 위한 것이 아닙니다.

**Activity 좌표계를 세로 상태로 안정적으로 유지한 뒤 `QuarterTurnLayout`이 딱 한 번 90도 회전하게 만드는 장치**입니다.

그래야:

- 시스템 세로 고정 사용자 → `전환` 화면 정상
- 시스템 자동 회전 사용자 → 휴대폰을 가로로 들었을 때도 이중 회전 없음

을 동시에 만족합니다.

---

## 4. `applyStopwatchMode()`의 핵심 규칙

현재 구조의 핵심은 다음과 같습니다.

```kotlin
private fun applyStopwatchMode() {
    if (showingSecond) {
        ensureSecondStopwatchFragment()
        classicGroup.visibility = View.GONE
        secondContainer.visibility = View.VISIBLE

        if (!isHidden) {
            lockHostToPortraitForSecondStopwatch()
        }
    } else {
        secondContainer.visibility = View.GONE
        classicGroup.visibility = View.VISIBLE

        if (!isHidden) {
            restoreHostOrientationToSystem()
        }
    }
}
```

### 절대 놓치면 안 되는 부분

```kotlin
if (!isHidden)
```

가 중요합니다.

이 앱은 하단 탭으로 `알림 / 스톱워치 / 타이머` 등을 전환합니다.

스톱워치 Fragment가 숨겨져 있는데도 portrait 잠금이 남아 있으면, 다른 탭까지 강제로 세로 고정될 수 있습니다.

따라서 **두 번째 스톱워치가 실제로 현재 보일 때만** portrait 잠금을 적용해야 합니다.

---

## 5. 하단 탭 변경 시 반드시 방향 잠금을 해제해야 함

현재 다음 코드가 중요한 안전장치입니다.

```kotlin
override fun onHiddenChanged(hidden: Boolean) {
    super.onHiddenChanged(hidden)

    if (hidden) {
        restoreHostOrientationToSystem()
    } else if (showingSecond) {
        lockHostToPortraitForSecondStopwatch()
    }
}
```

의미:

- 스톱워치 탭을 떠남 → `UNSPECIFIED`로 복원
- 다시 스톱워치 탭으로 돌아왔고 두 번째 스톱워치가 선택된 상태 → 다시 portrait 잠금

### 이 부분을 삭제하면 생길 수 있는 문제

- 타이머 탭이 자동 회전하지 않음
- 알림 탭까지 세로 강제 고정됨
- 두 번째 스톱워치로 다시 돌아왔을 때 이중 회전 문제가 재발할 수 있음

---

## 6. `전환` 버튼 흐름

### 일반 스톱워치 → 두 번째 스톱워치

```kotlin
private fun showSecondStopwatch() {
    resetKeepScreenState()
    showingSecond = true
    applyStopwatchMode()
}
```

이때 `applyStopwatchMode()`가 portrait 잠금을 적용합니다.

### 두 번째 스톱워치 → 일반 스톱워치

```kotlin
fun showClassicStopwatch() {
    resetKeepScreenState()
    showingSecond = false
    applyStopwatchMode()
}
```

이때 `applyStopwatchMode()`가:

```kotlin
restoreHostOrientationToSystem()
```

을 호출하여 다시 사용자의 Android 화면 회전 설정을 따릅니다.

---

## 7. 상태 복원도 유지해야 함

현재 두 번째 스톱워치 표시 여부는:

```kotlin
private const val KEY_SHOWING_SECOND = "showing_second_stopwatch"
```

로 저장합니다.

복원:

```kotlin
showingSecond =
    savedInstanceState?.getBoolean(KEY_SHOWING_SECOND, false) ?: false
```

저장:

```kotlin
override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(KEY_SHOWING_SECOND, showingSecond)
}
```

이 구조를 유지해야 구성 변경이나 Fragment 재생성 뒤에도 어느 스톱워치가 선택되어 있었는지 맞출 수 있습니다.

---

## 8. 관련 파일 구조

### 핵심 부모 Fragment

```text
app/src/main/java/com/krdonon/timer/StopwatchFragment.kt
```

클래스명:

```kotlin
class StopWatchFragment : Fragment()
```

> **주의:** 파일명은 `StopwatchFragment.kt`이지만 클래스명은 `StopWatchFragment`입니다. 대소문자 형태를 임의로 바꾸지 마십시오.

### 두 번째 스톱워치 Fragment

```text
app/src/main/java/com/krdonon/timer/StopwatchSecondFragment.kt
```

두 번째 스톱워치의 실제 버튼/시간/랩 동작을 담당합니다.

### 특수 회전 레이아웃

```text
app/src/main/java/com/krdonon/timer/widget/QuarterTurnLayout.kt
```

**이번 화면 방향 문제의 핵심 구조입니다.**

### 일반 스톱워치 화면

```text
app/src/main/res/layout/fragment_stopwatch.xml
```

두 번째 스톱워치가 들어가는 컨테이너:

```xml
<FrameLayout
    android:id="@+id/secondStopwatchContainer"
    ... />
```

### 두 번째 스톱워치 화면

```text
app/src/main/res/layout/activity_stopwatch_second.xml
```

루트:

```xml
<com.krdonon.timer.widget.QuarterTurnLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent">
```

또한 현재 프로젝트에는:

```text
app/src/main/res/layout-land/activity_stopwatch_second.xml
```

도 존재합니다.

두 파일 모두 `QuarterTurnLayout`을 사용합니다.

---

## 9. 향후 수정 시 하지 말아야 할 것

### 금지 1 — `QuarterTurnLayout`을 보고 "불필요한 90도 회전"이라 판단하여 제거

하지 마십시오.

이 앱은 **시스템 세로 고정 사용자도 전환 스톱워치를 가로형으로 사용하게 하려고 일부러 만든 구조**입니다.

일반적인 landscape XML로 바꾸려면 전체 요구사항을 다시 설계해야 합니다.

---

### 금지 2 — 두 번째 스톱워치에서 `SCREEN_ORIENTATION_UNSPECIFIED`를 계속 사용

두 번째 스톱워치가 표시되는 동안 `UNSPECIFIED`이면 자동 회전 사용자의 Activity가 landscape로 회전하면서 `QuarterTurnLayout`과 중복 회전될 수 있습니다.

`UNSPECIFIED`는 **두 번째 스톱워치를 벗어났을 때 복원용**입니다.

---

### 금지 3 — 두 번째 스톱워치 진입 시 `SCREEN_ORIENTATION_LANDSCAPE` 사용

이 화면은 Activity를 landscape로 만드는 구조가 아닙니다.

다음과 같이 바꾸면 안 됩니다.

```kotlin
activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
```

또는:

```kotlin
SCREEN_ORIENTATION_SENSOR_LANDSCAPE
SCREEN_ORIENTATION_USER_LANDSCAPE
```

현재 구조에서는 Activity를 portrait 좌표계로 유지한 뒤 `QuarterTurnLayout`이 회전해야 합니다.

---

### 금지 4 — 앱 전체 또는 `MainActivity`를 항상 portrait로 고정

두 번째 스톱워치 때문에 앱 전체를 고정하면 안 됩니다.

사용자가 Android에서 자동 회전을 사용하는 경우 다른 화면은 정상적으로 시스템 회전 설정을 따라야 합니다.

**잠금 범위는 "두 번째 스톱워치가 실제로 보이는 동안"만**이어야 합니다.

---

### 금지 5 — `onHiddenChanged()`의 방향 복원을 삭제

스톱워치 탭이 숨겨진 뒤에도 방향 잠금이 남는 버그가 생길 수 있습니다.

---

### 금지 6 — 단순히 `android:rotation="90"`으로 교체

`QuarterTurnLayout`은 그림만 돌리는 것이 아닙니다.

측정, 배치, 그리기, 터치 좌표까지 처리하므로 단순 `View.rotation`과 동일하지 않습니다.

특히 버튼 터치 위치가 어긋날 수 있습니다.

---

## 10. 향후 AI/개발자가 먼저 확인할 코드

화면 회전 문제를 수정하기 전에 반드시 아래 순서로 확인하십시오.

1. `StopwatchFragment.kt`
   - `showingSecond`
   - `applyStopwatchMode()`
   - `lockHostToPortraitForSecondStopwatch()`
   - `restoreHostOrientationToSystem()`
   - `onHiddenChanged()`
   - `showSecondStopwatch()`
   - `showClassicStopwatch()`

2. `QuarterTurnLayout.kt`
   - `onMeasure()`
   - `onLayout()`
   - `updateMatrices()`
   - `dispatchDraw()`
   - `dispatchTouchEvent()`

3. `fragment_stopwatch.xml`
   - `secondStopwatchContainer`

4. `activity_stopwatch_second.xml`
   - 루트가 `QuarterTurnLayout`인지 확인

5. `layout-land/activity_stopwatch_second.xml`
   - 별도 landscape 리소스가 현재 구조와 충돌하지 않는지 확인

---

## 11. 회귀 테스트 체크리스트

향후 이 기능과 관련된 코드를 조금이라도 수정했다면 **최소 아래 8가지를 실제 기기에서 테스트**하십시오.

### 테스트 A — Android 시스템 `세로 고정`

#### A-1
- 휴대폰 시스템: 세로 고정
- 앱: 세로 상태
- 스톱워치 일반 화면 진입
- 결과: 정상 세로 UI

#### A-2
- `전환` 버튼 누름
- 결과: 두 번째 스톱워치가 **기존처럼 90도 돌아간 큰 화면**으로 정상 표시

#### A-3
- 두 번째 스톱워치에서 `전환`으로 일반 스톱워치 복귀
- 결과: 정상 복귀

#### A-4
- 두 번째 스톱워치 상태에서 하단 `타이머` 또는 `알림` 탭 이동
- 결과: 다른 탭에 방향 강제 상태가 이상하게 남지 않아야 함

---

### 테스트 B — Android 시스템 `자동 회전`

#### B-1
- 휴대폰을 세로로 든 상태에서 앱 실행
- 일반 스톱워치 확인
- 결과: 정상

#### B-2
- `전환` 누름
- 휴대폰을 세로로 계속 든 상태
- 결과: 세로 Activity 안에서 두 번째 스톱워치가 90도 회전된 형태로 정상 표시

#### B-3 — 가장 중요
- 두 번째 스톱워치 상태에서 휴대폰을 물리적으로 가로로 돌림
- 결과: 사용자 눈에는 **화면 전체를 넓게 쓰는 정상적인 가로 스톱워치**처럼 보여야 함
- 실패 증상:
  - 숫자가 세로로 서 있음
  - 화면 한쪽으로 좁게 몰림
  - UI가 2번 회전한 것처럼 보임
  - 버튼이 이상한 방향으로 보임

이 증상이 나오면 **이중 회전 문제 재발**입니다.

#### B-4
- 일반 스톱워치로 복귀하거나 다른 탭으로 이동
- 휴대폰 방향을 바꿈
- 결과: 앱이 다시 Android 시스템 자동 회전 정책을 정상적으로 따라야 함

---

## 12. 터치 테스트도 반드시 해야 함

`QuarterTurnLayout`은 터치 좌표를 역변환합니다.

따라서 화면이 시각적으로 정상이어도 아래 버튼을 모두 눌러 보십시오.

- `초기화`
- `시작`
- `전환`
- `화면꺼짐 방지`

보이는 버튼 위치와 실제 터치 영역이 정확히 일치해야 합니다.

터치 위치가 어긋난다면 `QuarterTurnLayout.dispatchTouchEvent()` 또는 Matrix 계산을 의심해야 합니다.

---

## 13. 현재 정상 동작을 한 문장으로 정의

> **두 번째 스톱워치에서는 Activity를 portrait 좌표계로 유지하고 `QuarterTurnLayout`만 90도 회전시키며, 두 번째 스톱워치를 벗어나는 즉시 Activity 방향 제어를 `UNSPECIFIED`로 돌려 사용자의 시스템 회전 설정에 반환한다.**

이 문장이 현재 해결책의 핵심입니다.

---

## 14. 나중에 같은 문제가 생겼을 때 진단 순서

### 증상 1
자동 회전 사용자가 휴대폰을 가로로 돌렸더니 UI가 좁고 세로로 보인다.

**가장 먼저 확인:**

```kotlin
activity?.requestedOrientation
```

두 번째 스톱워치 표시 중에 정말:

```kotlin
ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
```

인지 확인하십시오.

그다음 `QuarterTurnLayout`이 여전히 적용되어 있는지 확인하십시오.

---

### 증상 2
타이머/알림 화면까지 계속 세로 고정된다.

확인:

```kotlin
onHiddenChanged(hidden: Boolean)
```

에서 숨겨질 때:

```kotlin
restoreHostOrientationToSystem()
```

가 호출되는지 확인하십시오.

또한 일반 스톱워치로 돌아갈 때 `applyStopwatchMode()`가 `UNSPECIFIED`로 복원하는지 확인하십시오.

---

### 증상 3
시스템 세로 고정 사용자에게 `전환` 화면이 더 이상 가로형으로 보이지 않는다.

`QuarterTurnLayout`을 제거하거나 일반 ConstraintLayout으로 바꾸지 않았는지 확인하십시오.

---

### 증상 4
화면은 맞는데 버튼 터치 위치가 틀린다.

`QuarterTurnLayout`의 다음 코드를 확인하십시오.

```kotlin
drawMatrix.invert(inverseMatrix)
```

그리고:

```kotlin
transformed.transform(inverseMatrix)
```

이 동작이 유지되어야 합니다.

---

## 15. 구조를 크게 바꾸고 싶다면

향후 이 화면을 완전히 네이티브 portrait/landscape 대응 구조로 다시 설계하는 것은 가능하지만, 그것은 **이번 수정의 연장선이 아니라 별도 리팩터링 작업**으로 취급해야 합니다.

그 경우 반드시:

- 시스템 세로 고정 사용자
- 시스템 자동 회전 사용자
- 세로 → 가로
- 가로 → 세로
- 일반 ↔ 두 번째 스톱워치 전환
- 하단 탭 이동
- Fragment 재생성
- 버튼 터치 좌표
- 화면 꺼짐 방지 상태

를 모두 다시 설계/검증해야 합니다.

현재 정상 작동하는 구조가 있다면 단순 정리 목적으로 리팩터링하지 않는 것이 안전합니다.

---

## 16. 향후 AI에게 전달할 지시문

프로젝트를 다시 분석하는 AI는 아래 원칙을 먼저 따르십시오.

> 이 앱의 두 번째 스톱워치는 일반 Android landscape 화면이 아니다.  
> `QuarterTurnLayout`이 portrait Activity 내부에서 UI를 90도 회전하고 터치 좌표까지 역변환한다.  
> 자동 회전 상태에서 Activity까지 landscape로 회전하면 이중 회전 버그가 발생한다.  
> 따라서 두 번째 스톱워치가 실제로 보이는 동안만 `SCREEN_ORIENTATION_PORTRAIT`를 사용하고, 일반 스톱워치 또는 다른 하단 탭으로 이동하면 즉시 `SCREEN_ORIENTATION_UNSPECIFIED`로 복원해야 한다.  
> `QuarterTurnLayout`, `applyStopwatchMode()`, `onHiddenChanged()`를 임의로 단순화하지 말고, 변경 후에는 세로 고정/자동 회전 두 환경을 모두 실제 기기에서 회귀 테스트한다.

---

## 17. 2026-09-09 수정 범위 기록

이번 회전 문제 해결을 위해 직접 변경한 파일:

```text
app/src/main/java/com/krdonon/timer/StopwatchFragment.kt
```

이번 해결 과정에서 **변경하지 않은 핵심 파일**:

```text
app/src/main/java/com/krdonon/timer/widget/QuarterTurnLayout.kt
app/src/main/java/com/krdonon/timer/StopwatchSecondFragment.kt
app/src/main/res/layout/fragment_stopwatch.xml
app/src/main/res/layout/activity_stopwatch_second.xml
app/src/main/res/layout-land/activity_stopwatch_second.xml
app/src/main/AndroidManifest.xml
```

향후 문제가 생기면 우선 `StopwatchFragment.kt`의 방향 잠금/복원 로직이 유지되고 있는지 비교하십시오.

---

## 18. 마지막 안전 규칙

**현재 기능이 정상이라면 화면 방향 관련 코드는 이유 없이 정리·통합·최적화하지 마십시오.**

특히 아래 세 요소는 하나의 세트입니다.

```text
1. QuarterTurnLayout 자체 90도 회전
2. 두 번째 스톱워치 표시 중 Activity portrait 유지
3. 화면을 벗어날 때 Activity UNSPECIFIED 복원
```

셋 중 하나만 변경하면 과거 버그가 다시 나타날 가능성이 큽니다.
