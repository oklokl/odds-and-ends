# Play Age Signals API 0.0.4 적용 내역

## 적용 기준
- 공식 문서의 활성 버전: `com.google.android.play:age-signals:0.0.4`
- 0.0.4의 2단계 호출 구조 사용
  1. `requestAgeSignalsAccess()`
  2. `AgeSignalsStatus.SHARED`인 경우 `checkAgeSignals()`
- 0.0.4에서 폐지된 `userStatus`는 사용하지 않음

## 변경 파일

### app/build.gradle.kts
- `implementation("com.google.android.play:age-signals:0.0.4")` 추가
- 사용자 프로젝트의 현재 설정에 맞춰 `compileSdk = 37`, `targetSdk = 37`, `versionCode = 11`, `versionName = "11.0"` 유지

### MainActivity.kt
- 앱이 런타임에서 열릴 때 `AgeSignalsCompliance.refresh(this)` 호출
- 위젯/배터리 최적화 기존 기능은 유지

### AgeSignalsCompliance.kt (신규)
- `AgeSignalsManagerFactory.create()`로 Manager 생성
- `AgeSignalsAccessRequest` + 현재 Activity로 접근 상태 요청
- SHARED일 때만 `checkAgeSignals()` 수행
- 18세 미만이 확정되는 구간은 `MINOR`, 18세 이상이 확정되는 구간은 `ADULT`
- 공유 거부/확인 필요/오류/모호한 커스텀 구간은 `UNKNOWN`
- 연령 범위, installId, significant-change 관련 값을 파일/DB에 저장하지 않음

## 현재 앱에서 적용하지 않은 항목

### 광고
현재 프로젝트에는 광고 SDK가 없습니다. 따라서 맞춤형 광고 비활성화 코드도 추가하지 않았습니다.
또한 Play Age Signals API 정책은 해당 API 데이터를 광고/마케팅/타기팅/프로파일링 용도로 사용하는 것을 금지합니다.
향후 광고 SDK를 넣는 경우에는 Families 정책 및 해당 광고 SDK의 child-directed / non-personalized 처리 방식을 별도로 적용해야 합니다.

### 인앱 결제
현재 프로젝트에는 Play Billing 또는 인앱 결제가 없습니다. 따라서 결제 전 보호자 승인 코드는 추가하지 않았습니다.
Play Age Signals 0.0.4에는 `isParentalConsentGiven()` 같은 결제별 승인 API가 없습니다.
`significantChangeStatus`는 앱의 "중대한 변경(significant change)"에 대한 보호자 승인 상태이며, 개별 구매 승인 상태가 아닙니다.
Google Play는 적용 대상 미국 주의 supervised user에 대해 다운로드와 구매 승인에 기존 parental controls를 사용합니다.

## 개인정보 최소화
Age Signals 결과는 현재 앱에서 메모리 상태로만 사용하며 로그, SharedPreferences, DB, 파일, 분석 SDK로 전송하지 않습니다.
