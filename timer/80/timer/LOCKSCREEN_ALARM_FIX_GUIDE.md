# 잠금화면 알람 인증 UI 겹침 개선

## 변경 파일
- `app/src/main/java/com/krdonon/timer/alarm/AlarmActivity.kt`
- `app/src/main/java/com/krdonon/timer/alarm/AlarmAgainActivity.kt`

## 변경 내용
1. 알람 Activity 시작 시 `KeyguardManager.requestDismissKeyguard()`를 호출하지 않도록 변경했습니다.
2. Android 8.0(API 26) 폴백에서 `FLAG_DISMISS_KEYGUARD`를 제거했습니다.
3. `showWhenLocked` / `turnScreenOn` 동작은 유지하여 잠금 상태에서도 알람 화면은 정상적으로 표시되고 화면도 켜집니다.
4. 알람을 해제하면 Activity만 종료되고 기기 잠금은 유지되므로, 이후 시스템 잠금화면에서 지문/패턴 인증을 정상적으로 진행합니다.

## 의도한 흐름
잠금 상태 → 타이머 울림 → 알람 화면 표시(잠금 유지) → 사용자가 알람 해제 → 시스템 잠금화면 복귀 → 지문/패턴 인증

## 참고
지문/패턴 UI는 Android System UI가 관리하므로 제조사별 표현 차이는 있을 수 있습니다. 앱이 잠금 해제를 직접 요청하지 않도록 하여 인증 UI와 알람 Activity가 동시에 경쟁하는 원인을 제거하는 방식입니다.
