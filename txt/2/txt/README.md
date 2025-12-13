# 글쓰기 pdf - Android 문서 편집기

TXT와 PDF 파일을 생성, 편집, 관리할 수 있는 Android 앱입니다.

## 주요 기능

### ✨ 핵심 기능
- **파일 관리**: 다운로드 폴더의 TXT/PDF 파일 목록 표시
- **새 문서 작성**: 빠르게 새 문서 생성 (자동 파일명: "제목 없음_날짜시간.txt")
- **파일 편집**: TXT 파일 편집, PDF 텍스트 추출 후 편집
- **자동 저장**: 40초 입력 멈춤 후 자동 임시 저장
- **PDF 내보내기**: 작성한 문서를 PDF로 저장
- **인쇄 기능**: Android 기본 프린터로 문서 인쇄

### 📱 UI/UX
- **편집 화면 배경색**: #CFFFE5 (연한 민트색)
- **A4 용지 느낌**: 카드 형태의 편집 영역
- **파일명 수정**: 제목 클릭으로 쉽게 파일명 변경
- **Material 3 디자인**: 현대적이고 깔끔한 UI

## 기술 스택

- **언어**: Kotlin
- **UI**: Jetpack Compose
- **PDF 처리**: PDFBox Android
- **네비게이션**: Navigation Compose
- **권한 관리**: Accompanist Permissions

## 설치 및 실행

### 1. 프로젝트 클론
```bash
git clone [repository-url]
cd txt-app
```

### 2. Android Studio에서 열기
- Android Studio Hedgehog (2023.1.1) 이상 권장
- Gradle 동기화 실행

### 3. (선택사항) PDF 한글 지원을 위한 폰트 추가
PDF 내보내기 시 한글을 제대로 표시하려면:

1. 나눔고딕 폰트 다운로드: https://hangeul.naver.com/font
2. `NanumGothic.ttf` 파일을 `app/src/main/assets/` 폴더에 복사

**참고**: 폰트 파일이 없어도 앱은 정상 작동하며, PDF 생성 시 fallback 로직이 실행됩니다.

### 4. 빌드 및 실행
- Android 기기 또는 에뮬레이터에서 실행
- 최소 SDK: API 26 (Android 8.0)
- 타겟 SDK: API 36

## 권한 요구사항

앱 실행 시 다음 권한이 필요합니다:

- **Android 13 이상**:
  - READ_MEDIA_IMAGES
  - READ_MEDIA_VIDEO
  - READ_MEDIA_AUDIO

- **Android 12 이하**:
  - READ_EXTERNAL_STORAGE
  - WRITE_EXTERNAL_STORAGE

## 파일 저장 위치

모든 파일은 **다운로드 폴더**에 저장됩니다:
- 경로: `/storage/emulated/0/Download/`
- 파일 형식: `.txt`, `.pdf`

## 사용 방법

### 새 문서 작성
1. 메인 화면에서 `+` 버튼 클릭
2. 편집 화면에서 내용 작성
3. 상단의 파일명을 클릭하여 이름 변경 가능
4. 저장 버튼 또는 자동 저장으로 저장

### 기존 파일 열기
1. 메인 화면의 파일 목록에서 파일 선택
2. TXT 파일: 바로 편집 가능
3. PDF 파일: 텍스트 추출 후 편집 가능

### PDF로 내보내기
1. 편집 화면에서 우측 상단 메뉴 (`⋮`) 클릭
2. "PDF로 내보내기" 선택
3. 다운로드 폴더에 PDF 파일 생성

### 인쇄하기
1. 편집 화면에서 우측 상단 메뉴 (`⋮`) 클릭
2. "인쇄" 선택
3. Android 기본 인쇄 다이얼로그에서 프린터 선택

## 프로젝트 구조

```
app/src/main/
├── java/com/krdondon/txt/
│   ├── MainActivity.kt              # 메인 액티비티
│   ├── model/
│   │   └── FileItem.kt             # 파일 데이터 모델
│   ├── ui/
│   │   ├── screens/
│   │   │   ├── FileListScreen.kt   # 파일 목록 화면
│   │   │   └── EditorScreen.kt     # 편집 화면
│   │   └── theme/                  # Material 3 테마
│   └── utils/
│       ├── FileManager.kt          # 파일 관리 유틸
│       ├── PermissionHandler.kt    # 권한 처리
│       └── PrintHelper.kt          # 인쇄 기능
├── res/
│   ├── values/
│   │   └── strings.xml
│   └── xml/
│       ├── backup_rules.xml
│       └── data_extraction_rules.xml
└── assets/
    └── README_FONT.txt             # 폰트 설치 안내
```

## 주요 클래스 설명

### FileManager
- 파일 읽기/쓰기/삭제
- PDF 텍스트 추출
- PDF 생성 (한글 지원)

### EditorScreen
- 텍스트 편집
- 40초 자동 저장
- 파일명 변경
- PDF 내보내기/인쇄

### FileListScreen
- 파일 목록 표시
- 파일 새로고침
- 새 문서 생성

## 알려진 이슈 및 제한사항

1. **PDF 편집**: PDF의 레이아웃/이미지는 유지되지 않고 텍스트만 추출됩니다
2. **폰트**: 나눔고딕 폰트가 없으면 PDF 한글 표시가 제한될 수 있습니다
3. **대용량 파일**: 매우 큰 파일 편집 시 성능 저하 가능

## 향후 개발 계획

- [ ] 텍스트 서식 지원 (굵게, 기울임 등)
- [ ] 글자 수/단어 수 표시
- [ ] 다크 모드 지원
- [ ] 클라우드 백업 기능
- [ ] 파일 검색 기능

## 라이선스

이 프로젝트는 개인 사용 목적으로 개발되었습니다.

## 개발자

- **앱 이름**: 글쓰기 pdf
- **패키지명**: com.krdondon.txt
- **개발 환경**: Android Studio, Kotlin, Jetpack Compose

## 문제 해결

### 앱이 파일을 찾을 수 없어요
- 저장소 권한이 허용되었는지 확인
- 다운로드 폴더에 TXT/PDF 파일이 있는지 확인
- 파일 목록 화면에서 새로고침 버튼 클릭

### PDF 저장이 안 돼요
- 저장소 용량 확인
- 앱 권한 확인
- 폰트 파일 설치 (한글 지원용)

### 자동 저장이 안 돼요
- 40초 동안 입력을 멈춰야 자동 저장됩니다
- 수동 저장 버튼을 사용해주세요

## 지원

문제가 발생하거나 제안사항이 있으시면 이슈를 등록해주세요.
