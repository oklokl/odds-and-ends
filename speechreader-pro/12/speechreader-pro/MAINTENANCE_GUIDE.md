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
7. [터치 오차 해결, 돋보기 잔상 방지 및 정밀 커서 활성화](#7-터치-오차-해결-돋보기-잔상-방지-및-정밀-커서-활성화)
8. [하단 버튼 찌그러짐 방지 및 2단 분리 레이아웃](#8-하단-버튼-찌그러짐-방지-및-2단-분리-레이아웃)
9. [자동 저장 카운터 0초 멈춤 방지 및 타이머 루프 구조](#9-자동-저장-카운터-0초-멈춤-방지-및-타이머-루프-구조)
10. [대용량 파일 TTS 미시작 원인(TransactionTooLargeException)과 TtsContentHolder](#10-대용량-파일-tts-미시작-원인transactiontoolargeexception과-ttscontentholder)
11. [로그 가로보기(화면 회전/텍스트 선택) 튕김 방지 및 네이티브 안정화](#11-로그-가로보기화면-회전텍스트-선택-튕김-방지-및-네이티브-안정화)
12. [문서 내 구분선(---, ───, 구분선 등) TTS 자동 스킵 및 기호 정제](#12-문서-내-구분선---구분선-등-tts-자동-스킵-및-기호-정제)

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

---

## 7. 터치 오차 해결, 돋보기 잔상 방지 및 정밀 커서 활성화

### ⚠️ 문제 현상과 원인 분석 (이유와 배경)
1. **커서 불일치 및 묵음/드래그 잠김 현상**:
   - 스크롤 후 특정 줄 끝을 터치했을 때 커서가 활성화되지 않거나, 엉뚱한 위치가 드래그 선택되는 현상이 발생했습니다.
2. **시스템 돋보기(Magnifier)가 멋대로 나타나 화면에 남는 현상**:
   - 안드로이드 9(API 28) 이상에는 텍스트 선택 핸들을 드래그할 때 시스템 돋보기가 자동으로 뜨는 기능이 내장되어 있습니다.
   - 스크롤을 시작할 때 내부 `TextView` 터치 파이프라인에 `ACTION_CANCEL` 신호가 전달되지 않아, 시스템은 "손가락으로 텍스트를 여전히 누른 채 선택 영역을 확장하고 있는 중"으로 착각하여 돋보기가 화면에 고정되고 터치가 먹통이 되는 원인이었습니다.

### 💡 해결 구현 메커니즘 ([LinedEditText.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/ui/LinedEditText.kt))

#### 1) 스크롤 진입 즉시 `ACTION_CANCEL` 전달 (돋보기 및 선택 잔상 원천 차단)
```kotlin
// LinedEditText.kt - onTouchEvent
if (!isScrolling && (dy > touchSlop && dy > dx)) {
    isScrolling = true
    parent?.requestDisallowInterceptTouchEvent(true)

    // 핵심: 스크롤이 시작되면 TextView에 ACTION_CANCEL을 강제 전달하여
    // 롱프레스 타이머, 돋보기(Magnifier), 드래그 선택 상태를 0ms 즉시 리셋
    if (!hasDispatchedCancel) {
        val cancelEvent = MotionEvent.obtain(event).apply {
            action = MotionEvent.ACTION_CANCEL
        }
        super.onTouchEvent(cancelEvent)
        cancelEvent.recycle()
        hasDispatchedCancel = true
    }
}
```

#### 2) 절대 스크롤 좌표 기반 정밀 커서 안착 (`placeCursorAt`)
사용자가 스크롤을 얼마나 많이 내렸든 상관없이, 화면 터치 픽셀 좌표 `(x, y)`에 현재 스크롤 오프셋 `(scrollY)`과 좌측 줄 번호 패딩 `(totalPaddingLeft)`을 합산하여 정확한 글자 위치를 찾아냅니다.

```kotlin
fun placeCursorAt(x: Float, y: Float) {
    val l = this.layout ?: return
    val top = totalPaddingTop
    val left = totalPaddingLeft
    // 좌측 줄 번호 거터 영역(left)을 제외하고, 현재 스크롤된 높이(scrollY)를 정확히 합산
    val adjustedX = (x - left + scrollX).coerceAtLeast(0f)
    val adjustedY = (y - top + scrollY).toInt().coerceAtLeast(0)

    val line = l.getLineForVertical(adjustedY)
    val offset = l.getOffsetForHorizontal(line, adjustedX)
    val safeOffset = offset.coerceIn(0, text?.length ?: 0)

    setSelection(safeOffset)
    requestFocus()

    // 소프트 키보드 즉시 호출
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}
```
- **줄 끝 빈 공간 클릭 시**: `getOffsetForHorizontal` 함수가 해당 줄의 가장 마지막 글자 오프셋을 자동으로 반환하므로, 사용자가 문장 끝 우측 빈 공간을 눌러도 문장 끝에 커서가 100% 정확하게 안착합니다.
- **좌측 줄 번호 영역 터치 시**: `adjustedX = 0f`가 되어 해당 줄의 맨 첫 글자로 커서가 이동합니다.

---

## 8. 하단 버튼 찌그러짐 방지 및 2단 분리 레이아웃

### ⚠️ 문제 현상과 원인 분석 ([CreateEditDocumentDialog.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/ui/CreateEditDocumentDialog.kt))
- 기존에는 `글자 수: 267542자 | 총 5148줄` 텍스트와 3개 버튼(`[검색]`, `[취소]`, `[수정 완료]`)이 한 행(Row)에 모두 들어있었습니다.
- 대용량 문서일수록 글자 수와 줄 수 텍스트가 가로 공간을 대다수 차지하여, 맨 우측 `[수정 완료]` 버튼에 남는 너비가 20dp 이하로 줄어들어 `수\n정\n완\n료` 형태로 세로로 찌그러졌습니다.

### 💡 해결 구조 (2단 분리 레이아웃)
```kotlin
// Bottom Footer: 1단(글자수/줄수) + 2단(3개 버튼 균등 배치)
Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    // 1단: 글자 수 및 총 줄 수 (전체 너비 독점 사용)
    Text(
        text = "글자 수: ${charCount}자  |  총 ${lineCount}줄",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp)
    )

    // 2단: 3개 버튼 가로 1열 균등 배치 (weight 비율 할당)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { showSearchDialog = true },
            modifier = Modifier.weight(1f) // 가로 비율 1.0
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("검색", maxLines = 1)
        }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f) // 가로 비율 1.0
        ) {
            Text("취소", maxLines = 1)
        }

        Button(
            onClick = { onConfirm(title, currentContent) },
            modifier = Modifier.weight(1.2f) // 가로 비율 1.2 (가장 넓게 안정적 배치)
        ) {
            Text(if (isEditing) "수정 완료" else "생성 하기", maxLines = 1)
        }
    }
}
```
- **효과**: 어떤 작은 스마트폰 화면에서도 버튼 너비가 좁아지지 않고, 세 버튼이 일정한 비율로 가로로 단정하게 표시됩니다.

---

## 9. 자동 저장 카운터 0초 멈춤 방지 및 타이머 루프 구조

### ⚠️ 문제 현상과 원인 분석
- 40초 동안 사용자가 입력을 하지 않아 자동 저장이 정상적으로 실행되었지만, 상단 버튼의 카운터 텍스트가 `자동 저장 (0초)` 상태로 계속 멈춰 있는 현상이 있었습니다.

### 💡 해결 구조 ([CreateEditDocumentDialog.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/ui/CreateEditDocumentDialog.kt))
```kotlin
LaunchedEffect(isAutoSaveEnabled, isEditing, activityTrigger) {
    if (!isEditing || !isAutoSaveEnabled) return@LaunchedEffect
    remainingSeconds = 40
    while (remainingSeconds > 0) {
        delay(1000L)
        remainingSeconds--
    }
    // 40초 도달: 실제 내용이 변경되었을 때만 저장 수행
    if (title.isNotBlank() && (title != lastSavedTitle || currentContent != lastSavedContent)) {
        onAutoSave?.invoke(title, currentContent)
        lastSavedTitle = title
        lastSavedContent = currentContent
        autoSaveStatusMessage = "40초 동안 입력이 없어 자동 저장되었습니다."
    }
    // 핵심: 저장이 끝나면 카운터를 0초에 머무르게 하지 않고 즉시 40초로 초기화
    remainingSeconds = 40
}
```
- 저장이 완료되면 `remainingSeconds`가 즉시 다시 `40`으로 복구되어, 화면 상단 버튼이 항상 정상적인 초 단위를 표시합니다.
- 알림 배너 메시지(`autoSaveStatusMessage`)는 별도의 `LaunchedEffect`에서 3.5초 뒤 부드럽게 사라집니다.

---

## 10. 대용량 파일 TTS 미시작 원인(TransactionTooLargeException)과 TtsContentHolder

### ⚠️ 문제 현상과 원인 분석 ([TtsPlaybackService.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/service/TtsPlaybackService.kt))
- **현상**: 작은 크기의 txt 파일은 음성 TTS가 정상 시작되지만, `생각대로_움직이는_세상.txt`(267,542자 / 약 535KB) 같은 대용량 파일은 플레이 버튼을 눌러도 TTS가 전혀 시작되지 않는 현상.
- **근본 원인 (안드로이드 시스템 IPC Binder 한계)**:
  - 기존에는 `TtsPlaybackService.startOrPlay` 함수에서 `intent.putExtra(EXTRA_DOC_CONTENT, content)`로 전체 텍스트 본문을 Intent에 실어 보냈습니다.
  - 안드로이드 시스템의 프로세스 간 통신(IPC Binder) 트랜잭션 버퍼 한계는 프로세스 전체를 통틀어 **512KB ~ 1MB**입니다.
  - 26만 7천 자의 문자열은 UTF-16으로 약 535KB를 초과하여 시스템 서버에서 `TransactionTooLargeException: data parcel size 537482 bytes`가 발생하였고, 안드로이드 OS가 서비스 시작 인텐트 자체를 누락/거부하여 서비스가 아예 호출되지 않았던 것입니다.

### 💡 해결 구조 (`TtsContentHolder` 인메모리 싱글톤)
동일 앱 프로세스 내에서 서비스를 구동하므로 무거운 문자열을 Intent로 직렬화할 필요 없이, 메모리 주소 참조를 사용하는 싱글톤 홀더 패턴을 적용했습니다:

```kotlin
// TtsPlaybackService.kt
object TtsContentHolder {
    @Volatile
    var activeContent: String = ""
}

// 1. 서비스 시작 호출부
fun startOrPlay(context: Context, docId: Long, title: String, content: String, startIndex: Int = 0) {
    // 텍스트 본문은 힙 메모리에 즉시 참조 보관 (0ms, 0바이트 IPC 오버헤드)
    TtsContentHolder.activeContent = content

    val intent = Intent(context, TtsPlaybackService::class.java).apply {
        action = ACTION_START_OR_PLAY
        putExtra(EXTRA_DOC_ID, docId)
        putExtra(EXTRA_DOC_TITLE, title)
        putExtra(EXTRA_START_INDEX, startIndex) // Intent에는 본문을 넣지 않음!
    }
    context.startForegroundService(intent)
}

// 2. 서비스 실행부 (onStartCommand)
val content = TtsContentHolder.activeContent.ifBlank {
    // 혹시 프로세스가 재생성된 경우 Room DB에서 비동기로 안전 복구
    repository.getDocumentOnce(docId)?.content ?: ""
}
```
- **효과**: 26만 자는 물론, 100만 자 이상의 초대용량 문서도 Intent 크기가 200바이트 미만으로 유지되어 `TransactionTooLargeException`이 100% 영구적으로 방지되며 즉시 재생됩니다.

---

## 11. 로그 가로보기(화면 회전/텍스트 선택) 튕김 방지 및 네이티브 안정화

### ⚠️ 문제 현상과 원인 분석 ([TtsLogDialog.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/ui/TtsLogDialog.kt))
- **현상**: TTS 진단 로그를 터치하여 선택한 상태에서 폰을 가로로 돌려 보거나 화면을 회전할 때 앱이 강제 종료(튕김)되는 현상.
- **근본 원인**:
  1. Jetpack Compose의 `SelectionContainer`가 가로/세로 양방향 스크롤(`verticalScroll` + `horizontalScroll`)과 중첩된 상태에서 화면이 회전(Configuration Change)되면, 기존 뷰 계층이 분리되면서 `LayoutCoordinates is not attached to Compose hierarchy` 오류가 발생합니다.
  2. 회전 시 액티비티가 재시작되면서 다이얼로그 높이(`280.dp`)가 가로 모드의 짧은 화면 높이(~360dp)를 초과하여 레이아웃 오버플로우가 발생했습니다.

### 💡 해결 구조
1. **안드로이드 네이티브 텍스트 뷰(`AndroidView` + `TextView`) 적용**:
   - 가로/세로 회전 시에도 예외 없이 100% 안정적인 네이티브 `TextView`를 사용하여 텍스트 선택(`setTextIsSelectable(true)`) 및 스크롤(`ScrollingMovementMethod`)을 안전하게 처리했습니다.
2. **다이얼로그 화면 적응형 높이(`heightIn`) & 전체 스크롤 지원**:
   - 다이얼로그 전체를 `verticalScroll(rememberScrollState())`로 감싸고 `heightIn(min = 140.dp, max = 260.dp)`를 적용하여, 가로 모드에서도 잘림이나 크래시 없이 부드럽게 스크롤되도록 개선했습니다.
3. **액티비티 재생성 방지 설정 (`AndroidManifest.xml`)**:
   - `MainActivity`에 `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`를 선언하여 화면 회전 시 액티비티가 강제 재시작되지 않고 자연스럽게 레이아웃만 적응하도록 보장했습니다.

---

## 12. 문서 내 구분선(---, ───, 구분선 등) TTS 자동 스킵 및 기호 정제

### ⚠️ 문제 현상과 원인 분석 ([SpeechSanitizer.kt](file:///G:/AndroidStudioProjects/speechreader-pro/app/src/main/java/com/krdondon/read/util/SpeechSanitizer.kt))
- **현상**: txt 문서나 웹소설 내에 `----------`, `──────────`, `---------- 구분선 ──────────`, `* * * * *` 등의 단락 구분선이 있을 때, TTS가 이를 읽으면서 *"다시 다시 다시...", "대시 대시...", "상자 그리기 가로선..."* 처럼 불필요한 기호 이름을 끊임없이 소리 내어 읽어 독서 흐름을 심각하게 방해하는 문제.

### 💡 해결 구조 (`SpeechSanitizer` 및 `TtsPlaybackService` 연동)
1. **순수 구분선 자동 스킵 (Silent Transition)**:
   - `SpeechSanitizer.shouldSkipSentence(chunk.text)`가 `true`인 경우(반복 기호나 '구분선', '절취선' 등 내용 없는 문장):
   - TTS가 소리를 내지 않고 **120ms의 자연스러운 숨고르기 간격**을 두고 다음 문장으로 매끄럽게 넘어갑니다.
   - 화면 UI의 읽는 위치 하이라이트는 구분선을 거쳐 자연스럽게 다음 문장으로 이동합니다.
2. **복합 제목 기호 정제 (Speech Text Sanitization)**:
   - `---------- 제1장 시작 ----------` 처럼 기호 사이에 실제 내용이 있는 경우:
   - 앞뒤의 반복 대시(`-`, `─`)만 깔끔하게 제거하고 **`제1장 시작`만 맑게 발화**합니다.



