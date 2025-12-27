# AM PM 요일 위젯 앱

## 📱 앱 설명
홈 화면에 아이콘 크기(1x1)로 배치할 수 있는 시간/날짜 위젯입니다.

## ✨ 주요 기능
- **작은 크기**: 1x1 셀 크기 (74x74dp) - 일반 앱 아이콘과 같은 크기
- **실시간 업데이트**: 매 1분마다 자동 갱신
- **배경색**: #cfffe5 (연한 민트색)
- **표시 내용**:
  - 상단: AM/PM + 시간 (예: AM 8:10)
  - 하단: 날짜 + 요일 (예: 12.08 월)

## 📂 프로젝트 구조

```
app/
├── src/main/
│   ├── java/com/krdondon/ampmwidget/
│   │   ├── MainActivity.kt              # 메인 액티비티
│   │   ├── TimeWidgetProvider.kt        # 위젯 프로바이더
│   │   ├── WidgetUpdateService.kt       # 매분 업데이트 서비스
│   │   └── ui/theme/
│   │       └── Theme.kt                 # 테마 설정
│   ├── res/
│   │   ├── layout/
│   │   │   └── widget_layout.xml        # 위젯 레이아웃
│   │   ├── xml/
│   │   │   └── widget_info.xml          # 위젯 정보
│   │   └── values/
│   │       └── strings.xml              # 문자열 리소스
│   └── AndroidManifest.xml              # 앱 설정
└── build.gradle.kts                      # 빌드 설정
```

## 🔧 설치 방법

### 1. Android Studio에서 프로젝트 열기
- Android Studio 실행
- "Open an Existing Project" 선택
- 이 프로젝트 폴더 선택

### 2. 빌드 및 실행
- 메뉴: Build → Make Project
- 에뮬레이터나 실제 기기 연결
- Run 버튼 클릭

### 3. 위젯 추가 방법
1. 앱 실행 후 "배터리 최적화 제외 설정" 버튼 클릭
2. 배터리 최적화 제외 허용
3. 홈 화면에서 빈 공간 길게 누르기
4. "위젯" 선택
5. "AM PM 요일" 위젯 찾아서 홈 화면에 추가

## 🔋 중요 설정
- **배터리 최적화 제외**: 위젯이 백그라운드에서 정확하게 작동하려면 필수

## 📱 필요한 권한
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: 배터리 최적화 제외
- `FOREGROUND_SERVICE`: 백그라운드 서비스 실행

## 🎨 디자인 사양
- **크기**: 74x74dp (1x1 홈스크린 셀)
- **배경**: #cfffe5
- **폰트**: 기본 sans-serif
- **텍스트 색상**: 검정색 (#000000)
- **시간 텍스트 크기**: 16sp
- **날짜 텍스트 크기**: 12sp

## 🔄 업데이트 주기
- 매 60초(1분)마다 자동 갱신
- 백그라운드 서비스로 구동

## 📌 주의사항
- Android 8.0 (API 26) 이상에서 작동
- 일부 제조사의 배터리 절전 기능에 따라 추가 설정이 필요할 수 있음
- 삼성, 샤오미, 화웨이 등은 별도의 자동 시작 권한 설정 필요

## 🛠️ 기술 스택
- Kotlin
- Jetpack Compose
- Android App Widget
- Background Service
