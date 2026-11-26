# Google Play 정책 및 기술 준수 점검 문서 (`targetSdk = 37`)

이 문서는 **허리 날씨 (`com.krdonon.wweather`, versionCode 42 / versionName 42.0)**의 Google Play 스토어 심사 기준(위치 권한, 데이터 보안, 연령 신호, 폼팩터 적응형, Edge-to-Edge) 준수 상태를 코드 기준으로 정리합니다.

---

## 1. 위치 권한 정책 (`targetSdk = 37` / Android 17)

### 적용 원칙: 최소 권한 범위(Minimum Scope)
- **선언 권한**: `android.permission.ACCESS_COARSE_LOCATION`만 선언 (`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` 미선언)
- **사유**: 본 앱은 시/군/구 단위 기상청 관측소·기압·날씨 정보를 제공하는 날씨 앱으로, m 단위 정밀 GPS(`ACCESS_FINE_LOCATION`) 없이 대략적 위치(`ACCESS_COARSE_LOCATION`, 약 2~3km 반경)만으로 핵심 기능을 완전히 수행할 수 있습니다.
- **전경 1회성 조회**:
  - 앱 실행(`onCreate`)·복귀(`onResume`) 시 자동으로 위치를 조회하지 않습니다.
  - 사용자가 **[재접속]** 버튼을 누르고 전용 안내창(`dialog_location_prompt.xml`)에서 **[위치 확인하기]**를 선택한 경우에만 1회 조회합니다.
  - 권한을 거부하거나 위치 서비스가 꺼져 있어도 **[지역 열기]**에서 수동 선택/고정이 가능합니다.
  - `onStop()`(화면 회전 제외) 시 진행 중인 위치·지오코딩·네트워크 요청을 즉시 취소합니다.

> **참고 (`onlyForLocationButton` 검토 결과)**:
> Android 17(`targetSdk = 37`) 정책은 1회성 정밀 위치가 반드시 필요한 경우 시스템 `LocationButton` + `onlyForLocationButton`을 요구하며, `ACCESS_COARSE_LOCATION`으로 지원 가능한 경우에는 `ACCESS_COARSE_LOCATION`을 우선 사용하도록 규정합니다. 따라서 본 날씨 앱은 `ACCESS_COARSE_LOCATION` 단일 권한을 유지하는 것이 정책상 가장 안전하고 적합합니다.

---

## 2. Google Play 데이터 보안(Data Safety) 작성 기준

| 항목 | 실제 코드 동작 | Play Console 입력 가이드 |
| :--- | :--- | :--- |
| **계정/로그인** | 없음 | 계정 생성 없음 |
| **대략적 위치** | 사용자가 [재접속] 동의 시 OS에서 1회 수신 (`lastLocation` 로컬 저장, Open-Meteo 모드 시 모델 좌표로 전송, KMA 모드 시 관측소 번호 `stn`만 전송) | 앱 기능 목적 처리 (오픈메타 모드 좌표 전송 고려 시 대략적 위치 수집/공유 여부 정책 기준에 맞춰 기재, 기기 외 개발자 서버 저장 없음) |
| **정밀 위치** | 미수집 (`ACCESS_FINE_LOCATION` 미선언) | 수집 안 함 |
| **광고/분석 SDK** | 미사용 (`AdMob`, `Firebase Analytics` 등 없음) | 수집 안 함 |
| **암호화 전송** | 기상청 `https://apihub.kma.go.kr`, Open-Meteo `https://api.open-meteo.com` 모두 HTTPS 사용 | 전송 중 데이터 암호화됨 |
| **데이터 삭제** | 개발자 서버 보관 없음, 로컬 데이터는 앱 내 `[초기화]` 또는 OS 앱 데이터 삭제/앱 제거로 즉시 삭제 | 별도 서버 삭제 요청 불필요 |

---

## 3. Google Play Age Signals API (`0.0.6`)
- `AgeSignalsCompliance.kt`에서 Activity 포커스 획득 후 1회 `requestAgeSignalsAccess()` → `SHARED` 시 `checkAgeSignals()` 호출
- 원본 연령은 파일·DB·`SharedPreferences`에 저장하지 않으며 프로세스 메모리에서 `MINOR` / `ADULT` / `UNKNOWN`으로만 분류
- 광고·마케팅·프로파일링에 사용하지 않음

---

## 4. Android 15~17 호환성 & 적응형(Edge-to-Edge / 폼팩터)
- **Edge-to-Edge**: `enableEdgeToEdge(SystemBarStyle.light(...))` + `WindowCompat.getInsetsController`로 밝은 배경에서도 상태바·내비게이션바 아이콘이 어두운 색으로 선명하게 표시되며, `systemBars() or displayCutout()` 4면 인셋을 적용해 시계·배터리·컷아웃 겹침을 방지합니다.
- **폼팩터 지원**: `screenOrientation` 제한 없음, `screen.portrait`/`location`/`compass` 등 `uses-feature android:required="false"` 선언, 화면 회전 시 `onSaveInstanceState` 상태 복원 지원.
- **백그라운드 완전 차단**: `Service`, `WorkManager`, 활성 `BroadcastReceiver` 없음 (`AlarmScheduler.reschedule()`이 과거 알람만 해제).
