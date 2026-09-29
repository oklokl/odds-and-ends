# AM PM 요일 위젯 & 상태바 알림 앱 (Week)

## 📱 앱 설명
홈 화면에 **아이콘 크기(1x1)** 로 배치할 수 있는 **시간/날짜/요일 위젯**과, 스마트폰 최상단 **상태바(상태표시줄) 및 알림창**에 현재 **오전/오후** 및 **오늘 요일**을 한눈에 확인할 수 있는 상주 아이콘 표시 기능을 제공합니다.

이 프로젝트는 배터리 소모를 0% 수준으로 억제하면서도 정밀한 갱신과 상시 유지를 지원하도록 설계되었습니다.

---

## ✨ 주요 기능

### 1. 홈 화면 위젯
- **작은 크기**: 1x1 셀 크기 (약 74x74dp 수준)
- **표시 내용**:
  - 상단: AM/PM + 시간 (예: AM 8:10)
  - 하단: 날짜 + 요일 (예: 01.02 금)
- **배경색**: `#cfffe5` (연한 민트색)
- **수동 동기화(강제 갱신)**:
  - 위젯을 탭하면 앱(MainActivity)으로 진입하며, 진입과 동시에 위젯을 강제 갱신하여 런처 환경에 따른 간헐적 갱신 누락을 방지합니다.

### 2. 상태바(상태표시줄) & 알림창 알림 (신규)
- **독립 상태바 아이콘 노출**:
  - 스마트폰 최상단 상태바에 **[오전] / [오후]** 및 **[월] ~ [일]** 한글 벡터 아이콘을 독립적으로 노출
  - 두 기능을 모두 켜면 상태바에 **[오전] [금]** 2개의 아이콘이 나란히 표시됨
- **간편한 토글 제어**:
  - 앱 메인 화면 하단에서 **[오전·오후]** 및 **[요일]** 버튼을 각각 켜고 끌 수 있음 (활성화 시 "해제" 버튼으로 전환)
- **상시 유지 (`setOngoing(true)`)**:
  - 사용자가 알림창에서 '모두 지우기'를 눌러도 지워지지 않고 상시 유지
  - 알림 카드 터치 시 앱 메인 화면으로 이동
- **안내 문구**:
  - 알림 내용에 *"요일 앱입니다. 해제하려면 요일 앱에서 하세요."* 표시

### 3. 배터리 소모 0% 하이브리드 갱신 구조
- **CPU 점유 0% 대기**:
  - 지속적인 백그라운드 서비스(Foreground Service)를 구동하지 않아 평소 배터리와 CPU 소모가 없습니다.
- **AlarmManager 정밀 갱신**:
  - 오전/오후와 요일이 바뀌는 **낮 12:00** 및 **밤 12:00(자정)**에만 시스템 알람으로 깨어나 1ms 내로 아이콘과 텍스트를 즉시 갱신하고 다음 12시간 뒤 알람을 재설정합니다.
- **WorkManager 이중 안전장치**:
  - 장시간 기기를 사용하지 않거나 앱이 닫혀 있어도 4시간 주기로 알림 상태를 점검하고 복구합니다.
- **시스템 이벤트 자동 복구**:
  - 기기 재부팅(`BOOT_COMPLETED`), 날짜/시간 수동 변경, 타임존 변경 시에도 자동으로 즉시 알림 상태를 복구합니다.
- **알림 자동 묶임 방지 및 상시 노출 최적화**:
  - 전용 채널 분리(`channel_status_am_pm_v2`, `channel_status_day_v2`) 및 고유 그룹 키 적용으로 OS에 의한 자동 카드 묶음을 방지합니다.
  - 무음 알림으로 인한 상태바 아이콘 숨김 현상을 방지하기 위해 `IMPORTANCE_DEFAULT` 무음 채널(소리/진동 완전 무음)을 적용하여 상태바에 아이콘이 100% 확실하게 표시됩니다.

---

## 📂 프로젝트 구조

```
app/
├── src/main/
│   ├── java/com/krdondon/week/
│   │   ├── MainActivity.kt                      # 메인 액티비티 (UI, 배터리 최적화, 상태바 알림 토글)
│   │   ├── TimeWidgetProvider.kt                # 위젯 프로바이더 (AppWidgetProvider)
│   │   ├── AgeSignalsCompliance.kt              # Google Play Age Signals API 연동
│   │   ├── notification/                        # 상태바 알림 모듈 (신규)
│   │   │   ├── StatusNotificationManager.kt     # 알림 생성/채널 관리/AlarmManager 스케줄러
│   │   │   ├── StatusNotificationPrefs.kt       # 알림 활성화 상태 SharedPreferences 관리
│   │   │   ├── StatusNotificationReceiver.kt    # 재부팅/시간변경/알람 수신 브로드캐스트 리시버
│   │   │   └── StatusNotificationWorker.kt      # WorkManager 기반 주기적 알림 점검 워커
│   │   └── ui/theme/                            # 테마 및 Compose 스타일링
│   ├── res/
│   │   ├── drawable/
│   │   │   ├── ic_stat_am.xml                   # 상태바 '오전' 벡터 아이콘
│   │   │   ├── ic_stat_pm.xml                   # 상태바 '오후' 벡터 아이콘
│   │   │   └── ic_stat_mon.xml ~ sun.xml        # 상태바 '월' ~ '일' 요일별 벡터 아이콘
│   │   ├── layout/
│   │   │   └── widget_layout.xml                # 위젯 레이아웃 (RemoteViews)
│   │   ├── xml/
│   │   │   └── widget_info.xml                  # 위젯 정보 (AppWidgetProviderInfo)
│   │   └── values/
│   │       └── strings.xml                      # 문자열 리소스 (UTF-8 인코딩 안전 관리)
│   └── AndroidManifest.xml                      # 앱/리시버/권한 설정
└── build.gradle.kts                              # AGP 9.x / 10.0 대응 빌드 설정
```

---

## 📱 필요한 권한

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  - 위젯 및 백그라운드 갱신 누락 방지를 위한 배터리 최적화 제외 요청 권한
- `POST_NOTIFICATIONS` (Android 13+ / API 33+)
  - 상태바 및 알림창에 오전/오후 및 요일 알림을 표시하기 위한 권한
- `RECEIVE_BOOT_COMPLETED`
  - 스마트폰 재부팅 시 설정해 둔 상태바 알림을 자동 복구하기 위한 권한

---

## 🔧 최신 빌드 환경 & AGP 10.0 호환성

- **Android Gradle Plugin (AGP)**: 9.x 지원 및 향후 AGP 10.0 대비 완료
  - 레거시 internal DSL(`BaseAppModuleExtension` 등) 대신 최신 공개 API인 `ApplicationExtension` 전면 적용
  - 임시 우회 플래그(`android.newDsl=false`, `android.builtInKotlin=false`)를 제거하고 **New DSL** 및 **AGP Built-in Kotlin** 적용
- **문자열 인코딩 안전성**:
  - 윈도우(Windows) 컴파일 환경에서의 한글 깨짐을 방지하기 위해 모든 UI 텍스트를 `strings.xml` 기반으로 리소스화하여 UTF-8 안전성을 확보

---

## 🧩 사용 방법

### 1) 홈 화면 위젯 추가
1. 앱 실행 후 (권장) **배터리 최적화 제외 설정** 진행
2. 홈 화면 빈 공간 길게 누르기 → **위젯** 선택
3. **AM PM 요일** 위젯을 홈 화면에 추가

### 2) 상태바 알림 활성화
1. 메인 화면 하단의 **상태바 알림 설정** 영역 확인
2. **[오전·오후]** 또는 **[요일]** 버튼 클릭
   - Android 13 이상에서는 알림 권한 허용 팝업 시 "허용" 선택
3. 스마트폰 최상단 상태표시줄에 해당 아이콘이 즉시 표시됩니다.
4. 해제하고 싶을 때는 메인 화면에서 변경된 **[오전·오후 해제]** 또는 **[요일 해제]** 버튼을 누르면 즉시 사라집니다.

---

## 🛠️ 기술 스택
- **Language**: Kotlin (AGP 9.x Built-in Kotlin)
- **UI Toolkit**: Jetpack Compose (Material 3)
- **Widget**: Android AppWidget (RemoteViews)
- **Notification & Scheduling**:
  - Android Notifications (Ongoing, Custom Vector Icons, Importance Management)
  - AlarmManager (`setAndAllowWhileIdle`)
  - WorkManager (`CoroutineWorker`, `PeriodicWorkRequest`)
- **Compliance**: Google Play Age Signals API (0.0.4)
