# 백그라운드 실행 차단 및 배터리·성능 관리 점검 보고서

이 문서는 WWeather 앱이 **백그라운드에서 절대 실행되지 않도록 보장**하고, 포그라운드 사용 중에도 **배터리·네트워크·메모리 소모를 최소화**하기 위해 적용된 정책과 구현 내용을 정리합니다.

---

## 1. 백그라운드 실행 완전 차단

| 항목 | 상태 | 적용 내용 |
| :--- | :---: | :--- |
| `PressureCheckReceiver` | **완전 제거** | `AndroidManifest.xml`에서 리시버 선언 자체를 제거했습니다. 또한 `onReceive()` 내부에도 알람 취소 가드 외 모든 네트워크/알림 로직을 제거했습니다. |
| `SettingsActivity` | **Manifest 제거** | 더 이상 사용하지 않는 레거시 설정 화면을 `AndroidManifest.xml`에서 제거했습니다. |
| `AlarmScheduler` | **해제 전용 (최적화)** | 과거 버전에서 등록해 둔 4시간 주기 `RTC_WAKEUP` 알람이 남아있는 기기에서도 앱 실행 시 `AlarmScheduler.reschedule()` → `cancel()`이 호출되어 OS `AlarmManager` 예약과 `settings`의 `alert`/`condition` 플래그를 즉시 해제합니다. 또한 기존 `PendingIntent`가 없는 경우에는 불필요한 `PendingIntent`를 새로 생성하지 않습니다. |
| `POST_NOTIFICATIONS` | **제거 완료** | 백그라운드 알림 기능을 사용하지 않으므로 `AndroidManifest.xml` 및 `MainActivity`의 알림 권한 요청/채널 생성 코드를 제거했습니다. |
| `WorkManager` / `Service` | **미사용** | 백그라운드 서비스, 포그라운드 서비스, WorkManager, JobScheduler를 일절 사용하지 않습니다. |
| 위치 조회 정책 | **전경 1회성 (`ACCESS_COARSE_LOCATION`, `onStop` 시 즉시 중단)** | `ACCESS_BACKGROUND_LOCATION` 및 `ACCESS_FINE_LOCATION` 미사용. 앱 시작(`onCreate`)·복귀(`onResume`) 시 자동으로 위치를 켜지 않으며, 사용자가 **[재접속]**을 누르고 동의한 경우에만 1회성으로 대략적 위치를 조회합니다. 조회 또는 지오코딩 도중 앱이 백그라운드로 이동하면 `onStop()`에서 `cancelCurrentWeatherRequest()`가 실행되어 위치(`CancellationTokenSource`, `CancellationSignal`), 지오코딩(`geocodeJob`), 네트워크 코루틴을 즉시 중단합니다. |
| 폼팩터 호환성 | **제한 해제** | `screenOrientation="sensorPortrait"`를 제거하고 `screen.portrait`, `location`, `compass` 등을 `uses-feature required="false"`로 선언해 전화·태블릿·Desktop·Android XR 모두 지원합니다. |

---

## 2. 배터리 및 리소스 최적화

### 위치 조회 및 행정구역 판별 최적화
1. **고속 활성 대략 위치 조회**:
   - `CurrentLocationRequest` (`PRIORITY_BALANCED_POWER_ACCURACY`, 5초 상한)로 Wi-Fi/기지국 기반 현재 위치를 즉시 요청하고, 미응답 시 `NETWORK_PROVIDER`(3.5초 상한) → 최근 위치(`lastLocation`) 순으로 폴백합니다. (과거 12초+8초+8초 직렬 지연 제거)
   - `GPS_PROVIDER` 위성 폴링을 사용하지 않아 실내 배터리 소모 및 긴 대기가 발생하지 않습니다.
2. **지오코딩 타임아웃 보호**:
   - `resolveAreaName()`의 `Geocoder` 호출은 `Dispatchers.IO`에서 최대 `1,500ms` 타임아웃으로 제한하고, 코루틴 취소(`CancellationException`)를 정상 전파해 화면 이탈 시 잔여 작업이 남지 않습니다.

### 네트워크 / API 최적화
1. **기상청 AWS 중복 요청 통합**:
   - 기존에는 기압/기온 조회(`fetchPressure`)와 강수 조회(`fetchRainToday`)가 동일한 `nph-aws2_min` URL을 2번 동시에 호출했습니다.
   - 이를 `fetchKmaAws()` **단일 HTTP 요청**으로 통합하여 기상청 API 호출량, 모바일 데이터, 배터리 소모를 절감하고 동일 캐시 파일(`nph-aws2_min.cache`) 동시 쓰기 경합(Race Condition)을 제거했습니다.
2. **OkHttp ConnectionPool / ThreadPool 공유**:
   - `MainActivity.client`와 `Aws2Ww1Client`가 별도의 `OkHttpClient` 인스턴스(별도 스레드 풀·소켓 풀)를 만들지 않고, `client.newBuilder()`로 연결 풀과 디스패처를 공유하면서 타임아웃만 용도에 맞게 설정했습니다.
3. **관측소 일치 캐시 검증**:
   - `aws_cache_stn`을 함께 저장하여 현재 조회하려는 관측소와 캐시된 관측소가 다를 경우 오래된/타 지역 캐시를 잘못 표시하지 않습니다.

### UI / 센서 / 생명주기 관리
1. **시계 갱신 타이머 (`timeTicker`)**:
   - 매초 갱신하지 않고 다음 분(`60_000L - (now % 60_000L)`) 경계에 맞춰 1분 간격으로 갱신하며, `onPause()`에서 즉시 콜백을 제거합니다.
2. **핸들러 및 코루틴 누수 방지**:
   - `MainActivity.onDestroy()`에서 `cancelActiveLocationRequest()`, `timeHandler.removeCallbacksAndMessages(null)` 및 모든 코루틴(`awsJob`, `openMeteoJob`, `geocodeJob`, `ww1PrimaryJob`, `ww1RetryJob`)을 취소합니다.
3. **재접속 버튼 테두리 애니메이션 (`attentionAnimator`)**:
   - `onPause()` 시 `pause()`, `onResume()` 시 `resume()`, `onDestroy()` 시 `cancel()`하여 화면이 가려지거나 백그라운드로 이동하면 렌더링/CPU를 전혀 쓰지 않습니다.
4. **풍향계 나침판 센서 (`WindCompassDialogFragment`)**:
   - `onResume()`에서만 `Sensor.TYPE_ROTATION_VECTOR`를 등록하고 `onPause()`에서 즉시 `unregisterListener()`를 호출합니다.
5. **화면 캡처 비트맵 메모리 해제**:
   - 캡처 크기를 최대 `12_000_000` 픽셀로 제한하고, 시스템 바 인셋 여백을 제외한 깔끔한 여백(`18dp`)만 포함해 캡처하며, Downloads 저장 직후 `finally` 블록에서 `bitmap.recycle()`을 즉시 호출해 메모리 점유를 최소화합니다.
   - API 29 이상 MediaStore 저장 실패 시 `resolver.delete(uri, null, null)`로 0바이트 고아 파일을 정리합니다.
