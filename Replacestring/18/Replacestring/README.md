# Replacestring 안드로이드 앱 분석 문서

## 1. 개요

이 프로젝트는 **Jetpack Compose 기반의 단일 화면 안드로이드 앱**입니다.
앱 이름은 `메모장 바꾸기`이며, 사용자가 입력한 전체 텍스트에서 특정 단어를 다른 단어로 일괄 치환하고, 결과를 수정하거나 클립보드에 복사할 수 있도록 만들어져 있습니다.

이 프로젝트는 전통적인 `activity_main.xml` 같은 **XML 레이아웃 화면 구조가 아니라**, 대부분의 UI와 동작이 **Kotlin Compose 코드(`MainActivity.kt`) 안에 직접 구현**되어 있습니다.

---

## 2. 기술 스택

- **언어**: Kotlin
- **UI 방식**: Jetpack Compose + Material 3
- **빌드 시스템**: Gradle Kotlin DSL (`*.kts`)
- **최소 SDK**: 26
- **타깃 SDK**: 36
- **컴파일 SDK**: 36
- **Java Toolchain**: 11
- **패키지명 / 네임스페이스**: `com.krdondon.Replacestring`

핵심 의존성은 다음과 같습니다.

- `androidx.activity:activity-compose`
- `androidx.compose.ui:ui`
- `androidx.compose.material3:material3`
- `androidx.lifecycle:lifecycle-runtime-ktx`
- Compose BOM 사용

---

## 3. 프로젝트 구조

아래는 실제 기능 분석에 필요한 핵심 구조만 정리한 것입니다.

```text
Replacestring/
├─ app/
│  ├─ build.gradle.kts
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml
│     │  ├─ java/com/krdondon/Replacestring/
│     │  │  ├─ MainActivity.kt
│     │  │  └─ ui/theme/
│     │  │     ├─ Color.kt
│     │  │     ├─ Theme.kt
│     │  │     └─ Type.kt
│     │  └─ res/
│     │     ├─ values/
│     │     │  ├─ strings.xml
│     │     │  ├─ colors.xml
│     │     │  └─ themes.xml
│     │     ├─ xml/
│     │     │  ├─ backup_rules.xml
│     │     │  └─ data_extraction_rules.xml
│     │     ├─ drawable/
│     │     └─ mipmap-*/
│     ├─ test/
│     └─ androidTest/
├─ gradle/
│  ├─ libs.versions.toml
│  └─ wrapper/
├─ build.gradle.kts
├─ settings.gradle.kts
└─ gradlew
```

---

## 4. 파일별 역할 정리

## 4.1 `app/src/main/java/com/krdondon/Replacestring/MainActivity.kt`

이 앱의 **핵심 기능이 모두 들어 있는 메인 파일**입니다.

역할:

- 앱 시작 진입점 제공
- Compose 화면 생성
- 입력 상태 관리
- 문자열 치환 처리
- 결과 복사 처리
- 입력 초기화 처리

구성:

- `MainActivity`: 안드로이드 액티비티 엔트리 포인트
- `WordReplacerApp()`: 실제 화면과 기능을 모두 담당하는 Composable 함수

### 내부 상태값

`WordReplacerApp()` 내부에서 다음 4개의 상태를 `remember { mutableStateOf(...) }`로 관리합니다.

- `targetWord`: 바꿀 대상 단어
- `replacement`: 새로 치환할 단어
- `originalText`: 원본 전체 텍스트
- `replacedText`: 치환 결과 텍스트

즉, 이 앱은 **ViewModel 없이 화면 내부 상태만으로 동작하는 간단한 구조**입니다.

---

## 4.2 `app/src/main/java/com/krdondon/Replacestring/ui/theme/Color.kt`

Material 테마 색상 상수를 정의합니다.

포함 내용:

- `Purple80`
- `PurpleGrey80`
- `Pink80`
- `Purple40`
- `PurpleGrey40`
- `Pink40`

실제 앱 로직과 직접적인 관련은 없고, Compose 테마 리소스 역할입니다.

---

## 4.3 `app/src/main/java/com/krdondon/Replacestring/ui/theme/Theme.kt`

Compose용 테마 설정 파일입니다.

역할:

- 라이트/다크 테마 분기
- Android 12 이상에서 Dynamic Color 지원
- `MaterialTheme(...)` 구성

주의할 점:

- 이 파일에는 `ReplacestringTheme(...)`가 정의되어 있지만,
- 실제 `MainActivity`에서는 `MaterialTheme { WordReplacerApp() }`를 직접 호출하고 있어,
- **현재 프로젝트에서는 커스텀 테마 함수가 사실상 사용되지 않습니다.**

즉, 테마 파일은 존재하지만 실제 화면 적용은 최소 수준입니다.

---

## 4.4 `app/src/main/java/com/krdondon/Replacestring/ui/theme/Type.kt`

Material Typography를 정의하는 파일입니다.

역할:

- `Typography` 객체 제공
- 기본 본문 텍스트 스타일 지정

이 역시 테마 관련 보조 파일이며, 핵심 비즈니스 로직은 아닙니다.

---

## 4.5 `app/src/main/AndroidManifest.xml`

앱의 기본 설정 파일입니다.

핵심 내용:

- 애플리케이션 아이콘 지정
- 앱 이름(`@string/app_name`) 지정
- 기본 테마 지정
- 런처 액티비티를 `MainActivity`로 등록
- 백업/데이터 추출 규칙 XML 연결

중요 선언:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

위 설정 때문에 앱 실행 시 `MainActivity`가 첫 화면으로 뜹니다.

---

## 4.6 `app/src/main/res/values/strings.xml`

문자열 리소스 파일입니다.

현재 정의:

- `app_name = "메모장 바꾸기"`

즉, 런처에 표시되는 앱 이름은 `메모장 바꾸기`입니다.

---

## 4.7 `app/src/main/res/values/themes.xml`

안드로이드 XML 테마를 정의합니다.

현재 설정:

- `Theme.Replacestring`
- 부모 테마: `android:Theme.Material.Light.NoActionBar`

Compose 앱이지만, 액티비티 기본 테마는 XML에서도 한 번 설정합니다.

---

## 4.8 `app/src/main/res/values/colors.xml`

기본 색상 리소스 XML입니다.

포함된 값은 보통 템플릿 프로젝트에서 생성되는 값들이며,
현재 핵심 UI 동작과 직접 연결되지는 않습니다.

---

## 4.9 `app/src/main/res/xml/backup_rules.xml`
## 4.10 `app/src/main/res/xml/data_extraction_rules.xml`

백업 및 데이터 추출 관련 설정 파일입니다.

현재는 사실상 템플릿 기본 상태이며,
앱의 주요 문자열 치환 기능과 직접 관련은 없습니다.

---

## 4.11 아이콘 관련 리소스 (`mipmap-*`, `drawable/*`)

역할:

- 앱 런처 아이콘 제공
- Android 버전별 적응형 아이콘 구성

기능 로직과는 무관합니다.

---

## 4.12 테스트 코드

- `app/src/test/.../ExampleUnitTest.kt`
- `app/src/androidTest/.../ExampleInstrumentedTest.kt`

기본 템플릿 테스트 파일이며,
현재 문자열 치환 기능에 대한 실제 의미 있는 테스트는 작성되어 있지 않습니다.

---

## 5. UI 구조 설명

이 앱은 **화면 1개짜리 단일 기능 앱**입니다.

화면 구성은 아래 순서로 되어 있습니다.

1. 대체할 단어 입력칸
2. 대체될 단어 입력칸
3. 전체 원본 텍스트 입력칸
4. 우측 하단 `Clean` 버튼
5. `단어 대체` 버튼
6. `결과 복사` 버튼
7. 결과 표시/수정 입력칸

### 화면 레이아웃 특징

- 최상위는 `Column`
- `systemBarsPadding()` 사용: 상태바/내비게이션 바와 겹치지 않도록 처리
- `imePadding()` 사용: 키보드가 올라올 때 가려짐 방지
- 일부 영역은 `weight(1f)`를 사용해 남은 세로 공간을 차지
- 버튼은 `Row`에서 가로로 배치

즉, **작은 유틸리티 앱에 적합한 단일 컬럼 폼 구조**입니다.

---

## 6. 실제 동작 방식

## 6.1 앱 시작

앱 실행 시 `MainActivity.onCreate()`가 호출됩니다.

```kotlin
setContent {
    MaterialTheme {
        WordReplacerApp()
    }
}
```

여기서 Compose 화면이 렌더링됩니다.

---

## 6.2 문자열 입력

사용자는 다음 값을 각각 입력합니다.

- 바꿀 단어 (`targetWord`)
- 새 단어 (`replacement`)
- 전체 텍스트 (`originalText`)

입력창은 모두 `OutlinedTextField`로 구현되어 있습니다.

---

## 6.3 단어 치환

`단어 대체` 버튼을 누르면 아래 코드가 실행됩니다.

```kotlin
replacedText = originalText.replace(targetWord, replacement)
```

이 한 줄이 앱의 핵심 로직입니다.

### 의미

- `originalText` 안에 있는 `targetWord`를
- `replacement`로 전부 치환해서
- 결과를 `replacedText`에 저장

### 동작 특성

- **전체 치환**입니다. 첫 번째만 바꾸는 것이 아니라 매칭되는 모든 문자열을 바꿉니다.
- **대소문자 구분**이 있습니다. Kotlin의 기본 `replace(oldValue, newValue)`를 사용하므로 기본적으로 case-sensitive입니다.
- **정규식 치환이 아닙니다.** 단순 문자열 치환입니다.

예시:

- 원문: `apple banana apple`
- 대상 단어: `apple`
- 대체 단어: `orange`
- 결과: `orange banana orange`

---

## 6.4 결과 복사

`결과 복사` 버튼을 누르면 클립보드에 결과를 넣습니다.

동작 흐름:

1. `replacedText`가 비어 있지 않은지 확인
2. `ClipboardManager` 획득
3. `ClipData.newPlainText(...)` 생성
4. 클립보드에 저장
5. Toast 메시지 표시

핵심 코드:

```kotlin
val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
val clipData = ClipData.newPlainText("Replaced Text", replacedText)
clipboardManager.setPrimaryClip(clipData)
```

버튼은 아래 조건으로 활성화됩니다.

```kotlin
enabled = replacedText.isNotEmpty()
```

즉, 결과가 없으면 복사 버튼이 비활성화됩니다.

---

## 6.5 초기화(Clean)

원본 입력 영역 안쪽 우측 하단의 `Clean` 버튼을 누르면 아래 4개 상태가 모두 초기화됩니다.

- `targetWord = ""`
- `replacement = ""`
- `originalText = ""`
- `replacedText = ""`

그리고 Toast 메시지로 사용자에게 초기화 사실을 알려줍니다.

---

## 6.6 결과 수정 가능

결과 영역은 단순 표시만 하는 것이 아니라 다음처럼 작성되어 있습니다.

```kotlin
OutlinedTextField(
    value = replacedText,
    onValueChange = { replacedText = it }
)
```

즉, 사용자가 치환 결과를 **직접 추가 수정**할 수 있습니다.

이 점은 단순 출력 전용 Text가 아니라, **후편집 가능한 결과 편집기**라는 의미입니다.

---

## 7. XML 구조 설명

이 프로젝트는 XML을 많이 쓰지 않습니다.
핵심 화면은 Compose로 작성되어 있으므로, XML은 보조 설정용입니다.

### 실제 XML 사용처

#### 1) `AndroidManifest.xml`
- 앱 등록
- 액티비티 등록
- 테마/아이콘/백업 규칙 연결

#### 2) `values/strings.xml`
- 앱 이름 문자열 저장

#### 3) `values/themes.xml`
- 기본 안드로이드 테마 지정

#### 4) `values/colors.xml`
- 색상 리소스 정의

#### 5) `xml/backup_rules.xml`, `xml/data_extraction_rules.xml`
- 백업 관련 정책

#### 6) `drawable/`, `mipmap-*`
- 아이콘 및 적응형 아이콘 리소스

즉, **XML은 화면 레이아웃을 만들기 위해 쓰인 것이 아니라, 앱 설정/리소스 관리 용도**로 사용되었습니다.

---

## 8. 코드 흐름도

아래 흐름으로 동작합니다.

```text
앱 실행
  ↓
MainActivity 시작
  ↓
Compose 화면 WordReplacerApp 표시
  ↓
사용자 입력
  ├─ 대체할 단어 입력
  ├─ 대체될 단어 입력
  └─ 원본 텍스트 입력
  ↓
[단어 대체] 클릭
  ↓
originalText.replace(targetWord, replacement)
  ↓
replacedText 갱신
  ↓
결과 창에 즉시 반영
  ↓
[결과 복사] 클릭 시 클립보드 저장
```

`remember + mutableStateOf`를 사용하고 있으므로,
값이 바뀌면 Compose가 자동으로 UI를 다시 그립니다.

---

## 9. 사용 방법 설명서

## 9.1 앱 실행

1. 앱을 실행합니다.
2. 첫 화면에서 3개의 입력 영역을 확인합니다.

## 9.2 단어 치환 방법

1. `대체할 단어` 칸에 바꾸고 싶은 문자열을 입력합니다.
   - 예: `홍길동`
2. `대체될 단어` 칸에 새 문자열을 입력합니다.
   - 예: `김철수`
3. 큰 텍스트 입력칸에 전체 문장을 붙여넣습니다.
4. `단어 대체` 버튼을 누릅니다.
5. 하단 `결과` 영역에 치환된 텍스트가 표시됩니다.

## 9.3 결과 수정 방법

- 결과 창은 읽기 전용이 아니라 편집 가능합니다.
- 자동 치환 후 사용자가 수동으로 일부 문장을 추가 수정할 수 있습니다.

## 9.4 결과 복사 방법

1. 결과가 생성된 상태에서 `결과 복사` 버튼을 누릅니다.
2. 결과 텍스트가 안드로이드 클립보드에 저장됩니다.
3. 다른 앱(메모장, 메신저, 문서 앱 등)에 붙여넣을 수 있습니다.

## 9.5 입력값 전체 삭제 방법

1. 원본 텍스트 입력칸 오른쪽 아래의 `Clean` 버튼을 누릅니다.
2. 검색어, 치환어, 원문, 결과가 모두 초기화됩니다.

---

## 10. 빌드 관련 파일 설명

## 10.1 `app/build.gradle.kts`

앱 모듈 설정 파일입니다.

핵심 설정:

- 앱 모듈 플러그인 적용
- Compose 활성화 (`buildFeatures { compose = true }`)
- SDK 버전 설정
- 버전 코드/버전명 설정
- Java 11 설정
- Compose Compiler 버전 지정
- 의존성 선언

중요 값:

- `versionCode = 9`
- `versionName = "9.0"`

---

## 10.2 `gradle/libs.versions.toml`

라이브러리 버전 카탈로그 파일입니다.

의미:

- AGP, Kotlin, Compose BOM, AndroidX 버전을 한 곳에서 관리
- `build.gradle.kts`에서는 별칭(alias)으로 참조

이 구조는 최근 Gradle 프로젝트에서 많이 쓰는 방식입니다.

---

## 10.3 `settings.gradle.kts`

프로젝트 전체 설정 파일입니다.

역할:

- 플러그인 저장소 지정
- 의존성 저장소 지정
- 루트 프로젝트 이름 지정
- `:app` 모듈 포함

---

## 11. 아키텍처 관점 분석

이 프로젝트는 다음 특성을 가집니다.

### 장점

- 구조가 단순해서 이해하기 쉽습니다.
- 작은 유틸리티 앱으로는 빠르게 개발 가능합니다.
- Compose 상태 기반 UI라 입력값 변경 시 반응형으로 즉시 갱신됩니다.
- 별도 권한 없이 클립보드 복사 기능을 구현했습니다.

### 한계

- 모든 기능이 `MainActivity.kt` 하나에 몰려 있습니다.
- ViewModel, 상태 홀더, 유스케이스 분리가 없습니다.
- 테스트 코드가 사실상 비어 있습니다.
- 치환 옵션이 단순합니다.
  - 대소문자 무시 옵션 없음
  - 단어 경계 기준 치환 없음
  - 정규식 치환 없음
  - 미리보기/변경 이력 없음

즉, 현재 구조는 **소형 단일 기능 앱에는 적합하지만, 기능 확장성은 낮은 편**입니다.

---

## 12. 주의할 점 및 동작상 특이사항

### 12.1 빈 문자열 치환 가능성

코드상 `targetWord`가 빈 문자열인지 검사하지 않고 바로 `replace()`를 호출합니다.

```kotlin
replacedText = originalText.replace(targetWord, replacement)
```

이 경우 Kotlin 문자열 치환 로직 특성상, 빈 문자열을 대상으로 넣으면 사용자가 기대하지 않은 결과가 나올 수 있습니다.
따라서 실제 운영 앱이라면 아래 검증이 필요합니다.

- 대상 단어가 비어 있으면 치환 실행 금지
- Toast 또는 에러 메시지 표시

---

### 12.2 대소문자 구분

현재는 `Apple`과 `apple`을 서로 다른 문자열로 취급합니다.
즉, 영문 텍스트 처리 시 사용자가 예상과 다르게 느낄 수 있습니다.

---

### 12.3 멀티라인 입력 처리

원본/결과 영역에 `OutlinedTextField`를 사용해 긴 텍스트를 입력할 수 있게 만들었지만,
스크롤과 `TextField`의 조합 방식은 Compose 버전에 따라 UX가 다소 어색할 수 있습니다.
특히 아주 긴 텍스트에서는 별도 스크롤 처리 설계가 더 정교할 필요가 있습니다.

---

### 12.4 커스텀 테마 미사용

`ui/theme/Theme.kt`가 존재하지만 실제 `MainActivity`에서는 `ReplacestringTheme()`를 호출하지 않고 기본 `MaterialTheme`를 직접 사용합니다.
따라서 테마 파일의 의미가 일부 반감됩니다.

---

## 13. 개선 제안

분석 기준으로 볼 때 다음과 같이 개선할 수 있습니다.

### 구조 개선

- `MainActivity.kt`에서 UI와 로직 분리
- `ViewModel` 도입
- 문자열 치환 로직을 별도 함수 또는 유스케이스 클래스로 분리
- 재사용 가능한 Composable로 입력 영역 분리

### 기능 개선

- 빈 문자열 입력 검증
- 대소문자 무시 옵션 추가
- 정규식 치환 옵션 추가
- 한 번에 모두 지우기 전에 확인 다이얼로그 추가
- 치환 결과 미리보기 통계 추가
  - 변경 건수
  - 원문 길이 / 결과 길이
- 파일 열기/저장 기능 추가

### 품질 개선

- 단위 테스트 작성
- UI 테스트 작성
- 다국어 문자열 리소스 분리
- 접근성 향상(콘텐츠 설명, 버튼 명확화)

---

## 14. 요약

이 앱은 다음과 같이 정리할 수 있습니다.

- **목적**: 긴 텍스트에서 특정 문자열을 다른 문자열로 쉽게 치환하는 유틸리티 앱
- **구조**: 단일 액티비티 + 단일 Compose 화면
- **핵심 로직**: `originalText.replace(targetWord, replacement)`
- **UI 구성 방식**: XML 레이아웃이 아닌 Jetpack Compose 중심
- **XML 역할**: 화면이 아니라 앱 설정/문자열/테마/백업/아이콘 리소스 관리
- **부가 기능**: 결과 수정 가능, 클립보드 복사 가능, 전체 초기화 가능

현재 상태의 프로젝트는 **작고 단순한 Compose 실습/유틸리티 앱**으로 보이며,
구조가 간단해 유지보수 진입 장벽은 낮지만, 기능이 늘어나면 파일 분리와 상태 관리 체계 도입이 필요합니다.

---

## 15. 한 줄 결론

이 프로젝트는 **"입력한 전체 문자열에서 특정 단어를 다른 단어로 바꾸고, 결과를 수정·복사할 수 있는 단일 화면 Compose 안드로이드 앱"**입니다.
