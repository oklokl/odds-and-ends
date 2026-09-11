# 상단 마이크 - Android 녹음 앱

## 📱 앱 소개
상단/하단 마이크를 선택하여 녹음할 수 있는 Android 녹음 앱입니다.

## 🎯 주요 기능

### 핵심 기능
- **마이크 위치 선택**: 상단 또는 하단 마이크 선택 가능
- **고품질 녹음**: 음질 선택 (고/중/저음질)
- **백그라운드 녹음**: 화면이 꺼져도 녹음 지속
- **알림바 컨트롤**: 알림바에서 녹음/재생 제어

### 녹음 기능
- M4A/MP3 포맷 지원
- 스테레오/모노 녹음 선택
- 실시간 오디오 진폭 표시
- 일시정지/재개 기능
- **녹음 중 전화 자동 차단**: 녹음 시 방해받지 않도록 수신 전화를 자동으로 거절합니다.

### 파일 관리
- **직관적인 조작**: 짧게 클릭하여 파일을 재생하고, 길게 눌러 이름 변경, 삭제 등 메뉴를 엽니다.
- 카테고리별 파일 분류
- 파일 이름 변경
- 폴더 이동
- 휴지통 기능
- 저장 위치 선택 (내장/SD카드/OTG)

### 재생 기능
- 백그라운드 재생
- **자동 다음 파일 재생**: 현재 파일 재생이 끝나면 자동으로 다음 파일을 재생합니다.
- 알림바 재생 컨트롤

## 🛠️ 기술 스택

- **언어**: Kotlin
- **UI**: Jetpack Compose
- **아키텍처**: MVVM
- **의존성 주입**: Manual DI
- **로컬 저장소**: DataStore, File System
- **미디어**: MediaRecorder, MediaPlayer, Media3

## 📋 필요한 권한

- `RECORD_AUDIO`: 마이크 녹음
- `WRITE_EXTERNAL_STORAGE`: 파일 저장 (Android 9 이하)
- `READ_EXTERNAL_STORAGE`: 파일 읽기 (Android 12 이하)
- `POST_NOTIFICATIONS`: 알림 표시 (Android 13+)
- `FOREGROUND_SERVICE`: 백그라운드 서비스
- `FOREGROUND_SERVICE_MICROPHONE`: 마이크 포그라운드 서비스
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: 미디어 재생 서비스
- `READ_PHONE_STATE`: 전화 상태 감지 (전화 차단 기능)
- `ANSWER_PHONE_CALLS`: 수신 전화 제어 (전화 차단 기능)
- `BLUETOOTH_CONNECT`: 블루투스 마이크 연결 (Android 12+)

## 🏗️ 프로젝트 구조

```
com.krdonon.microphone/
├── data/
│   ├── model/           # 데이터 모델
│   └── repository/      # 데이터 저장소
├── service/            # 백그라운드 서비스
├── ui/
│   ├── screens/        # 화면 UI
│   └── theme/          # 테마 설정
└── utils/              # 유틸리티 클래스
```

## 🚀 빌드 방법

1. Android Studio를 실행합니다
2. "Open an Existing Project"로 프로젝트를 엽니다
3. Gradle 동기화가 완료될 때까지 기다립니다
4. 실제 Android 기기 또는 에뮬레이터를 연결합니다
5. Run 버튼을 클릭합니다

## 📱 최소 요구사항

- **최소 SDK**: Android 8.0 (API 26)
- **타겟 SDK**: Android 14 (API 36)
- **권장 기기**: 실제 Android 기기 (마이크 테스트를 위해)

## 📁 저장 위치

녹음 파일은 다음 위치에 저장됩니다:
- **내장 저장소**: `/Music/krdondon_mic/`
- **SD 카드**: `<SD카드>/krdondon_mic/`
- **OTG**: `<OTG>/krdondon_mic/`

## 🔧 주요 설정

### 음질 설정
- **고음질**: 256kbps, 48kHz
- **중간**: 128kbps, 48kHz (기본값)
- **저음질**: 64kbps, 48kHz

### 오디오 포맷
- **M4A** (기본값)
- **MP3**

### 마이크 위치
- **상단 마이크**: 전면 카메라 근처 마이크 사용
- **하단 마이크**: 충전 포트 근처 마이크 사용 (기본값)

## ⚠️ 주의사항

1. **마이크 선택 기능**: Android의 MediaRecorder는 명시적으로 상단/하단 마이크를 선택하는 API를 제공하지 않습니다. 이 앱은 `AudioSource.CAMCORDER` (상단)와 `AudioSource.MIC` (하단)을 사용하여 근사적으로 구현했습니다.

2. **권한**: Android 13 이상에서는 알림 권한을, 전화 차단 기능을 사용하려면 전화 관련 권한을 별도로 요청해야 합니다.

3. **저장 공간**: 장시간 녹음 시 충분한 저장 공간이 필요합니다.

4. **배터리**: 백그라운드 녹음은 배터리를 소모합니다.

## 📄 라이선스

이 프로젝트는 개인 프로젝트입니다.

## 👨‍💻 개발자

krdonon

## 📞 문의

버그 리포트나 기능 제안은 GitHub Issues를 통해 알려주세요.
