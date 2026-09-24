# K 읽기 (Korean Text Reader) 📖🔊

**K 읽기**는 텍스트(TXT) 파일을 편리하게 읽고, 안드로이드 내장 TTS(Text-To-Speech) 엔진을 통해 백그라운드에서도 편안하게 음성으로 들을 수 있는 스마트 텍스트 뷰어 & 오디오북 리더 앱입니다.

---

## ✨ 주요 기능 (Key Features)

### 1. 텍스트 문서 관리 및 불러오기
- **TXT 파일 불러오기**: 기기 내 저장된 `.txt` 문서를 손쉽게 임포트
- **인코딩 자동 감지**: UTF-8 및 EUC-KR(CP949) 인코딩을 자동 감지하여 한글 깨짐 방지
- **직접 작성 및 편집**: 앱 내에서 직접 문서를 작성하거나 기존 내용을 자유롭게 수정 및 삭제
- **대용량 파일 OOM 방지**: 메모리 누수 및 비정상 종료를 방지하는 안전한 스트림 로딩 적용

### 2. 강력한 TTS 백그라운드 오디오북 & 듀얼 엔진 지원
- **백그라운드 지속 재생**: 안드로이드 포그라운드 서비스(Foreground Service - Media Playback) 및 WakeLock을 적용하여 화면이 꺼지거나 다른 앱을 사용할 때도 끊김 없이 재생
- **듀얼 TTS 엔진 원클릭 전환 (삼성 TTS ⟷ 구글 TTS)**:
  - 삼성 갤럭시 폰의 **"삼성 기본 TTS 엔진 (`com.samsung.SMT`)"**과 **"Google TTS (`com.google.android.tts`)"**를 설정 다이얼로그에서 버튼 하나로 손쉽게 전환
  - Android 11~16 Package Visibility(`<queries>`)를 완벽히 준수하여 삼성 단말기 호환성 보장
- **3중 안전망 양방향 자동 폴백 (Auto-Fallback)**:
  - 시스템(삼성) TTS 초기화 실패, 기기 내 한국어 음성 팩 누락, 또는 발화 거부 시 자동으로 구글 TTS로 즉시 전환되어 음성이 끊기지 않고 재생되도록 보호
- **무한 반복 재생 (Loop Playback) 🔁**: 텍스트의 마지막 문장까지 다 읽으면 자동으로 처음으로 돌아가서 끊김 없이 계속 읽어주는 무한 루프 재생 기능 지원 (상단 앱바에서 손쉽게 켜기/끄기 토글 가능)
- **문장 단위 실시간 하이라이팅**: 현재 읽고 있는 문장을 시각적으로 강조 표시하고, 독서 진행에 따라 자동 스크롤
- **자유로운 위치 탐색**: 읽고 싶은 문장을 직접 터치하여 해당 문장부터 즉시 듣기, 이전/다음 문장 건너뛰기
- **음성 상세 설정**: 사용자의 취향에 맞게 읽기 속도(Speed)와 음높이(Pitch)를 세밀하게 조절

### 3. AI 진단 로그 시스템 (Diagnostic Logging) 🤖📋
- **실시간 진단 로그 뷰어**: TTS 음성 설정 화면 좌측 하단의 `[ ≡ 로그 ]` 버튼을 통해 실시간 동작 및 엔진 연결 상태 확인
- **원클릭 복사, 저장 및 공유**:
  - 📋 **클립보드 복사**: 전체 진단 로그를 클립보드로 즉시 복사
  - 💾 **다운로드 폴더 저장**: 기기의 Download 폴더에 `KReader_TTS_Log_YYYYMMDD_HHMMSS.txt` 파일로 즉시 저장
  - 📤 **외부 공유**: Gmail, 메시지, 카카오톡 등 설치된 앱으로 진단 로그 즉시 전송
- **용량 최적화 및 40분 자동 삭제 타이머**:
  - 앱을 실행할 때마다 이전 로그를 자동으로 덮어써서 불필요한 용량 점유 방지
  - 앱이 백그라운드로 전환된 후 **40분간 미사용 시 로그 및 임시 파일 자동 정리**
- **AI 분석 최적화 영문 구조화 포맷**:
  - 플레이 버튼 클릭, 엔진 초기화, 언어 바인딩, 문장 발화 결과가 AI가 즉각 파싱할 수 있는 표준 영문 Key-Value 포맷으로 기록되어 기기별 오류 원인을 손쉽게 규명 가능

### 4. 독서 편의 기능
- **스마트 책갈피 (Bookmark)**: 읽던 위치를 원클릭으로 북마크하고, 언제든지 해당 위치로 즉시 점프
- **화면 켜짐 유지 (Keep Screen On)**: 독서 중 화면이 저절로 꺼지지 않도록 화면 켜짐 유지 토글 기능 제공
- **배터리 최적화 예외 지원**: 백그라운드에서 시스템에 의해 앱이 강제 종료되지 않도록 배터리 제한 해제 설정 가이드 제공
- **알림창 미디어 컨트롤**: 상태 표시줄(Notification)에서 재생/일시정지, 정지, 현재 진행률 및 읽는 문장 미리보기 지원

### 5. 규정 준수 및 기기 이전 최적화 (Compliance & Optimization)
- **Google Play Age Signals API (0.0.4)**: 미성년자 보호 및 전연령 적합성 규정을 준수하며, 연령 신호 데이터를 로컬에 영구 저장하지 않고 메모리에서 안전하게 처리
- **기기 이전 데이터 백업 (Device Migration)**: 폰을 변경하거나 백업 복원 시 문서 및 독서 진행 상황(Room DB, SharedPreferences)이 안전하게 이전되도록 백업 규칙 최적화
- **R8 DEX 코드 축소 및 최적화**: Google Play 최신 앱 품질 가이드라인(2027)에 맞추어 릴리즈 시 R8 난독화 및 리소스 축소 적용

---

## 🛠 기술 스택 (Tech Stack)

| 분류 | 기술 |
| :--- | :--- |
| **언어** | Kotlin (2.2.10) |
| **UI 프레임워크** | Jetpack Compose, Material 3 (2026.09.00 BoM) |
| **아키텍처** | MVVM (Model-View-ViewModel), StateFlow, UDF |
| **로컬 데이터베이스** | Android Room Database (2.8.5, SQLite) |
| **비동기 처리** | Kotlin Coroutines, StateFlow (1.11.0) |
| **백그라운드 서비스** | Foreground Service (`mediaPlayback`), Media Notification, WakeLock |
| **음성 합성** | Android TextToSpeech API (삼성 SMT / Google TTS 듀얼 지원) |
| **규정 준수** | Google Play Age Signals SDK (0.0.4) |
| **빌드 시스템** | Gradle (9.7.1), AGP (9.4.0), Java 17, Version Catalogs |

---

## 📂 프로젝트 구조 (Project Structure)

```text
com.krdondon.read/
├── compliance/             # Google Play Age Signals 규정 준수 처리
│   └── AgeSignalsCompliance.kt
├── data/
│   ├── db/                 # Room Database, DAO 정의
│   ├── model/              # TxtDocument 데이터 엔티티
│   └── repository/         # 문서 저장소 (DocumentRepository)
├── service/
│   ├── TtsPlaybackService.kt # 백그라운드 TTS 포그라운드 서비스 (듀얼 엔진 & 폴백)
│   └── TtsStateHolder.kt     # TTS 상태 관리 및 StateFlow (엔진/루프/속도/피치)
├── ui/
│   ├── DocumentListScreen.kt # 메인 문서 목록 화면
│   ├── ReaderScreen.kt       # 텍스트 뷰어 및 리더 화면 (오디오 컨트롤러)
│   ├── DocumentViewModel.kt  # 통합 뷰모델
│   ├── TtsSettingsDialog.kt  # TTS 설정 다이얼로그 (엔진 토글 및 로그 버튼)
│   ├── TtsLogDialog.kt       # 실시간 TTS 진단 로그 뷰어 (복사/저장/공유)
│   ├── BatteryExemptionDialog.kt # 배터리 최적화 안내
│   └── theme/                # Material 3 테마 및 컬러
├── util/
│   ├── TtsLogManager.kt             # AI 구조화 진단 로그 매니저 (40분 타이머)
│   ├── BatteryOptimizationHelper.kt # 배터리 최적화 유틸
│   ├── SentenceParser.kt            # 문장 분리 및 인덱싱 파서
│   └── TxtFileHelper.kt             # TXT 파일 로더 및 인코딩 처리
└── MainActivity.kt         # 단일 액티비티 엔트리포인트 (Edge-to-Edge)
```

---

## 🚀 빌드 및 실행 방법 (How to Build & Run)

### 요구 사양
- **Android Studio**: Ladybug / Otter 이상 권장
- **JDK**: JDK 17 이상
- **최소 지원 OS**: Android 8.0 (API Level 26) 이상
- **타겟 OS**: Android 16 (API Level 37)

### 빌드 명령어

1. **프로젝트 클린**:
   ```bash
   ./gradlew clean
   ```

2. **디버그 APK 빌드**:
   ```bash
   ./gradlew :app:assembleDebug
   ```

3. **단위 테스트 실행**:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

4. **R8 릴리즈 축소 빌드 검증**:
   ```bash
   ./gradlew :app:minifyReleaseWithR8
   ```

---

## 🔒 개인정보 및 보안 정책 (Privacy & Policy)
- **완전 오프라인 동작**: 인터넷 연결 없이 기기 내부에서 모든 문서가 로컬(Room DB)에만 저장됩니다.
- **광고 및 유료 결제 없음**: 앱 내 광고 SDK 및 인앱 결제 라이브러리가 포함되어 있지 않습니다.
- **연령 데이터 미저장**: Google Play Age Signals API를 통해 런타임에 확인되는 연령 신호는 파일이나 데이터베이스에 일체 영구 저장되지 않으며 메모리 내에서만 안전하게 관리됩니다.
- **진단 로그 안전성**: 진단 로그는 사용자 기기 내부 캐시에만 임시 보관되며, 40분 미사용 시 자동 파기되고 사용자가 직접 저장하거나 공유하기 전까지 외부로 전송되지 않습니다.
