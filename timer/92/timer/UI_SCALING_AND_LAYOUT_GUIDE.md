# Timer 앱 — 폰트·디스플레이 크기 대응 및 레이아웃 정렬 유지보수 설명서

> **목적:** 향후 UI를 수정하거나 새 화면을 추가할 때, 휴대폰의 **글꼴 크기 변경** 또는 **디스플레이 크기(화면 크게/작게)** 변경으로 글자가 깨지거나(`...`, 강제 줄바꿈, 세로 잘림) 버튼 정렬·그림자가 무너지는 현상이 재발하지 않도록 방지하는 기준 문서입니다.  
> **패키지:** `com.krdonon.timer`

---

## 1. 시스템 폰트 크기(`fontScale`) 고정 원칙 (`BaseActivity`)

타이머·숫자패드·스톱워치·요일 선택 버튼은 고정 비율의 원형/캡슐형 버튼 안에 배치되므로, 시스템 글꼴 크기(`fontScale`)가 1.3~2.0배로 커지면 버튼 내부에서 글자가 꺾이거나(`다\n음`, `10\nㅂ`) 세로가 잘리는 문제가 발생합니다.

따라서 앱 내 **모든 Activity는 반드시 `BaseActivity`를 상속**해야 합니다.

```text
app/src/main/java/com/krdonon/timer/BaseActivity.kt
```

* `BaseActivity.attachBaseContext()`는 `Configuration.fontScale`을 `1.0f`로 고정합니다.
* **`densityDpi`는 고정하지 않습니다.** (`DENSITY_DEVICE_STABLE`로 강제 고정하면 삼성 기기의 해상도 변경(HD+/FHD+/WQHD+)이나 폴더블·멀티윈도우 배율과 어긋날 수 있습니다.)
* **금지 사항:**
  * 새 Activity를 추가할 때 `AppCompatActivity()`를 직접 상속하지 말 것 (반드시 `BaseActivity()` 상속).
  * `TimerApp`(Application)에만 `fontScale`을 적용하면 Activity의 뷰 인플레이션에는 반영되지 않으므로 `BaseActivity` 방식을 유지할 것.

현재 `BaseActivity` 상속 목록:
* `MainActivity.kt`
* `alarm/AlarmActivity.kt`
* `alarm/AlarmAgainActivity.kt`
* `alarmclock/AlarmEditActivity.kt`

---

## 2. 디스플레이 크기(`densityDpi`) 확대 대응 레이아웃 규칙

`fontScale`을 `1.0f`로 고정해도, 사용자가 시스템의 **디스플레이 크기(화면 크게/작게)** 를 키우면 `1dp`의 픽셀 크기가 커지면서 **가용 가로 폭(`dp`)이 `320dp` 수준까지 좁아집니다.**

이 환경에서도 UI가 깨지지 않도록 XML 레이아웃 작성 시 아래 규칙을 반드시 지킵니다.

### 규칙 1 — 좁은 가로 버튼에 Material 기본 패딩(`24dp`)을 그대로 두지 않는다
Material 3 버튼(`Widget.Material3.Button*`)은 기본 좌우 패딩이 커서 `weight` 버튼이나 `80dp` 내외 버튼에서 텍스트 폭이 크게 줄어듭니다 (`초기화` → `...`, `소리 선택` → `소리`, `다음` → `다\n음`).

가로 공간이 제한된 버튼에는 아래 속성을 함께 지정합니다.
```xml
android:minWidth="0dp"
android:paddingStart="4dp"
android:paddingEnd="4dp"
android:maxLines="1"
android:autoSizeTextType="uniform"
android:autoSizeMinTextSize="10sp"
android:autoSizeMaxTextSize="14sp"
android:autoSizeStepGranularity="1sp"
```

적용 대상 예시:
* `activity_main.xml`: 하단 탭(`btnAlarm`, `btnStopwatch`, `btnTimer`)
* `fragment_timer.xml`: `btnStartTimer`, `btnResetTimer`, `btnAddTimer`, `btnPickTimerSound`, `btnAlertMode`, `btnTimerRingDuration`, `btnAddCustomPreset`, `btnCancelCustomPresetDelete`, `btnLog`
* `fragment_number_pad.xml`: `btnNext`, `btnTomorrow`, `btnAmPm`, `btnPreset5`~`btnPreset50`
* `fragment_stopwatch.xml`: `btnLapReset`, `btnStartStop`, `btnSwitchStopwatch`, `btnKeepScreenClassic`
* `activity_stopwatch_second.xml` / `layout-land/activity_stopwatch_second.xml`: `btnSecondStartStop`, `btnSecondReset`, `btnSecondSwitch`, `btnSecondKeepScreen`, `btnSecondClock`
* `activity_alarm_edit.xml`: `btnCalendar`, `btnEveryWeek`, `btnCancel`, `btnSave`
* `timer_block.xml`: `blockStartBtn`, `blockDeleteBtn`

### 규칙 2 — 7등분 요일 버튼(`daySun`~`daySat`)에 `minWidth="40dp"` 금지
`activity_alarm_edit.xml`의 `dayGroup`은 7개 버튼이 한 줄에 들어갑니다. 각 버튼에 `minWidth="40dp"`를 넣으면 여백 포함 최소 `344dp` 이상을 요구해 화면 확대(`320dp` 폭) 시 우측 요일 버튼이 밀리거나 잘립니다.
* 반드시 `android:layout_width="0dp"`, `android:layout_weight="1"`, `android:minWidth="0dp"`, `android:paddingStart="0dp"`, `android:paddingEnd="0dp"`, `autoSizeTextType="uniform"` 조합을 유지합니다.

### 규칙 3 — 라벨 행에 고정폭(`280dp` 등) 금지
`fragment_timer.xml`과 `fragment_number_pad.xml`의 `시간 / 분 / 초 / 천분` 헤더 행은 카드 내부 폭에 맞추어 `android:layout_width="match_parent"`를 사용합니다.

### 규칙 4 — 2번째 가로 스톱워치(`QuarterTurnLayout`) 세로 컨트롤 공간 확보
`activity_stopwatch_second.xml`과 `layout-land/activity_stopwatch_second.xml`은 회전된 세로 폭(`320dp` 기준) 안에 2줄 버튼(`시작·초기화·전환·화면꺼짐 방지` + 아래 `시계`)이 들어갑니다.
* `guidelineTimeBottom` 비율은 **`0.66`** 이하(하단 34% 이상 확보)를 유지하고, 1행 높이 `38dp` + 간격 `4dp` + 2행 높이 `34dp` 구조를 깨뜨리지 않습니다.

### 규칙 5 — 알람 목록 FAB 정렬 및 그림자 (`fragment_alarm_list.xml`)
* `fabGroups`(그룹 관리 도구 버튼)와 `fabAdd`(+ 추가 버튼)에 서로 다른 `layout_marginBottom` 값을 주면 수평 높이가 어긋납니다.
* `fabGroups`는 반드시 `fabAdd`를 기준으로 **`app:layout_constraintTop_toTopOf="@id/fabAdd"`** 와 **`app:layout_constraintBottom_toBottomOf="@id/fabAdd"`** 로 수평 정렬하고, `app:fabSize="mini"`로 크기를 어긋나게 하지 않습니다.
* 루트 `ConstraintLayout`에 `android:clipChildren="false"`, `android:clipToPadding="false"`를 유지하고, 두 FAB 모두 `android:elevation="12dp"` 및 `app:elevation="12dp"`를 유지해 그림자가 잘리거나 평면적으로 보이지 않도록 합니다.

---

## 3. UI 수정 후 필수 체크리스트

1. 시스템 설정 → **글꼴 크기 최대**에서 타이머, 숫자패드, 스톱워치(1번/2번), 알람 목록/편집 화면 확인
2. 시스템 설정 → **화면 크게/작게(디스플레이 크기) 최대**에서:
   * 타이머 `초기화` / `추가` / `소리 선택` / `시간 버튼 추가`가 `...` 또는 세로 잘림 없이 표시되는지 확인
   * 숫자패드 `다음` 및 하단 `5분~50분` 버튼이 한 줄로 표시되는지 확인
   * 스톱워치 1번/2번 버튼 및 `화면꺼짐 방지`가 잘리지 않는지 확인
   * 알람 편집 화면 `일~토` 7개 버튼이 모두 균등하게 보이는지 확인
   * 알람 목록 우측 하단 `도구` / `+` 버튼이 같은 수평선상에 정렬되고 그림자가 보이는지 확인
