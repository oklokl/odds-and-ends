# Changelog

## 최근 점검 및 개선 사항

### v42.0 — Edge-to-Edge 상태바 수정 · 위치/GPS 안내창 디자인 개선 · 위치 조회/행정구역 판별 최적화
- **상단 상태바 가시성 및 인셋 겹침 해결 (`MainActivity.kt`, `themes.xml`, `AndroidManifest.xml`)**:
  - `enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(...), navigationBarStyle = SystemBarStyle.light(...))` 및 `WindowInsetsControllerCompat`의 `isAppearanceLightStatusBars = true` 적용으로 상단 시계·Wi-Fi·통신·배터리 아이콘 시인성 확보
  - `rootLayout`에 `WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()` 4면 인셋을 적용해 상단 텍스트와 상태바/펀치홀 겹침 해결
  - `Theme.Wweather`(`Theme.MaterialComponents.Light.NoActionBar` 기반 + 고대비 `ThemeOverlay.Wweather.AlertDialog`)를 `AndroidManifest.xml`에 적용해 다크 모드 시 텍스트 대비 깨짐 방지
- **GPS/위치 안내창 및 메인 화면 디자인 개선**:
  - 저대비 연한 녹색 다이얼로그 버튼 대신 전용 레이아웃(`dialog_location_prompt.xml`)과 고대비 에메랄드 녹색(`#1B7A3D`) 버튼·카드형 안내창 적용
  - `activity_main.xml`을 카드형 레이아웃(`bg_weather_card.xml`), 둥근 모서리 이모지 카드(`MaterialCardView`), 통일된 `MaterialButton`, 고대비 타이포그래피(`colors.xml`)로 개편
  - 로딩 다이얼로그(`showLoading`)에 **취소** 버튼 및 뒤로가기 취소 핸들러 추가
- **위치 조회 속도 개선 및 Google Play `targetSdk = 37` 정책 준수**:
  - `targetSdk = 37` 최소 권한 원칙에 맞춰 `ACCESS_COARSE_LOCATION` 전경 1회 조회 유지
  - `CurrentLocationRequest`(`PRIORITY_BALANCED_POWER_ACCURACY`, `setMaxUpdateAgeMillis(0L)`, 5초 상한) → `NETWORK_PROVIDER`(3.5초) → 최근 위치 폴백 구조로 단축해 실기기 약 58ms 활성 위치 수신 확인
  - 시/구청 단일 중심점 거리 매칭 대신 `Geocoder`(1.5초 타임아웃) 우선 행정구역 판별(`resolveAreaName`) + CSV 폴백 적용 및 마지막 행정구역명(`PREF_LAST_AREA`) 저장
- **안정성 및 문서 현행화**:
  - `AlarmScheduler.cancel()`에서 기존 알람이 없을 때 불필요한 `PendingIntent` 생성 제거
  - `saveCurrentScreenImage()`가 상태바/내비게이션바 인셋 공백을 포함하지 않도록 `18dp` 고정 여백 및 `bg_screen` 배경 적용
  - 화면 회전 복원(`restoreUiState`) 시 `시도중…` 상태 고착 방지
  - 한국 법령·Google Play 정책 기준 한글 [개인정보 처리방침](Privacy%20Policy.txt)([PRIVACY_POLICY.md](PRIVACY_POLICY.md)) 및 [GOOGLE_PLAY_POLICY.md](GOOGLE_PLAY_POLICY.md) 최신화

---

### Google Play 정책·폼팩터 호환성 및 백그라운드 완전 차단
- **Play Console 하드웨어 제한 경고(`android.hardware.screen.portrait`) 해결**:
  - `AndroidManifest.xml`에서 `android:screenOrientation="sensorPortrait"` 제거
  - `android.hardware.screen.portrait`, `location`, `location.network`, `location.gps`, `sensor.compass`를 `android:required="false"`로 명시하여 전화·태블릿·Desktop·Android XR 설치 지원
  - 화면 회전 시 표시 중인 날씨 데이터/풍향/공유 URI가 유지되도록 `onSaveInstanceState` / `restoreUiState` 구현
  - 가로 모드 및 태블릿에서 잘리지 않도록 `dialog_region_picker.xml`에 `ScrollView` 적용 및 `WindCompassDialogFragment` 최대 폭 제한
- **백그라운드 실행 및 백그라운드 위치 차단 강화**:
  - `AndroidManifest.xml`에서 미사용 `PressureCheckReceiver`, `SettingsActivity`, `POST_NOTIFICATIONS` 완전 제거
  - `strings.xml`에서 미사용 `background_location_needed` 문자열 제거
  - `MainActivity.onStop()`에서 화면 회전이 아닌 백그라운드 이동 시 진행 중인 위치(`CancellationTokenSource`, `CancellationSignal`) 및 날씨 요청 즉시 취소
  - `AlarmScheduler.reschedule()`이 반복 알람(`RTC_WAKEUP`)을 등록하지 않고 기존 예약 알람 및 설정 플래그를 해제하도록 변경
  - `MainActivity`의 알림 채널 생성 및 알림 권한 요청 제거
- **API 중복 호출 제거 및 캐시 정합성**:
  - `fetchPressure` + `fetchRainToday` 중복 `nph-aws2_min` 요청을 `fetchKmaAws()` 단일 요청으로 통합
  - `nph-aws2_min.cache` 사용 시 관측소 번호(`aws_cache_stn`) 일치 여부 검증 추가
  - `MainActivity`의 `OkHttpClient` 연결 풀/스레드 풀을 `Aws2Ww1Client`와 공유
- **안정성 및 버그 수정**:
  - 지역 선택 다이얼로그에서 **확인(세션 일시 선택)**과 **고정(앱 재실행 후에도 유지)** 동작이 정확히 구분되도록 `onCreate()` 초기화 로직 보완
  - `NumberPicker` 필터링 시 `displayedValues` 교체 전 `value = 0` 초기화로 `ArrayIndexOutOfBoundsException` 방지
  - `MainActivity.onDestroy()`에서 `timeHandler.removeCallbacksAndMessages(null)` 호출로 위치 타임아웃 콜백 누수 차단
  - `showLoading()`에 `isFinishing || isDestroyed` 가드 추가
  - `saveBitmapToDownloads()`에서 MediaStore 저장 실패 시 미완성 `IS_PENDING` 엔트리 정리
  - `startWw1SilentRetry()`에서 `provider == WeatherProvider.KMA` 및 최신 `weatherRequestSeq` 검증 추가
  - 미사용 함수(`applyPinnedSelection`, `reverseGeocodePretty`, `usePressureCacheFallback`) 및 미사용 import 정리

### 의존성 및 빌드 환경 업데이트 (`gradle/libs.versions.toml`)
- `org.jetbrains.kotlin.android`: `2.4.20` → `2.4.21`
- `androidx.core:core-ktx`: `1.19.0` → `1.19.1`
- `androidx.fragment:fragment-ktx`: `1.9.0` → `1.9.1`
- `com.squareup.okhttp3:okhttp`: `5.5.0` → `5.5.1`
- `androidx.lifecycle:lifecycle-runtime-ktx`: `2.11.0` → `2.11.1`
- `com.google.android.play:age-signals`: `0.0.4` → `0.0.6`
