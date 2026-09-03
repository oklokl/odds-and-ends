# Ratio Android 앱 분석 README

## 1. 개요

이 프로젝트는 **Jetpack Compose 기반의 단일 액티비티 Android 앱**입니다. 앱의 목적은 **갤러리에서 이미지를 선택한 뒤, 원본 이미지를 자르지 않고 원하는 비율의 캔버스에 중앙 배치하여 새 이미지로 저장**하는 것입니다.

핵심 기능은 다음과 같습니다.

- 이미지 선택
- EXIF 방향값을 반영한 이미지 로드
- 90도 단위 회전
- 미리 정의된 화면 비율(1:1, 4:3, 16:9 등) 적용
- 배경색 선택(흰색/검은색/투명)
- JPG/PNG 저장 형식 선택
- 품질(1~100) 설정
- `MediaStore`를 통한 사진 저장

이 앱은 **이미지를 크롭하는 방식이 아니라, 캔버스를 확장하여 비율을 맞추는 방식**으로 동작합니다. 즉, 원본 이미지를 유지한 채 좌우 또는 상하에 배경 영역을 추가합니다.

---

## 2. 기술 스택

- **언어**: Kotlin
- **UI**: Jetpack Compose + Material3
- **이미지 처리**: `Bitmap`, `Canvas`, `Matrix`, `ExifInterface`
- **저장 방식**: `MediaStore.Images.Media`
- **최소 SDK**: 26
- **대상 SDK / 컴파일 SDK**: 36
- **JVM 타깃**: Java 17 / Kotlin JVM 17

사용 라이브러리:

- `androidx.core:core-ktx`
- `androidx.lifecycle:lifecycle-runtime-ktx`
- `androidx.activity:activity-compose`
- `androidx.compose.material3:material3`
- `io.coil-kt:coil-compose`
- `androidx.compose.material:material-icons-extended`

참고:

- `coil-compose`가 의존성에 포함되어 있지만, 현재 핵심 화면에서는 `Bitmap`을 직접 `Image`로 렌더링하므로 사실상 **실사용은 거의 없음**.
- `ui/theme` 패키지는 존재하지만 실제 `MainActivity`에서 `RatioTheme()`로 감싸지 않아 **현재는 적용되지 않는 상태**입니다.

---

## 3. 프로젝트 구조

실제 의미 있는 구조만 정리하면 아래와 같습니다.

```text
ratio/
├─ build.gradle.kts                  # 루트 Gradle 설정
├─ settings.gradle.kts               # 모듈 포함 설정
├─ gradle/libs.versions.toml         # 버전 카탈로그
├─ gradlew / gradlew.bat             # Gradle Wrapper
└─ app/
   ├─ build.gradle.kts               # 앱 모듈 설정
   └─ src/
      ├─ main/
      │  ├─ AndroidManifest.xml      # 앱 권한/액티비티 선언
      │  ├─ java/com/krdonon/ratio/
      │  │  ├─ MainActivity.kt       # 앱의 핵심 로직 대부분이 들어있는 파일
      │  │  └─ ui/theme/
      │  │     ├─ Color.kt           # Compose 색상 정의
      │  │     ├─ Theme.kt           # Compose 테마 정의
      │  │     └─ Type.kt            # Compose 타이포그래피 정의
      │  └─ res/
      │     ├─ values/
      │     │  ├─ strings.xml        # 앱 문자열
      │     │  ├─ colors.xml         # 기본 색상 리소스
      │     │  └─ themes.xml         # Android 테마 리소스
      │     ├─ xml/
      │     │  ├─ backup_rules.xml
      │     │  └─ data_extraction_rules.xml
      │     ├─ drawable/             # 런처 아이콘 관련 벡터
      │     └─ mipmap-*/             # 앱 아이콘 리소스
      ├─ test/
      │  └─ .../ExampleUnitTest.kt   # 기본 샘플 테스트
      └─ androidTest/
         └─ .../ExampleInstrumentedTest.kt # 기본 샘플 테스트
```

중요 포인트:

- 이 프로젝트는 **XML 레이아웃 기반이 아니라 Compose 기반**이라서 `activity_main.xml` 같은 화면 레이아웃 XML이 없습니다.
- 화면 로직은 거의 전부 `MainActivity.kt` 하나에 집중되어 있습니다.
- `res/xml`은 UI XML이 아니라 **백업/데이터 추출 정책용 XML**입니다.

---

## 4. Kotlin(.kt) 파일 구조와 역할

### 4.1 `MainActivity.kt`

이 파일이 앱의 중심입니다. 아래 역할을 모두 수행합니다.

#### 포함된 주요 구성요소

1. `MainActivity : ComponentActivity`
   - 앱 진입점
   - `onCreate()`에서 `setContent { RatioApp() }` 호출

2. 데이터 구조
   - `AspectRatioOption`
     - 비율 이름과 실제 비율값 보관
   - `ImageFormat`
     - 저장 포맷(`JPG`, `PNG`)
   - `BackgroundColor`
     - 배경색 enum (`WHITE`, `BLACK`, `TRANSPARENT`)

3. Compose UI
   - `RatioApp()`
     - 전체 화면 상태 및 UI 제어
   - `RatioButton()`
     - 비율 선택 버튼 UI
   - `QualityDialog()`
     - 저장 품질 슬라이더 다이얼로그

4. 이미지 처리 함수
   - `loadBitmapWithOrientation()`
   - `getOrientationFromExif()`
   - `resizeToAspectRatio()`
   - `saveImage()`
   - `getImprovedFileNameFromUri()`
   - `InputStream.copyTo()` 확장 함수

#### 상태(state) 관리 항목

`RatioApp()` 내부에서 `remember { mutableStateOf(...) }` 로 관리하는 값들:

- `selectedImageUri`: 선택한 이미지 URI
- `originalBitmap`: 원본 비트맵
- `editedBitmap`: 비율 적용 후 비트맵
- `selectedRatio`: 현재 선택된 비율
- `selectedFormat`: 저장 형식(JPG/PNG)
- `selectedBackgroundColor`: 배경색
- `quality`: 저장 품질
- `showQualityDialog`: 품질 다이얼로그 표시 여부
- `currentRotation`: 현재 회전 각도

즉, 이 앱은 별도의 ViewModel 없이 **Composable 내부 state 기반**으로 동작합니다.

---

### 4.2 `ui/theme/Color.kt`

Compose Material 테마 색상 정의 파일입니다.

- `Purple80`, `PurpleGrey80`, `Pink80`
- `Purple40`, `PurpleGrey40`, `Pink40`

기능 자체에는 직접 영향이 거의 없고, 일반적인 Compose 템플릿에서 생성된 테마 리소스에 가깝습니다.

---

### 4.3 `ui/theme/Theme.kt`

Compose 테마를 구성하는 파일입니다.

- `DarkColorScheme`
- `LightColorScheme`
- `RatioTheme()`

`dynamicColor`도 지원하도록 되어 있습니다.

하지만 현재 `MainActivity`에서는 아래처럼 사용합니다.

```kotlin
setContent {
    RatioApp()
}
```

즉, 아래처럼 감싸지 않기 때문에:

```kotlin
setContent {
    RatioTheme {
        RatioApp()
    }
}
```

현재 테마 정의 파일은 **실질적으로 미적용 상태**입니다.

---

### 4.4 `ui/theme/Type.kt`

Material Typography 설정 파일입니다.

- `Typography` 정의
- `bodyLarge` 기본 스타일 지정

이 역시 `RatioTheme()`가 실제 적용되지 않으므로 현재 화면에 적극적으로 반영되는 구조는 아닙니다.

---

### 4.5 테스트 파일

- `ExampleUnitTest.kt`
- `ExampleInstrumentedTest.kt`

Android Studio 기본 템플릿 수준의 예제 테스트이며, 실제 앱 기능을 검증하는 로직은 아닙니다.

---

## 5. XML 파일 구조와 역할

이 프로젝트는 Compose UI 앱이라 XML이 적습니다.

### 5.1 `AndroidManifest.xml`

앱 전역 설정 파일입니다.

#### 선언 내용

- 권한
  - `READ_MEDIA_IMAGES`
  - `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`)
  - `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`)
- 앱 아이콘/라벨/테마
- 런처 액티비티로 `MainActivity` 등록

#### 해석

- Android 13 이상에서는 `READ_MEDIA_IMAGES` 권한을 사용하도록 의도되어 있습니다.
- 하위 버전 호환을 위해 `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`도 일부 선언했습니다.
- 다만 현재 코드상 **실제 런타임 권한 요청 흐름은 완성도가 낮습니다.**
  - `permissionLauncher`는 선언되어 있지만 저장 시점에 실질적으로 호출되지 않습니다.
  - 이미지 선택은 `GetContent()`를 사용하므로 일반적으로 시스템 피커를 통해 접근합니다.
  - 저장은 `MediaStore`를 사용하므로 최신 Android에서는 별도 쓰기 권한 없이도 가능한 경우가 많습니다.

즉, Manifest 권한 선언은 있으나 실제 동작은 **시스템 문서 선택기 + MediaStore 저장** 중심입니다.

---

### 5.2 `res/values/strings.xml`

문자열 리소스입니다.

- `app_name = "이미지 비율"`

앱 이름만 정의되어 있습니다.

---

### 5.3 `res/values/colors.xml`

기본 색상 리소스입니다.

- 보라/청록/흑백 계열 템플릿 색상

Compose 앱 특성상 XML 색상 리소스 활용도는 높지 않습니다.

---

### 5.4 `res/values/themes.xml`

Android 테마 리소스입니다.

```xml
<style name="Theme.Ratio" parent="android:Theme.Material.Light.NoActionBar" />
```

- Android Manifest에서 앱/액티비티 테마로 지정됨
- Compose 내부 Material3 테마와는 별도 축의 설정

---

### 5.5 `res/xml/backup_rules.xml`
### 5.6 `res/xml/data_extraction_rules.xml`

둘 다 Android 기본 템플릿 성격의 파일입니다.

- 자동 백업
- 기기 간 데이터 이전 정책

현재는 대부분 주석 상태이며, 이 앱의 핵심 기능과 직접적인 연관은 거의 없습니다.

---

## 6. 화면 구조(UI 구조)

앱은 사실상 **단일 화면**입니다.

### 상단 영역

- `TopAppBar`
- 제목: `비율 편집기`

### 본문 영역

1. **이미지 선택 버튼**
   - `GetContent("image/*")` 실행

2. **미리보기 영역**
   - 편집본이 있으면 `editedBitmap` 표시
   - 없으면 `originalBitmap` 표시

3. **90도 회전 버튼**
   - 원본 비트맵을 직접 90도 회전
   - 이미 비율이 선택된 경우 즉시 재편집

4. **비율 선택 영역**
   - `LazyRow`로 가로 스크롤 버튼 목록 표시
   - 제공 비율:
     - 1:1
     - 4:3
     - 3:4
     - 16:9
     - 9:16
     - 3:2
     - 2:3

5. **저장 설정 카드**
   - 배경색 선택
   - 포맷 선택(JPG / PNG)
   - 품질 설정(슬라이더 다이얼로그)

6. **저장 버튼**
   - `editedBitmap`가 있을 때만 표시
   - `MediaStore`로 저장 수행

---

## 7. 앱 작동 방식 상세

### 7.1 앱 실행 흐름

1. 앱 실행
2. `MainActivity.onCreate()` 호출
3. `setContent { RatioApp() }`
4. Compose UI 표시
5. 사용자가 이미지 선택
6. 선택된 이미지를 EXIF 회전값 반영하여 로드
7. 사용자가 회전 / 비율 / 배경색 / 포맷 / 품질 설정
8. 비율 적용 결과를 `editedBitmap`으로 생성
9. 저장 버튼 클릭
10. `Pictures` 경로에 새 파일 생성

---

### 7.2 이미지 선택 흐름

사용 코드:

- `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())`

동작:

1. 시스템 파일 선택기 또는 갤러리 UI가 열림
2. 사용자가 이미지를 선택
3. URI가 반환됨
4. `loadBitmapWithOrientation(context, uri)` 실행
5. 원본 비트맵을 상태에 저장
6. 이전 편집 결과는 초기화

특징:

- 갤러리 경로 직접 접근보다 **Content URI 기반 접근**을 사용
- 저장소 구조를 직접 다루지 않아 최신 Android 정책에 비교적 유리

---

### 7.3 EXIF 방향 보정

카메라로 찍은 사진은 실제 픽셀은 가로인데 EXIF에만 회전 정보가 들어 있는 경우가 많습니다.

이 앱은 다음 순서로 보정합니다.

1. `BitmapFactory.decodeStream()`으로 이미지 로드
2. `getOrientationFromExif()`로 EXIF `TAG_ORIENTATION` 확인
3. 필요 시 `Matrix().postRotate(...)`
4. `Bitmap.createBitmap(...)`으로 회전된 비트맵 생성

지원 회전:

- 90도
- 180도
- 270도

즉, **사진이 옆으로 눕는 문제를 줄이기 위한 보정 로직**이 포함되어 있습니다.

---

### 7.4 90도 회전 기능

사용자가 누르는 `90도 회전` 버튼은 다음 방식으로 동작합니다.

1. 현재 `originalBitmap`을 가져옴
2. `Matrix`에 `postRotate(90f)` 적용
3. 회전된 새 비트맵으로 `originalBitmap` 갱신
4. `currentRotation` 값을 90씩 증가
5. 이미 비율이 선택돼 있으면 `resizeToAspectRatio()`를 다시 실행

중요:

- 회전 대상은 `editedBitmap`가 아니라 **`originalBitmap`** 입니다.
- 따라서 회전 후 다시 비율을 적용하는 구조입니다.

---

### 7.5 비율 적용 알고리즘

핵심 함수는 `resizeToAspectRatio(bitmap, targetRatio, backgroundColor)` 입니다.

이 함수는 **원본을 늘리거나 자르지 않고**, 목표 비율에 맞는 새 캔버스를 만든 뒤 원본을 중앙에 붙입니다.

#### 동작 원리

1. 원본 가로/세로 크기 확인
2. 원본 비율 계산
3. 목표 비율과 비교
4. 새 캔버스의 너비/높이 결정
5. 빈 캔버스 생성 (`ARGB_8888`)
6. 배경색으로 전체 채움
7. 원본 이미지를 중앙 좌표에 그림
8. 새 비트맵 반환

#### 예시 1: 원본이 세로 사진인데 16:9 선택

- 원본을 자르지 않음
- 좌우 또는 상하에 배경을 추가하여 16:9 박스를 만듦
- 원본은 중앙 유지

#### 예시 2: 원본이 가로 사진인데 1:1 선택

- 정사각형 캔버스 생성
- 남는 영역을 배경색으로 채움

즉, 이 앱은 **크롭 편집기라기보다 비율 캔버스 확장기**에 가깝습니다.

---

### 7.6 배경색 처리

배경색 enum:

- `WHITE`
- `BLACK`
- `TRANSPARENT`

비율 변경 시 새 캔버스를 만든 뒤 아래 방식으로 사용합니다.

```kotlin
canvas.drawColor(backgroundColor)
```

그 다음 원본 비트맵을 중앙에 그립니다.

주의점:

- `TRANSPARENT`는 비트맵 내부적으로는 가능하지만, 저장 형식을 `JPG`로 선택하면 투명도가 유지되지 않습니다.
- 투명 배경이 필요하면 **PNG 저장**을 선택해야 의미가 있습니다.

---

### 7.7 포맷 및 품질

#### 포맷

- JPG
- PNG

#### 품질

- 1~100 슬라이더
- `bitmap.compress(compressFormat, quality, outputStream)`에 전달

주의점:

- Android의 `PNG` 압축에서 `quality` 값은 사실상 큰 의미가 없는 경우가 많습니다.
- 반면 `JPEG`에서는 품질 값이 파일 크기와 손실 압축 품질에 직접적인 영향을 줍니다.

즉, 현재 UI는 JPG/PNG 모두 품질 설정을 허용하지만, **실질적으로는 JPG에서 더 의미 있는 설정**입니다.

---

### 7.8 저장 방식

저장은 `saveImage()` 함수에서 수행합니다.

#### 처리 순서

1. 원본 URI에서 파일명 추정
2. 현재 시각 타임스탬프 생성 (`yyMMddHHmmss`)
3. 새 파일명 생성
   - 예: `원본파일명_250310153012.jpg`
4. `ContentValues` 구성
   - 표시 이름
   - MIME 타입
   - 저장 경로: `Environment.DIRECTORY_PICTURES`
5. `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`에 insert
6. OutputStream 열기
7. `bitmap.compress(...)` 수행
8. 성공/실패 콜백 처리

#### 파일명 처리 장점

- 원본 이름 유지 시도
- 확장자 제거 후 새 확장자 부여
- 길이 제한 대응
- 이름이 비정상적일 경우 `IMG_yyyyMMdd` 대체

저장 위치는 일반적으로 **갤러리의 Pictures 폴더**입니다.

---

## 8. 실제 사용법

### 앱 사용자 기준 사용 방법

1. 앱 실행
2. `이미지 선택` 버튼 터치
3. 갤러리/파일 선택기에서 이미지 선택
4. 필요하면 `90도 회전` 버튼으로 방향 수정
5. 원하는 비율 버튼 선택
6. 배경색 선택
7. JPG 또는 PNG 선택
8. 품질 설정 변경
9. `저장` 버튼 터치
10. 저장 완료 후 갤러리에서 확인

추천 사용 예:

- 인스타그램용 1:1, 4:5 비슷한 결과가 필요할 때
- 스토리용 세로 비율(9:16) 맞출 때
- 원본을 자르지 않고 썸네일/게시용 비율을 맞추고 싶을 때

현재 앱에 4:5 비율은 없고, 대신 3:4 / 9:16 / 1:1 / 16:9 등의 고정 비율만 제공됩니다.

---

## 9. 개발자 기준 사용법

### 요구 환경

- Android Studio 최신 버전 권장
- JDK 17
- Android SDK 36 설치
- Gradle 환경

### 실행 절차

1. Android Studio에서 프로젝트 열기
2. Gradle Sync 수행
3. 에뮬레이터 또는 실제 기기 연결
4. `app` 모듈 실행

### 빌드 명령 예시

```bash
./gradlew assembleDebug
```

릴리즈 빌드:

```bash
./gradlew assembleRelease
```

참고:

- 이번 분석 환경에서는 네트워크 제약 때문에 Gradle Wrapper가 배포본을 내려받지 못해 실제 빌드 실행 검증은 완료하지 못했습니다.
- 다만 프로젝트 구조와 소스 코드는 정상적인 Android Compose 앱 형태로 구성되어 있습니다.

---

## 10. 코드 관점에서 본 장점

### 장점

1. **구조가 단순함**
   - 단일 액티비티, 단일 화면
   - 학습/유지보수 진입장벽이 낮음

2. **핵심 기능이 명확함**
   - 이미지 선택 → 회전 → 비율 적용 → 저장

3. **EXIF 회전 보정 지원**
   - 카메라 이미지 처리에서 중요한 부분을 반영함

4. **크롭 없이 비율 맞춤**
   - 원본 손실을 줄이는 방향

5. **MediaStore 저장 방식 사용**
   - 최신 Android 저장 방식과 비교적 잘 맞음

6. **파일명 처리 보완이 들어감**
   - 단순 저장보다 실사용성이 높음

---

## 11. 구조상 아쉬운 점 및 개선 포인트

### 11.1 `MainActivity.kt`에 로직 집중

현재는 UI, 상태, 이미지 처리, 저장 로직이 한 파일에 몰려 있습니다.

권장 분리 예:

- `MainActivity.kt`: 진입점만 담당
- `RatioScreen.kt`: 화면 UI
- `ImageEditorViewModel.kt`: 상태 관리
- `ImageProcessor.kt`: 비트맵 처리
- `ImageSaver.kt`: 저장 처리

이렇게 나누면 유지보수성이 좋아집니다.

---

### 11.2 Compose 테마 미적용

`ui/theme`는 정의돼 있지만 실제 화면에 연결되지 않았습니다.

개선 예:

```kotlin
setContent {
    RatioTheme {
        RatioApp()
    }
}
```

---

### 11.3 사용하지 않는 import / 의존성 존재 가능성

예를 들어:

- `coil.compose.AsyncImage` import가 보이지만 핵심 화면에서 사용되지 않음
- 일부 foundation/background 관련 import도 불필요할 가능성 있음

정리하면 코드 가독성이 좋아집니다.

---

### 11.4 권한 처리 로직 정리 필요

- `permissionLauncher`는 선언돼 있지만 실제 호출되지 않음
- 현재 저장 로직은 `MediaStore` 기반이므로 Android 버전에 따라 불필요한 권한이 있을 수 있음

즉, Manifest와 런타임 권한 흐름을 다시 정리하는 것이 좋습니다.

---

### 11.5 대용량 이미지 메모리 이슈 가능성

현재 이미지는 `BitmapFactory.decodeStream()`으로 원본 크기 그대로 읽습니다.

고해상도 이미지에서는:

- 메모리 사용량 증가
- 앱 느려짐
- OOM 가능성

개선 방향:

- `inSampleSize`로 다운샘플링
- 미리보기용/저장용 처리 분리
- 코루틴/백그라운드 처리 도입

---

### 11.6 비율 종류 확장성 부족

현재 비율은 하드코딩된 리스트입니다.

```kotlin
listOf(
    AspectRatioOption("1:1", 1f),
    AspectRatioOption("4:3", 4f / 3f),
    ...
)
```

추후 아래 기능을 넣을 수 있습니다.

- 사용자 지정 비율 입력
- 최근 사용 비율 저장
- SNS 프리셋(Instagram, YouTube, Shorts, Story 등)

---

### 11.7 JPG + 투명 배경 조합 UX 보완 필요

현재는 `TRANSPARENT`를 선택하고 `JPG`를 저장 포맷으로 선택할 수 있습니다.

하지만 JPG는 투명도를 지원하지 않으므로 사용자가 기대와 다른 결과를 볼 수 있습니다.

개선 예:

- 투명 배경 선택 시 PNG만 허용
- 또는 경고 메시지 표시

---

## 12. 핵심 함수별 역할 요약

### `loadBitmapWithOrientation(context, uri)`
- 이미지 로드
- EXIF 회전 보정 적용
- 보정된 비트맵 반환

### `getOrientationFromExif(context, uri)`
- EXIF orientation 태그 확인
- 0/90/180/270 회전값 반환

### `resizeToAspectRatio(bitmap, targetRatio, backgroundColor)`
- 목표 비율 캔버스 계산
- 배경 채움
- 원본 이미지 중앙 배치
- 편집 결과 비트맵 반환

### `saveImage(context, bitmap, originalUri, format, quality, ...)`
- 파일명 생성
- `MediaStore`에 이미지 저장
- 성공/실패 콜백 처리

### `getImprovedFileNameFromUri(context, uri)`
- URI에서 가능한 안정적으로 파일명 추출
- 예외 시 기본 파일명 생성

### `QualityDialog(...)`
- 슬라이더로 품질 설정

### `RatioButton(...)`
- 비율 선택 UI 버튼

---

## 13. 이 앱을 한 문장으로 정리하면

**갤러리 이미지를 선택해 EXIF 방향을 보정하고, 원하는 비율의 배경 캔버스에 중앙 배치한 뒤 JPG/PNG로 저장하는 Jetpack Compose 기반 이미지 비율 편집 앱**입니다.

---

## 14. 빠른 구조 요약 리스트

### Kotlin 파일 리스트

- `app/src/main/java/com/krdonon/ratio/MainActivity.kt`
  - 앱 핵심 전체 로직
- `app/src/main/java/com/krdonon/ratio/ui/theme/Color.kt`
  - 테마 색상
- `app/src/main/java/com/krdonon/ratio/ui/theme/Theme.kt`
  - Compose 테마
- `app/src/main/java/com/krdonon/ratio/ui/theme/Type.kt`
  - 타이포그래피
- `app/src/test/.../ExampleUnitTest.kt`
  - 샘플 테스트
- `app/src/androidTest/.../ExampleInstrumentedTest.kt`
  - 샘플 테스트

### 핵심 XML 리스트

- `app/src/main/AndroidManifest.xml`
  - 권한 및 액티비티 선언
- `app/src/main/res/values/strings.xml`
  - 앱 이름
- `app/src/main/res/values/colors.xml`
  - 색상 리소스
- `app/src/main/res/values/themes.xml`
  - Android 테마
- `app/src/main/res/xml/backup_rules.xml`
  - 백업 정책
- `app/src/main/res/xml/data_extraction_rules.xml`
  - 데이터 추출 정책

### 실제 기능 흐름 리스트

- 앱 시작
- 이미지 선택
- EXIF 보정 로드
- 원본 미리보기
- 90도 회전
- 비율 선택
- 배경색 선택
- 포맷/품질 선택
- 새 파일명 생성
- Pictures 폴더 저장

---

## 15. 결론

이 프로젝트는 **기능이 명확하고 구조가 단순한 Compose 이미지 편집 앱**입니다.

특징을 요약하면:

- UI는 Compose 단일 화면
- 비율 맞춤 방식은 크롭이 아니라 캔버스 확장
- 이미지 회전과 EXIF 보정 지원
- MediaStore 저장 지원
- 다만 코드가 한 파일에 집중되어 있고, 테마/권한/메모리 처리 측면에서는 개선 여지가 있음

유지보수나 기능 확장을 생각하면 다음 순서로 리팩터링하는 것이 좋습니다.

1. UI / 처리 로직 / 저장 로직 분리
2. `RatioTheme` 실제 적용
3. 이미지 다운샘플링 추가
4. 권한 정책 재정리
5. 사용자 지정 비율/프리셋 추가

