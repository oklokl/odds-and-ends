# WWeather (허리 날씨 앱)

기압(방재기상관측, AWS), 강수, 현재 온도, 풍향/풍속/습도, 현천(WW1 / WMO) 정보를 보여주는 Android 앱입니다.  
기상청 APIHub(typ01 `nph-aws2_min`, `nph-aws2_min_ww1`) 및 Open-Meteo API를 선택하여 사용할 수 있으며, **백그라운드에서는 일절 실행되지 않고** 사용자가 화면에서 직접 조회를 요청할 때만 전경(Foreground)에서 동작합니다.

---

## 현재 주요 기능

### 1) 온디맨드(수동 시작) 날씨 조회 & 1회성 위치 동의
- 앱 시작 시 자동으로 위치 권한을 요청하거나 GPS/네트워크를 호출하지 않고 대기 상태로 시작합니다.
- **[재접속]** 버튼을 누르면:
  - 선택/고정된 지역이 있는 경우: GPS 조회 없이 해당 지역 기준으로 즉시 조회합니다.
  - 선택/고정된 지역이 없는 경우: 사용자 동의 다이얼로그를 표시하고, 동의 시 이번 조회에 한해 대략적인 위치(`ACCESS_COARSE_LOCATION`)를 1회 확인합니다.
- 해외 좌표가 감지된 경우(예: 해외 에뮬레이터 GPS) 기상청 국내 관측소(독도 등)로 오매칭하지 않도록 차단하며, 국내 지역 수동 선택 또는 Open-Meteo 전환을 안내합니다.

### 2) 날씨 제공처 전환 (기상청 ↔ 오픈메타)
- **[오픈메타 / 기상청]** 전환 버튼으로 데이터 제공처를 실시간으로 전환할 수 있습니다.
  - **기상청 (`KMA`)**: 방재기상관측소(`StationRepo`) 기준 `nph-aws2_min`(기압·온도·풍향·풍속·습도·강수) 및 `nph-aws2_min_ww1`(지리적으로 가장 가까운 현천 관측소) 데이터를 조회합니다.
  - **오픈메타 (`Open-Meteo`)**: `korea_regions.csv` 행정구역 좌표 또는 현재 위치 좌표를 기준으로 `api.open-meteo.com` 모델 날씨와 WMO 코드를 조회합니다. (해외 위치에서도 조회 가능)

### 3) 수동 지역 선택 · 검색 · 고정 · 초기화 (`[지역 열기]`)
- **[지역 열기]** 버튼으로 지역 선택 다이얼로그를 열 수 있습니다 (기상청 모드: AWS 관측소 목록 / Open-Meteo 모드: `korea_regions.csv` 시군구 목록).
- **검색**: 부분 문자열 검색으로 지역 목록을 실시간 필터링합니다.
- **버튼 구성**:
  - **검색**: 입력한 검색어로 목록 필터링
  - **나가기**: 다이얼로그 닫기
  - **초기화**: 선택/고정 및 이전 위치 정보를 해제하고 초기 위치 기반 모드로 복귀
  - **고정**: 선택한 지역을 영구 저장하여 앱을 다시 실행해도 해당 지역이 유지됨
  - **확인**: 현재 앱 실행 세션 동안 선택한 지역으로 즉시 조회 (앱 재실행 시 초기화됨)

### 4) 기압 상태 · 강수 · 현천 · 기온 · 풍향/풍속/습도 표시
- **기압 상태 분류**: `1010.00 hPa` 미만(아픔 · 나쁨), `1010.00 ~ 1016.00 hPa`(보통 · 정상), `1016.00 hPa` 초과(안아픔 · 좋음)로 이모지 및 문구를 표시합니다.
- **강수 판정**:
  - 기상청 모드: `RE >= 0`이면 `RE > 0`으로 비 유무를 판단하고, `RE`가 결측 음수(예: `-99.9`)인 경우 `RN-15m > 0`으로 대체 판정합니다. (60분 이상 지난 오래된 캐시는 '확인 불가'로 보호)
  - Open-Meteo 모드: `snowfall > 0` 또는 눈 WMO 코드(지금 눈 옴), `rain > 0` 또는 비 WMO 코드(지금 비 옴), 기타 강수량 기반 판정.
- **현천 코드 & 범례**: 하단 가로 스크롤 범례로 현천 코드(KMA) / WMO 코드(Open-Meteo)를 확인할 수 있습니다.
- **오프라인 캐시 보호**: 기상청 `nph-aws2_min.cache`는 마지막 성공 관측소 번호(`aws_cache_stn`)가 일치할 때만 폴백으로 사용하여 타 지역 캐시 오표시를 방지합니다.

### 5) 풍향 UI (풍향계 & 실시간 나침판)
- **[풍향 UI]** 버튼을 누르면 `WindCompassDialogFragment` 팝업이 열립니다.
- 상단 풍향계(`WindVaneView`)와 하단 실시간 나침판(`CompassView`, `TYPE_ROTATION_VECTOR` 센서 + 화면 회전 좌표계 보정)을 함께 제공합니다.
- 다이얼로그가 일시정지(`onPause`)되면 센서 리스너를 즉시 해제합니다.

### 6) 화면 이미지 저장 / 공유 & 진단 로그
- **[이미지 저장]**: 현재 스크롤 화면 전체를 PNG로 렌더링하여 `Downloads` 폴더에 저장합니다(12MP 상한 스케일링 및 저장 후 즉시 `bitmap.recycle()`).
- **[공유]**: 저장된 최신 PNG 이미지를 다른 앱으로 공유합니다.
- **[로그]**: `AppLog`에 기록된 내부 진단 로그(최대 120KB)를 확인, 복사, 공유합니다. **[재접속]** 시 자동 초기화됩니다.

---

## 백그라운드 비실행 및 배터리 관리 정책

- **백그라운드 실행 없음**: `AlarmManager` 반복 알람 및 `PressureCheckReceiver` 브로드캐스트 리시버는 `AndroidManifest.xml`에서 비활성화(`android:enabled="false"`)되어 있으며, 앱 시작 시 기존 예약 알람이 있더라도 모두 해제(`AlarmScheduler.cancel`)합니다.
- **자세한 내용**: [BACKGROUND_AND_BATTERY.md](BACKGROUND_AND_BATTERY.md)를 참고하세요.

---

## 빌드 및 환경 설정

- **API 키 설정**: 프로젝트 루트의 `local.properties`에 기상청 APIHub 인증키를 설정합니다(Git 추적 제외).
  ```properties
  KMA_AUTH_KEY=발급받은_기상청_인증키
  ```
  `app/build.gradle.kts`가 이를 읽어 `BuildConfig.KMA_AUTH_KEY`로 주입합니다.
- **SDK 및 기술 스택**: `compileSdk 37` / `targetSdk 37` / `minSdk 24` / Java 17 / Kotlin `2.4.21` / AGP `9.6.0-alpha04` / OkHttp `5.5.1`

---

## 주요 파일 구조

- `app/src/main/java/com/krdonon/wweather/MainActivity.kt` — 메인 화면, 온디맨드 위치/날씨 조회, 지역 선택 다이얼로그, 캡처/공유, 진단 로그
- `app/src/main/java/com/krdonon/wweather/Aws2Ww1Client.kt` — 기상청 `nph-aws2_min_ww1` 전체 관측소 조회 및 최근접 현천 매칭/캐시
- `app/src/main/java/com/krdonon/wweather/OpenMeteoClient.kt` — Open-Meteo 현재 날씨 및 WMO 코드 파서
- `app/src/main/java/com/krdonon/wweather/Station.kt` — 기상청 방재기상관측(AWS) 관측소 좌표 목록 (`StationRepo`)
- `app/src/main/java/com/krdonon/wweather/LocalRegionRepository.kt` — `assets/korea_regions.csv` 행정구역 좌표 로더
- `app/src/main/java/com/krdonon/wweather/WindCompassDialogFragment.kt`, `WindVaneView.kt`, `CompassView.kt` — 풍향계/나침판 팝업 UI
- `app/src/main/java/com/krdonon/wweather/AgeSignalsCompliance.kt` — Google Play Age Signals API (`0.0.6`) 연동
- `app/src/main/java/com/krdonon/wweather/AppLog.kt` — 용량 제한(120KB) 내부 진단 로그 버퍼
- `app/src/main/java/com/krdonon/wweather/AlarmScheduler.kt`, `PressureCheckReceiver.kt` — 레거시 백그라운드 알람 해제/차단 안전장치
- `app/src/main/res/layout/activity_main.xml` — 메인 UI 레이아웃 (`NestedScrollView`)
- `app/src/main/res/layout/dialog_region_picker.xml` — 지역 선택/검색 다이얼로그 레이아웃
