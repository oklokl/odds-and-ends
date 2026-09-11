# Google Play 2026 App Quality 대응 변경사항

기준 문서:
- Android Developers Blog (2026-08-26): Elevating app quality: Reducing memory usage and improving device migration
- Play Console technical quality requirements
- Android Developers: Bitmap memory usage
- Android Developers: Enable app optimization with R8

## 이 앱에 적용되는 요구사항

### 1. 메모리 / Bitmap 최적화

`MainActivity.kt`를 수정했습니다.

- 화면 미리보기 이미지는 최대 2048px 기준으로 downsample decode
- 이미지 decode / resize / save를 메인 스레드 밖에서 실행
- 앱이 화면에서 사라질 때 편집 결과 Bitmap 참조 해제
- `onTrimMemory()` 신호에서 transient 편집 Bitmap 해제
- 저장 시 기기 heap 크기, 이미지 크기, 목표 비율을 계산해 예상 작업 메모리가 과도하면 자동으로 inSampleSize 증가
- EXIF + 사용자 회전을 저장 시 원본 URI에서 다시 적용
- 저장 작업에 사용한 임시 Bitmap은 저장 완료 후 즉시 recycle
- Android 10+ MediaStore 저장 시 IS_PENDING 사용

이 구조는 화면 표시를 위해 초고해상도 원본 Bitmap을 계속 들고 있던 기존 방식보다 장시간 Bitmap 메모리 사용량과 OOM 위험을 크게 줄입니다.

### 2. DEX / R8 최적화

`app/build.gradle.kts`와 `gradle.properties`를 수정했습니다.

- release 빌드 `isMinifyEnabled = true`
- release 빌드 `isShrinkResources = true`
- `proguard-android-optimize.txt` 사용 유지
- `android.r8.optimizedResourceShrinking=true`
- `android.r8.strictFullModeForKeepRules=true`
- 실제 코드에서 사용하지 않던 Coil 의존성 제거

이 프로젝트는 현재 `android.builtInKotlin=false`와 `android.newDsl=false`를 사용하고 있으므로 Kotlin Android 플러그인과의 호환성을 유지하기 위해 AGP 9.3의 legacy buildType DSL을 사용했습니다. 기능적으로 R8 코드 최적화/난독화/축소와 리소스 축소를 활성화하는 설정입니다.

실제 Google Play의 최적화 비율(25% 이상)은 최종 AAB를 Play Console에 업로드한 뒤 App Bundle Explorer의 optimization insights에서 확인해야 합니다.

## 이 앱에 적용되지 않는 요구사항

### Zero-Tap Sign-In / Restore Credentials

현재 프로젝트를 검색한 결과 사용자 계정, 로그인, 인증, Credential Manager 관련 기능이 없습니다.

Google Play 문서상 사용자 로그인을 제공하지 않는 앱은 Zero-Tap Sign-In Restoration 요구사항의 적용 대상이 아닙니다. 따라서 Restore Credentials API를 억지로 추가하지 않았습니다.

향후 로그인 기능을 추가한다면 그 시점에 Restore Credentials API를 연동해야 합니다.

## 변경 파일

- `app/src/main/java/com/krdonon/ratio/MainActivity.kt`
- `app/build.gradle.kts`
- `gradle.properties`
- `gradle/libs.versions.toml` (사용하지 않는 Coil 제거)
- `QUALITY_CHANGES_2026.md` (본 문서)

## 이미지 선택 호환성 수정 (2026-08-31)

에뮬레이터/일부 MediaProvider에서 선택한 `content://` URI를 여러 번 `openInputStream()` 하는 과정에서
`이미지 스트림을 열 수 없습니다`가 발생할 수 있던 경로를 수정했습니다.

- `GetContent` 대신 AndroidX `PickVisualMedia` 사용
- 선택한 URI는 즉시 앱 전용 cache 파일로 1회 복사
- URI 입력은 `openInputStream` 실패 시 `openFileDescriptor`, `openTypedAssetFileDescriptor` 순으로 fallback
- Bitmap bounds / preview decode / EXIF / export는 이후 cache 파일에서만 처리
- 새 이미지를 선택하거나 화면이 폐기될 때 임시 cache 파일 삭제
- Photo Picker가 선택한 개별 항목에 접근 권한을 주므로 불필요한 `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE` 선언 제거

이 변경으로 cloud media provider, Android Photo Picker, 파일 공급자, 에뮬레이터 MediaProvider의 URI 재오픈 차이의 영향을 줄였습니다.
