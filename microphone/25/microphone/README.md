# 상단 마이크 (Microphone) - Android 프리미엄 음성 녹음기

상단/하단 마이크를 선택하여 선명하게 녹음하고, 기기 내에만 안전하게 보관하는 **100% 온디바이스(On-Device) 안드로이드 녹음 앱**입니다.

---

## 🎯 주요 기능

### 1. 마이크 위치 선택 (핵심 기능)
- **상단 마이크**: 전면 카메라/상단 지향성 마이크를 활용하여 인터뷰, 강의, 전방 녹음에 최적화
- **하단 마이크**: 통화용 하단 마이크를 활용하여 일반적인 음성 메모 및 가까운 소리 녹음에 최적화

### 2. 고품질 녹음 및 포맷 지원
- **M4A & 고음질 MP3 완벽 지원**:
  - 표준 M4A 즉시 저장 지원
  - 고음질 MP3 변환 저장 지원 (순수 Java LAME 인코더 파이프라인 내장, 변환 시 실시간 진행률 게이지바 팝업 표시)
- **음질 선택**: 고음질(256kbps), 중간(128kbps), 저음질(64kbps) 48kHz
- **스테레오 / 모노 녹음**: 단말기 하드웨어 지원 시 입체감 있는 스테레오 녹음
- **실시간 파형 애니메이션**: 녹음 중 입력 진폭을 아름다운 실시간 파형 그래프로 시각화
- **녹음 중 전화 차단 (선택 기능)**: 녹음 도중 전화 벨소리로 녹음이 중단되지 않도록 수신 전화를 자동으로 거절

### 3. 직관적인 모달 플레이어 & A-B 구간 반복
- **중앙 모달 플레이어 팝업**:
  - 시스템 하단 내비게이션 바와 겹치지 않는 쾌적한 중앙 다이얼로그
  - 집중도를 높여주는 부드러운 반투명(Dimmed) 배경
  - 멈춤(일시정지) 및 이어서 재생 지원
  - 터치/드래그로 원하는 위치로 즉시 이동하는 반응형 시크바(슬라이더)
- **A-B 구간 반복 (A-B Repeat) 기능**:
  - 원하는 시작 지점(A)과 종료 지점(B)을 설정하여 해당 구간을 무한 반복 청취
  - 어학 공부, 강의 복습, 회의록 정리 시 핵심 구간 집중 청취에 최적화

### 4. 파일 관리 및 안전한 저장
- **원터치 'Download 폴더로 내보내기'**:
  - 최신 Scoped Storage(MediaStore) 정책을 준수하여 공용 `Download/` 폴더로 바로 복사
  - 스마트폰의 기본 '파일(Files)' 앱을 열자마자 첫 화면(Downloads)에서 녹음 파일을 바로 확인, 공유, PC 백업 가능
- **카테고리 관리**: 강의, 회의, 일상 등 카테고리를 자유롭게 생성하고 파일 분류
- **휴지통 및 완전 삭제**:
  - 실수로 지운 파일은 휴지통에서 즉시 복원 가능
  - '완전 삭제' 또는 '휴지통 비우기' 실행 시 기기 내부 디스크에서 실제 오디오 파일을 즉시 영구 삭제하여 저장 공간 완벽 확보

### 5. 완벽한 백그라운드 안정성 및 프라이버시
- **포그라운드 서비스(Foreground Service)**: 화면이 꺼지거나 다른 앱을 사용하는 중에도 녹음 및 재생이 강제 종료되지 않고 유지
- **100% 온디바이스 개인정보 보호**:
  - 인터넷 권한(`INTERNET`)을 일절 요청하지 않음
  - 어떠한 광고 SDK나 외부 분석 트래커도 미포함
  - 모든 음성 데이터와 설정은 사용자의 스마트폰 로컬에만 안전하게 보관

---

## 🛠️ 기술 스택 및 아키텍처

- **Language**: Kotlin 2.x
- **Build System**: Gradle 9.x / AGP 9.5+ (AGP 10.0 완벽 호환, `android.builtInKotlin=true`, `android.newDsl=true`)
- **UI Toolkit**: Jetpack Compose, Material 3
- **Architecture**: MVVM + StateFlow 반응형 아키텍처
- **Media Engine**:
  - Recording: Android `MediaRecorder`
  - Playback: Android `MediaPlayer` + `PlaybackService`
  - MP3 Encoding: `AudioConverter` (MediaCodec AAC Decoder + Pure Java LAME `jump3r` Encoder, 16 KB Page-size 완벽 호환)
- **Storage**:
  - Settings: Android Jetpack DataStore Preferences
  - Audio Files: App-specific Sandbox Storage + MediaStore Downloads Provider

---

## 📋 권한 안내

| 권한 명칭 | 구분 | 사용 목적 |
| :--- | :--- | :--- |
| `RECORD_AUDIO` | 필수 | 상단/하단 마이크를 통한 고음질 음성 녹음 |
| `FOREGROUND_SERVICE` 계열 | 필수 | 화면 꺼짐 시에도 백그라운드 녹음/재생 서비스 유지 |
| `POST_NOTIFICATIONS` | 선택 (Android 13+) | 백그라운드 녹음 및 재생 상태 알림바 컨트롤 제공 |
| `READ_PHONE_STATE` / `ANSWER_PHONE_CALLS` | 선택 | '녹음 중 전화 차단' 기능 사용 시 수신 전화 자동 거절 |
| `BLUETOOTH_CONNECT` | 선택 | 블루투스 무선 마이크 연결 녹음 지원 |
| `WAKE_LOCK` | 필수 | 녹음 중 CPU 절전 모드로 인한 녹음 끊김 방지 |
| `READ/WRITE_EXTERNAL_STORAGE` | 하위 호환 | Android 9 이하 구형 OS 호환용 |

---

## 🏗️ 프로젝트 구조

```
com.krdonon.microphone/
├── compliance/          # 구글 플레이 연령 신호(Age Signals) 준수
├── data/
│   ├── model/           # 데이터 모델 (AppSettings, RecordingFile, Category 등)
│   └── repository/      # 저장소 (RecordingRepository, SettingsRepository, CategoryRepository)
├── service/             # 백그라운드 서비스 및 상태 매니저
│   ├── RecordingService.kt        # 녹음 포그라운드 서비스
│   ├── RecordingStateManager.kt   # 녹음 실시간 상태 (진폭, 경과시간)
│   ├── PlaybackService.kt         # 재생 포그라운드 서비스
│   ├── PlaybackStateManager.kt    # 재생 실시간 상태 (위치, 길이, A-B 구간 반복)
│   └── PhoneStateReceiver.kt      # 전화 차단 리시버
├── ui/
│   ├── screens/         # Compose 화면 UI
│   │   ├── HomeScreen.kt          # 메인 녹음 목록 화면
│   │   ├── RecordingScreen.kt     # 녹음 진행 및 저장 화면
│   │   ├── SettingsScreen.kt      # 설정 화면
│   │   ├── CategoryManagementScreen.kt # 카테고리 관리 화면
│   │   └── AudioPlayerPopup.kt    # 중앙 모달 오디오 플레이어 (A-B 반복)
│   └── theme/           # Compose 테마, 색상, 타이포그래피
└── utils/               # 유틸리티
    ├── AudioRecorder.kt # MediaRecorder 래퍼
    ├── CacheCleaner.kt  # 임시 캐시 자동 정리
    └── mp3/
        └── AudioConverter.kt # AAC -> Pure Java LAME MP3 변환기
```

---

## 📱 시스템 요구사항

- **최소 SDK (minSdk)**: Android 8.0 (API 26)
- **타겟 SDK (targetSdk)**: Android 15 / 16 (API 37)
- **컴파일 SDK (compileSdk)**: API 37
- **Java 호환성**: Java 11

---

## 👨‍💻 개발 및 라이선스

- **개발자**: krdonon (상단 마이크 개발팀)
- **문의 전자우편**: `don4444@duck.com`
- 본 프로젝트는 순수 온디바이스 환경에서 최고의 안정성과 보안을 제공하는 것을 목표로 합니다.
