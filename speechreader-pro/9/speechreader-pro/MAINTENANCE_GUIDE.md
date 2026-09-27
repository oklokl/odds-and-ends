# K 읽기 - 개발자 유지보수 & 최적화 가이드 (Developer & Maintenance Guide) 🛠️📘

이 문서는 **K 읽기** 앱의 유지보수, 대용량 텍스트 저장 방안, 에디터 및 스크롤 파라미터 튜닝, 향후 기능 확장 시 도움이 되는 핵심 기술 가이드를 정리한 문서입니다.

---

## 📌 목차 (Table of Contents)
1. [대용량 텍스트 저장 방안 (Storage Architecture Tips)](#1-대용량-텍스트-저장-방안-storage-architecture-tips)
2. [물리 관성 가속 스크롤 파라미터 튜닝](#2-물리-관성-가속-스크롤-파라미터-튜닝)
3. [에디터 줄 번호(행 번호) 및 검색 튜닝](#3-에디터-줄-번호행-번호-및-검색-튜닝)
4. [40초 자동 저장 및 라이프사이클 안전망](#4-40초-자동-저장-및-라이프사이클-안전망)
5. [1GB 이상 초대용량 파일 스트리밍 활용법](#5-1gb-이상-초대용량-파일-스트리밍-활용법)
6. [빌드 및 테스트 명령어 모음](#6-빌드-및-테스트-명령어-모음)

---

## 1. 대용량 텍스트 저장 방안 (Storage Architecture Tips)

### ⚠️ 안드로이드 SQLite CursorWindow (2MB) 한계
현재 앱은 텍스트 본문(String)을 Room 데이터베이스의 `txt_documents` 테이블 `content` 컬럼에 직접 저장합니다.
- **현재 상태 (수십만 자 / 수백 KB)**:
  - 12만 자(약 250KB) 크기는 SQLite의 기본 CursorWindow 한계(2MB) 내에 충분히 들어가므로 정상 동작합니다.
- **잠재적 위험 (수 MB ~ 수십 MB의 초대용량 파일 여러 개 저장 시)**:
  - 문서 목록 화면에서 `SELECT * FROM txt_documents`를 실행할 때, 모든 문서의 본문이 한 번에 CursorWindow로 로딩되면서 메모리 낭비 또는 `RowTooBigException`이 발생할 수 있습니다.

### 💡 권장 분리 저장 아키텍처 (Hybrid Storage)
차후 10MB 이상의 대형 소설이나 다수의 대용량 텍스트를 안정적으로 지원하려면 다음 구조로의 전환을 권장합니다:

```text
[ 구조 설계 ]
1. Room DB (메타데이터 전용)
   - id: Long
   - title: String
   - filePath: String (내부 저장소 파일 경로)
   - previewText: String (목록 카드 표시용 앞 150자)
   - charCount: Int
   - lineCount: Int
   - bookmarkCharIndex: Int
   - bookmarkSentenceIndex: Int
   - updatedAt: Long

2. 내부 저장소 (텍스트 본문 파일)
   - context.filesDir / "documents" / "doc_{id}.txt"
```

#### 장점:
1. **목록 로딩 속도 100배 향상**: `content`를 제외한 가벼운 메타데이터만 쿼리하므로 수백 개의 문서가 있어도 목록이 0ms로 뜹니다.
2. **용량 무제한**: 100MB 이상의 대형 텍스트 파일도 DB 크기 제한 없이 저장 가능합니다.
3. **읽기/수정 시 스트리밍**: 필요한 문서만 파일 스트림으로 열어 편집 및 TTS 재생이 가능합니다.

---

## 2. 물리 관성 가속 스크롤 파라미터 튜닝

[LinedEditText.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/ui/LinedEditText.kt)에 구현된 관성 가속 스크롤은 사용자의 스와이프 속도에 따라 감도와 이동 범위를 세밀하게 조절할 수 있습니다.

```kotlin
// LinedEditText.kt 내부 FlingGestureListener
val absVelocityY = abs(velocityY)

// 1. 속도별 가속 계수 조절 (스와이프 속도에 따라 증폭할 배수)
val accelerationFactor = when {
    absVelocityY > 9000 -> 2.4f // 초고속 플링: 기본 2.4배 가속 (더 멀리 가려면 2.8f~3.2f)
    absVelocityY > 5000 -> 1.8f // 빠른 플링: 기본 1.8배 가속
    absVelocityY > 2500 -> 1.3f // 중간 플링: 기본 1.3배 가속
    else -> 1.0f                // 부드러운 스와이프: 손가락 움직임과 1:1 일치
}

// 2. 동적 마찰력 (Friction) 조절 (작을수록 얼음판처럼 멀리 미끄러짐)
// 기본 안드로이드 마찰력은 0.015f입니다.
val dynamicFriction = if (absVelocityY > 4000) 0.006f else 0.012f
scroller.setFriction(dynamicFriction)
```

- **더 시원하게 멀리 미끄러지게 하고 싶을 때**:
  - `dynamicFriction`을 `0.004f`로 낮추고 `accelerationFactor`를 `2.8f`로 높입니다.
- **너무 많이 내려가는 것을 방지하고 적당히 멈추게 하고 싶을 때**:
  - `dynamicFriction`을 `0.009f`로 올리거나 `accelerationFactor`를 `1.6f`로 낮춥니다.

---

## 3. 에디터 줄 번호(행 번호) 및 검색 튜닝

### 줄 번호 스타일 변경
[LinedEditText.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/ui/LinedEditText.kt) 상단의 `Paint` 설정을 통해 줄 번호 색상과 글자 크기를 변경할 수 있습니다:
```kotlin
private val lineNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#1976D2") // 파란색 (원하는 색상 코드로 변경 가능)
    typeface = Typeface.MONOSPACE       // 고정폭 폰트로 숫자가 반듯하게 정렬됨
    textAlign = Paint.Align.RIGHT
}
```

### 검색 및 커서 하이라이트
- 검색 시 현재 커서 위치(`selectionEnd`)를 기점으로 아래쪽 일치 항목을 우선 검색하며, 문서 끝에 도달하면 자동으로 문서 첫 머리로 순환(`wrapAround`)합니다.
- 일치된 단어는 `setSelection(offset, offset + length)`를 통해 네이티브 선택 하이라이트가 적용되고 화면의 1/3 지점으로 자동 스크롤됩니다.

---

## 4. 40초 자동 저장 및 라이프사이클 안전망

[CreateEditDocumentDialog.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/ui/CreateEditDocumentDialog.kt)의 자동 저장 시스템 구조:

```kotlin
// 40초 비활성(Inactivity) 감지 타이머
LaunchedEffect(isAutoSaveEnabled, isEditing, activityTrigger) {
    if (!isEditing || !isAutoSaveEnabled) return@LaunchedEffect
    remainingSeconds = 40
    while (remainingSeconds > 0) {
        delay(1000L)
        remainingSeconds--
    }
    // 40초 동안 타이핑이나 커서 움직임이 없을 때 자동 저장 트리거
    if (title.isNotBlank() && (title != lastSavedTitle || currentContent != lastSavedContent)) {
        onAutoSave?.invoke(title, currentContent)
        ...
    }
}
```
- 사용자가 글자를 입력할 때마다 `activityTrigger++`가 실행되어 40초 카운트다운이 처음부터 다시 리셋됩니다.
- 사용자가 [해제] 버튼을 누르면 `isAutoSaveEnabled = false`가 되어 코루틴 루프가 즉시 종료되고 자동 저장이 멈춥니다.

---

## 5. 1GB 이상 초대용량 파일 스트리밍 활용법

[LargeFileStreamReader.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/util/LargeFileStreamReader.kt)는 1GB 이상의 초대형 텍스트 파일도 OOM(OutOfMemory) 없이 열 수 있는 모듈입니다.

### 1) 특정 위치 페이징 읽기 (전자책 뷰어용)
```kotlin
// 100MB 파일의 1,000,000번째 바이트 위치부터 64KB(한 페이지 분량)만 0ms 지연으로 읽기
val pageText = LargeFileStreamReader.readChunkWithFileChannel(
    file = myLargeFile,
    startOffset = 1_000_000L,
    length = 64 * 1024
)
```

### 2) 비동기 한 줄씩 스트리밍 (TTS 순차 발화용)
```kotlin
// 1GB 파일이라도 메모리에 전부 로드하지 않고 한 줄씩 읽어오기
lifecycleScope.launch {
    LargeFileStreamReader.streamLines(myLargeFile).collect { line ->
        // 한 줄씩 처리하거나 TTS 대기열에 추가
    }
}
```

---

## 6. 빌드 및 테스트 명령어 모음

터미널이나 Gradle 빌드 도구에서 다음 명령어로 변경 사항을 신속하게 검증할 수 있습니다:

```bash
# 1. 단위 테스트 실행 (LargeFileStreamReader, LinedEditText 검색/줄수 등 8개 테스트)
./gradlew :app:testDebugUnitTest

# 2. Kotlin 컴파일 검증
./gradlew :app:compileDebugKotlin

# 3. 디버그 APK 빌드
./gradlew :app:assembleDebug
```
