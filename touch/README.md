# Touch 안드로이드 앱 분석 README

## 1. 개요
이 프로젝트는 **Jetpack Compose 기반의 안드로이드 터치 게임 앱**입니다. 앱 이름은 `터치`이며, 화면에 랜덤하게 나타나는 원형 터치 타겟(물방울처럼 보이는 이미지)을 사용자가 눌러 점수를 올리는 구조입니다.

이 앱의 핵심 특징은 다음과 같습니다.

- **전화기와 태블릿을 구분하여 화면 방향을 다르게 동작**시킴
- **Jetpack Compose로 UI를 구성**하므로 전통적인 `layout.xml` 화면 파일이 없음
- **시간 경과에 따른 난이도 증가 기능** 제공
- **동시 등장 타겟 수 증가 기능** 제공
- **배경음악(BGM)과 효과음** 사용
- **멀티터치 입력 처리** 지원
- **터치 성공 시 팝 애니메이션** 재생

---

## 2. 기술 스택

- **언어**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **최소 SDK**: 26
- **타겟 SDK / 컴파일 SDK**: 36
- **빌드 시스템**: Gradle Kotlin DSL (`build.gradle.kts`)
- **오디오**:
  - `MediaPlayer` : 배경음악 재생
  - `SoundPool` : 짧은 효과음 재생

---

## 3. 프로젝트 구조

```text
touch/
├─ app/
│  ├─ build.gradle.kts
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml
│     │  ├─ java/com/krdonon/touch/
│     │  │  ├─ MainActivity.kt
│     │  │  ├─ TabletLandscapeScreen.kt
│     │  │  └─ ui/theme/
│     │  │     ├─ Color.kt
│     │  │     ├─ Theme.kt
│     │  │     └─ Type.kt
│     │  └─ res/
│     │     ├─ drawable/
│     │     │  ├─ touch.png
│     │     │  ├─ touchimage1.png
│     │     │  ├─ touchimage2.png
│     │     │  ├─ touchimage3.png
│     │     │  ├─ touchimage4.png
│     │     │  └─ touchimage5.png
│     │     ├─ raw/
│     │     │  ├─ sound.mp3
│     │     │  └─ sstouch.mp3
│     │     ├─ values/
│     │     │  ├─ strings.xml
│     │     │  ├─ colors.xml
│     │     │  └─ themes.xml
│     │     └─ xml/
│     │        ├─ backup_rules.xml
│     │        └─ data_extraction_rules.xml
│     ├─ test/
│     └─ androidTest/
├─ gradle/
│  └─ libs.versions.toml
├─ build.gradle.kts
└─ settings.gradle.kts
```

---

## 4. Kotlin 파일 구조 설명

### 4.1 `MainActivity.kt`
이 프로젝트의 **핵심 파일**입니다. 앱 진입점, 게임 상태 관리, UI 구성, 오디오 재생, 타겟 생성, 터치 처리, 난이도 제어가 대부분 여기 들어 있습니다.

#### 포함된 주요 요소

- `MainActivity`
  - 앱 시작 시 화면 방향 결정
  - 태블릿이면 가로 고정, 폰이면 세로 고정
  - Compose 루트 화면 설정

- `LayoutMode`
  - `PHONE_PORTRAIT`
  - `TABLET_LANDSCAPE`
  - 화면 좌표계/배치 구분용 enum

- `TouchTarget`
  - 현재 화면에 떠 있는 타겟 1개를 표현하는 데이터 클래스
  - 속성: `id`, `x`, `y`

- `PopEffect`
  - 터치 성공 후 재생되는 터짐 효과 위치 정보
  - 속성: `id`, `x`, `y`

- 유틸 함수
  - `formatElapsedTime(ms)` : 경과 시간을 `HH:MM:SS.mmm` 형태로 변환
  - `stageFromElapsedMs(elapsedMs)` : 40초마다 스테이지 1 상승
  - `spawnDelayRangeMs(stage)` : 스테이지에 따라 다음 타겟 등장 지연시간 계산
  - `targetLifetimeMs(stage)` : 타겟이 화면에 유지되는 시간 계산
  - `extraLifetimeMsForTargetCount(targetCount)` : 동시에 많이 뜰수록 생존시간 소폭 증가
  - `targetCountFromStage(stage)` : 자동 증가 모드에서 스테이지에 따라 동시 등장 개수 계산
  - `generateTargets(count, minDistance)` : 서로 너무 겹치지 않게 랜덤 위치 생성

- `TouchGameScreen()`
  - 실제 게임 전체 UI와 상태 로직을 담당하는 Compose 함수
  - 사실상 앱 본체

- `InfoBox()`
  - 상단 상태 박스 UI (`버튼 누른 횟수`, `실패 횟수`) 출력용

- `PopBurst()`
  - 터치 성공 시 프레임 이미지 + 알파/스케일/리플 애니메이션 처리

---

### 4.2 `TabletLandscapeScreen.kt`
태블릿 가로 모드 전용 래퍼입니다.

#### 역할
- 기존 세로 UI를 **-90도 회전**해서 보여줌
- 태블릿에서는 레이아웃을 따로 다시 만들지 않고, 기존 게임 화면을 재사용
- `TouchGameScreen(layoutMode = LayoutMode.TABLET_LANDSCAPE)` 호출

#### 핵심 포인트
- `Rotated90CounterClockwise()` 커스텀 `Layout` 사용
- 자식의 가로/세로 제약을 뒤집어서 측정
- 배치 시 `rotationZ = -90f` 적용
- 주석상 `MotionEvent` 좌표 보정 이슈가 언급되지만, 실제 현재 구현은 `pointerInput`을 사용하므로 Compose 로컬 좌표를 직접 받아 처리함

---

### 4.3 `ui/theme/Color.kt`
Compose 테마 색상 정의 파일입니다.

#### 역할
- 기본 Material 색상 팔레트 정의
- `Purple80`, `PurpleGrey80`, `Pink80`, `Purple40` 등 선언

실제 게임 화면은 이 테마 색상보다 코드 내부에서 직접 지정한 색상(`Color(0xFF...)`)을 더 많이 사용합니다.

---

### 4.4 `ui/theme/Theme.kt`
Compose 전역 테마 적용 파일입니다.

#### 역할
- 다크/라이트 테마 스킴 설정
- Android 12 이상에서는 dynamic color 사용 가능
- `TouchTheme { ... }` 형태로 앱 전체를 감쌈

---

### 4.5 `ui/theme/Type.kt`
Typography 설정 파일입니다.

#### 역할
- 기본 글꼴 크기와 스타일 정의
- 현재는 기본 `bodyLarge` 위주로만 설정되어 있고 커스텀 타이포그래피 사용은 크지 않음

---

## 5. XML 파일 구조 설명

이 프로젝트는 **Compose 기반 UI**라서 전통적인 `activity_main.xml`, `fragment_xxx.xml` 같은 화면 XML이 없습니다.
즉, **화면 구조는 XML이 아니라 Kotlin Composable 함수로 작성**되어 있습니다.

XML은 설정/리소스 성격의 파일만 사용합니다.

### 5.1 `AndroidManifest.xml`
앱의 메타 정보와 진입 액티비티가 정의되어 있습니다.

#### 주요 내용
- 패키지: `com.krdonon.touch`
- 시작 액티비티: `MainActivity`
- 런처 앱으로 등록됨
- 앱 테마: `Theme.Touch`
- `configChanges="orientation|screenSize"` 설정 포함

#### 의미
- 방향 전환/화면 크기 변경 시 Activity 재생성 대신 일부 변경을 직접 처리하려는 의도
- 실제 방향 제어는 `MainActivity`에서 기기 유형에 따라 수행

---

### 5.2 `res/values/strings.xml`
문자열 리소스 파일입니다.

#### 현재 내용
- `app_name = 터치`

문자열 리소스 분리는 최소 수준이며, 실제 버튼 문구나 안내 문구 상당수는 Kotlin 코드 안에 직접 문자열로 작성되어 있습니다.

---

### 5.3 `res/values/colors.xml`
기본 색상 리소스 파일입니다.

#### 현재 역할
- 기본 템플릿 수준의 색상 정의
- 실제 게임 UI는 Compose 코드에서 직접 색상을 많이 지정함

---

### 5.4 `res/values/themes.xml`
앱 테마 XML입니다.

#### 현재 내용
- `Theme.Touch`가 `android:Theme.Material.Light.NoActionBar`를 상속

#### 의미
- 액션바 없는 기본 라이트 테마 사용
- 실제 세부 색상은 Compose `MaterialTheme`와 코드 내 색상 지정이 담당

---

### 5.5 `res/xml/backup_rules.xml`
백업 규칙 파일입니다.

#### 역할
- Auto Backup 관련 설정 위치
- 현재는 샘플 수준이며 실질적 include/exclude 설정 없음

---

### 5.6 `res/xml/data_extraction_rules.xml`
Android 12 이상 데이터 추출/전송 규칙 파일입니다.

#### 역할
- 클라우드 백업/기기 전송 시 포함/제외 대상을 정하는 위치
- 현재는 기본 템플릿 상태

---

## 6. 리소스 파일 설명

### 6.1 `drawable/`

- `touch.png`
  - 게임에서 터치해야 하는 메인 타겟 이미지
- `touchimage1.png` ~ `touchimage5.png`
  - 터치 성공 후 재생되는 팝 효과 프레임 이미지
- `ic_launcher_*`
  - 앱 아이콘 관련 리소스

### 6.2 `raw/`

- `sstouch.mp3`
  - 배경음악(BGM)
- `sound.mp3`
  - 타겟 터치 성공 시 재생되는 짧은 효과음

---

## 7. 앱 동작 방식

## 7.1 실행 흐름
1. 앱이 시작되면 `MainActivity`가 실행됩니다.
2. 현재 기기의 `smallestScreenWidthDp`를 기준으로 태블릿 여부를 판별합니다.
3. 태블릿이면 가로 모드, 폰이면 세로 모드로 고정합니다.
4. Compose 루트 화면을 생성합니다.
5. 태블릿은 `TabletLandscapeScreen()`, 폰은 `TouchGameScreen()`을 표시합니다.

---

## 7.2 게임 상태 관리
`TouchGameScreen()` 내부에서 `remember`, `rememberSaveable`을 사용하여 상태를 관리합니다.

### 주요 상태값
- `elapsedMs` : 현재 게임 경과 시간
- `accumulatedMs` : 일시정지/백그라운드 포함 누적 시간 보정값
- `touchCount` : 성공 터치 횟수
- `failCount` : 타겟 놓친 횟수
- `isGamePaused` : 일시정지 여부
- `touchTargets` : 현재 화면에 표시 중인 타겟 목록
- `popEffects` : 현재 재생 중인 터짐 효과 목록
- `isBgmEnabled` : BGM 켜기/끄기
- `isAutoDifficultyEnabled` : 시간 경과에 따른 자동 난이도 증가
- `isManualStageEnabled` / `manualStage` : 수동 스테이지 지정
- `isAutoIncreaseEnabled` : 동시 등장 개수 자동 증가
- `isManualIncreaseEnabled` / `manualTargetCount` : 동시 등장 개수 수동 고정

---

## 7.3 난이도 계산 방식

### 기본 상태
- 자동 난이도 OFF
- 수동 단계 OFF
- 기본 단계는 사실상 `1`

### 자동 난이도 ON일 때
- `40초마다 1단계 증가`
- 최대 `140단계`

```kotlin
stage = (elapsedMs / 40000) + 1
```

### 수동 단계 ON일 때
- 자동 난이도보다 수동 단계가 우선
- 사용자가 `0 ~ 140` 사이 값을 직접 지정

주의할 점은 UI에서는 수동 단계에 `0`도 넣을 수 있지만, 실제 로직상 생성 지연/생존시간 계산 함수는 내부에서 보정해 사용합니다.

---

## 7.4 타겟 등장 로직
앱은 `LaunchedEffect` 루프를 통해 타겟을 계속 생성합니다.

### 생성 규칙
- 화면에 이미 타겟이 있으면 새 타겟을 만들지 않음
- 모두 사라졌을 때만 다음 타겟 생성
- 생성 전 일정 시간 대기
- 대기 시간은 스테이지가 올라갈수록 짧아짐

### 등장 수량 결정
우선순위는 다음과 같습니다.

1. 수동 증가 ON → `manualTargetCount` 사용 (2~4)
2. 자동 증가 ON → `targetCountFromStage(stage)` 사용
3. 둘 다 OFF → 1개

### 자동 증가 규칙
- 1~6단계: 1개
- 7~13단계: 2개
- 14~20단계: 3개
- 21단계 이상: 4개

### 위치 생성 방식
- `generateTargets()`가 랜덤 정규화 좌표(0~1)를 생성
- 서로 너무 겹치지 않게 최소 거리 제약 적용
- 최대 4개까지 생성

---

## 7.5 타겟 실패 처리
타겟이 생성되면 각 타겟마다 코루틴이 하나씩 실행됩니다.

### 흐름
- 일정 생존시간(`lifetimeForThisSpawn`) 대기
- 그 시간 안에 사용자가 누르지 않으면
  - 해당 타겟 제거
  - `failCount++`

즉, **놓친 타겟은 자동 소멸되며 실패 횟수에 반영**됩니다.

---

## 7.6 터치 판정 방식
터치 입력은 각 이미지에 개별 클릭 리스너를 다는 방식이 아니라, **게임 영역 전체에서 통합 입력 처리**합니다.

### 처리 방식
- `pointerInput` + `awaitPointerEventScope` 사용
- 모든 새 터치 다운 이벤트 감지
- 멀티터치 대응 가능
- 터치 좌표가 특정 타겟의 히트박스 내부인지 검사

### 히트박스 특징
- 실제 이미지 크기: `80.dp`
- 실제 판정 박스: `130.dp`

즉, 보이는 이미지보다 더 큰 영역을 터치 성공으로 처리해 민감도를 높였습니다.

### 성공 시 동작
- `touchCount++`
- 해당 타겟 제거
- `popEffects`에 효과 추가
- `SoundPool` 효과음 재생

---

## 7.7 애니메이션 효과
`PopBurst()`가 터치 성공 애니메이션을 담당합니다.

### 포함 요소
- 프레임 이미지 순차 재생 (`touchimage1~5`)
- 스케일 확대/축소
- 알파 감소
- 원형 리플 확산

애니메이션 종료 후 해당 효과는 목록에서 제거됩니다.

---

## 7.8 오디오 처리

### BGM
- `MediaPlayer` 사용
- 앱 시작 시 `sstouch.mp3` 로드 후 반복 재생
- 스위치로 ON/OFF 가능

### 효과음
- `SoundPool` 사용
- 타겟 성공 터치 시 `sound.mp3` 재생

### 생명주기 처리
- `ON_PAUSE` 시 BGM 일시정지
- `ON_RESUME` 시 BGM 재개
- 화면 종료 시 리소스 release

---

## 7.9 앱 생명주기 대응
`LifecycleEventObserver`를 사용해 백그라운드 전환/복귀를 처리합니다.

### ON_PAUSE
- BGM 정지
- 현재 경과 시간 고정
- 자동 일시정지 처리

### ON_STOP
- 누적 시간 저장

### ON_RESUME
- BGM 복원
- 자동 일시정지였던 경우 게임 재개

이 구조 덕분에 앱이 백그라운드에 갔다 와도 시간이 갑자기 초기화되거나 게임 상태가 크게 깨질 가능성을 줄였습니다.

---

## 8. 실제 화면 구성

UI는 크게 아래 순서로 구성됩니다.

1. **상단 정보 바**
   - 게임 진행 시간
   - BGM 스위치

2. **상태 박스 영역**
   - 버튼 누른 횟수
   - 실패 횟수

3. **게임 영역**
   - 초록색 배경 박스
   - 안내 문구 출력
   - 랜덤 타겟 표시
   - 터치 이펙트 표시
   - 우측 하단 현재 단계 표시

4. **하단 버튼 1줄**
   - 일시 정지 / 재생
   - 초기화

5. **하단 버튼 2줄**
   - 점점 어렵게
   - 단계 수동
   - 점점 증가
   - 수동 증가

---

## 9. 사용법 설명서

## 9.1 기본 사용법
1. 앱을 실행합니다.
2. 게임 영역에 나타나는 원형 타겟을 누릅니다.
3. 누르면 성공 횟수가 올라가고 효과음/애니메이션이 재생됩니다.
4. 놓치면 실패 횟수가 올라갑니다.

---

## 9.2 상단 기능

### 게임 진행 시간
- 현재 플레이 시간을 표시합니다.
- `시:분:초.밀리초` 형식으로 표시됩니다.

### BGM 스위치
- ON: 배경음악 재생
- OFF: 배경음악 정지

---

## 9.3 하단 버튼 기능

### `일시 정지` / `재생`
- 현재 게임 루프를 멈추거나 다시 시작합니다.
- 일시정지 중에는 새 타겟 생성과 시간 흐름이 멈춥니다.

### `초기화`
다음 내용을 초기 상태로 되돌립니다.

- 성공 횟수 초기화
- 실패 횟수 초기화
- 타겟 제거
- 시간 초기화
- 자동 난이도 OFF
- 수동 단계 OFF
- 자동 증가 OFF
- 수동 증가 OFF

---

## 9.4 난이도 관련 기능

### `점점 어렵게`
- 켜면 시간이 지날수록 스테이지가 자동 증가합니다.
- 스테이지가 높아질수록 타겟 등장 간격과 유지 시간이 짧아집니다.

### `단계 수동`
- 버튼을 누르면 다이얼로그가 열립니다.
- `0 ~ 140` 범위의 단계를 선택할 수 있습니다.
- `수동 단계 적용` 체크 시 해당 단계가 고정 적용됩니다.

#### 우선순위
- 수동 단계가 활성화되면 자동 난이도보다 우선합니다.

---

## 9.5 동시 등장 개수 관련 기능

### `점점 증가`
- 스테이지에 따라 동시에 나타나는 타겟 개수가 증가합니다.

### `수동 증가`
- 다이얼로그에서 동시에 등장할 개수를 `2 ~ 4`개로 설정합니다.
- `수동 증가 적용` 체크 시 지정한 개수가 고정됩니다.

#### 우선순위
- 수동 증가가 활성화되면 자동 증가보다 우선합니다.

---

## 10. 구조적 특징 요약

이 앱은 전통적인 안드로이드 아키텍처(MVVM, Repository, UseCase 등)를 크게 나누지 않고, **단일 화면 중심의 Compose 게임 앱** 구조를 취합니다.

### 현재 구조 특징
- **장점**
  - 작은 앱에서는 빠르게 개발 가능
  - 상태 흐름을 한 파일에서 보기 쉬움
  - Compose 특성상 UI와 상태 연결이 직관적

- **한계**
  - `MainActivity.kt`에 로직이 많이 몰려 있음
  - 기능이 더 늘어나면 유지보수가 어려워질 수 있음
  - ViewModel, 상태 분리, 사운드 관리 분리 등이 아직 없음

---

## 11. 코드 관점에서의 동작 우선순위 정리

### 단계 결정 우선순위
1. 수동 단계 사용 여부
2. 자동 난이도 사용 여부
3. 기본값 1단계

### 동시 등장 수 우선순위
1. 수동 증가 사용 여부
2. 자동 증가 사용 여부
3. 기본값 1개

### 타겟 생명주기
1. 대기
2. 생성
3. 사용자가 누르면 성공 처리
4. 제한시간 초과 시 실패 처리
5. 모든 타겟이 사라지면 다음 생성 사이클 시작

---

## 12. XML 관점 결론

이 프로젝트를 분석할 때 중요한 점은 다음입니다.

- **화면 XML 구조는 사실상 없음**
- XML은 설정 파일 중심
- 실제 앱 구조와 동작은 거의 전부 Kotlin Compose 코드에서 처리

즉, 이 앱은 **"XML 기반 안드로이드 앱"이 아니라 "Compose 기반 게임 앱"**으로 보는 것이 정확합니다.

---

## 13. 개선 포인트 제안

분석 기준에서 보았을 때 향후 개선하면 좋은 부분은 다음과 같습니다.

1. `MainActivity.kt`를 파일별로 분리
   - 게임 상태 관리
   - 타겟 생성 로직
   - 오디오 관리
   - UI 컴포넌트

2. `ViewModel` 도입
   - 상태 저장과 화면 분리
   - 회전/재생성 대응 명확화

3. 문자열 리소스 분리 강화
   - 현재 한국어 문자열이 코드에 직접 하드코딩되어 있음

4. 테스트 강화
   - 현재 테스트 파일은 기본 샘플 수준

5. 설정 저장 추가
   - BGM 상태, 수동 단계, 수동 증가 값을 `DataStore` 등에 저장 가능

---

## 14. 한 줄 결론
이 프로젝트는 **Jetpack Compose로 작성된 단일 화면형 터치 반응 게임 앱**이며, `MainActivity.kt`가 게임 로직과 UI를 대부분 담당하고, XML은 화면이 아니라 앱 설정/리소스 정의 용도로만 사용됩니다.
