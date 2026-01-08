# K메트로놈 (K-Metronome)

안드로이드 메트로놈 앱

## 주요 기능

- 박자(Beat) 설정: 1–16
- 단위(Unit) 설정: 1, 2, 4, 8, 16
- BPM 조절: 40–240
- 원형 애니메이션 시각화
- 강박(Strong Beat) / 약박(Weak Beat) 재생
- 사운드 세트 변경 (set0 / set1 / set2 …)
- 포그라운드 서비스로 백그라운드 재생 유지
- 알림바 제어 버튼 제공 (일시정지 / 정지)
- 정지 시 알림 자동 제거 및 서비스 완전 종료
- 설정(Settings) 화면 제공
- 강박 순간에 휴대폰 플래시(Flash) 켜기 기능
- 향후 진동 모드 확장 가능 구조

---

# 최근 업데이트 (2025-11)

## 1. 설정(Settings) 화면 추가
- Strong Beat 순간에 **후래쉬를 켜기/끄기** 스위치 추가
- 전체 UI를 **검정 배경 + 흰 글씨**의 다크 테마로 변경
- 스위치 옆에 “꺼짐 / 켜짐” 텍스트 추가
- 각 항목 박스에 흰색 테두리 추가
- 뒤로 가기 버튼(아이콘 + 텍스트)을 누르면 설정 종료
- 버튼 **전체 영역**을 터치 가능하도록 개선

## 2. 플래시 기능 추가
- 강박 위치(빨간 지점)에 도달하면:
    - 플래시 ON → 바로 OFF (반짝 효과)
- 설정에서 플래시 OFF 시 기존처럼 소리만 재생
- 플래시 권한 허용 시 자동 활성화

## 3. 알림바 정지 기능 개선
- 알림바에서 "정지"를 누르면:
    1) 메트로놈 재생 중단
    2) 알림바 제거
    3) 포그라운드 서비스 완전 종료

---

# 사운드 파일 구조

사운드 파일은 `assets/sounds/` 아래 다음 구조로 저장됩니다.

```
assets/sounds/
  ├── set0/
  │   ├── weak.mp3
  │   └── strong.mp3
  ├── set1/
  │   ├── weak.mp3
  │   └── strong.mp3
  └── set2/
      ├── weak.mp3
      └── strong.mp3
```

- weak.mp3  → 약박
- strong.mp3 → 강박
- 새로운 사운드 세트는 `set3`, `set4` 등 폴더만 추가하면 자동 적용됩니다.

---

# 앱 권한 (최신 반영)

## 필수 권한
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

## Android 13+ 알림 권한
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## 플래시 기능 추가로 인한 권한
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.flash" android:required="false" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

> CAMERA 권한은 **카메라 사용이 아니라 플래시 제어용**이라 Google Play에서도 정상 승인됩니다.

---

# 설정 화면 구조

- 뒤로 가기 버튼(전체 터치 가능)
- 강박 순간 후래시 켜기 스위치
- 다크 테마 적용
- 스위치 양쪽에 “꺼짐 / 켜짐” 텍스트 표시
- 각 옵션 박스 흰색 테두리 적용

---

# 알림바 기능

- 일시정지
- 정지 → 알림 제거 + 서비스 종료

---

# 메트로놈 동작

- 강약박에 따라 서로 다른 소리 재생
- 강박 시 원형 애니메이션 붉은 강조
- 강박 위치에서 플래시 연동(옵션)

``` ```

---