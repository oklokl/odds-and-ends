# Android AVD GPU Mode Fix

Windows 배치 파일로 Android Emulator AVD의 `config.ini` 파일에서  
`hw.gpu.mode=auto` 값을 `hw.gpu.mode=host` 로 변경하는 도구입니다.

## 왜 이 도구를 사용하나요?

일부 Windows 11 환경에서 Android Emulator 실행 시 GPU 설정이 `auto` 일 때
그래픽 관련 오류, 검은 화면, 실행 불안정, 렌더링 문제 등이 발생하는 경우가 있습니다.

이럴 때 `hw.gpu.mode=host` 로 변경하면 문제가 해결되는 경우가 있어,
매번 수동으로 `config.ini` 를 수정하지 않고 한 번에 변경할 수 있도록 만든 배치 파일입니다.

## 무엇을 변경하나요?

아래 항목만 변경합니다.

- 변경 전: `hw.gpu.mode=auto`
- 변경 후: `hw.gpu.mode=host`

다른 설정값은 그대로 유지됩니다.

## 대상 경로

배치 파일은 현재 로그인한 Windows 사용자 기준으로 아래 경로를 검사합니다.

```text
%USERPROFILE%\\.android\\avd\\*.avd\\config.ini
```

예시:

```text
C:\\Users\\사용자이름\\.android\\avd\\Pixel_10.avd\\config.ini
```

여기서 `Pixel_10.avd` 부분은 고정 이름이 아니며,
배치 파일은 `.android\\avd` 폴더 아래의 모든 `.avd` 폴더를 자동으로 검색합니다.

## 어떻게 작동하나요?

배치 파일은 다음 순서로 동작합니다.

1. `%USERPROFILE%\\.android\\avd` 폴더를 찾습니다.
2. 그 아래의 모든 `.avd` 폴더를 검사합니다.
3. 각 폴더 안의 `config.ini` 파일을 찾습니다.
4. `config.ini` 내용 중 `hw.gpu.mode=auto` 가 있으면
   `hw.gpu.mode=host` 로 변경합니다.
5. 다른 내용은 그대로 유지합니다.
6. 실행 결과를 화면에 보여줍니다.

## 사용 방법

1. Android Emulator를 종료합니다.
2. 배치 파일을 더블클릭해서 실행합니다.
3. 화면에 표시되는 결과를 확인합니다.
4. Emulator를 다시 실행합니다.

## 주의 사항

- 에뮬레이터가 실행 중인 상태에서 설정 파일을 수정하면 반영이 꼬일 수 있으므로,
  반드시 Emulator를 종료한 뒤 실행하는 것을 권장합니다.
- 이 도구는 `hw.gpu.mode=auto` 항목만 `host` 로 바꾸도록 만들어졌습니다.
- 이미 `hw.gpu.mode=host` 인 경우에는 실제 변경이 없을 수 있습니다.
- AVD 설정 구조나 파일 형식이 크게 다른 특수 환경에서는 동작이 다를 수 있습니다.

## 이런 분들에게 유용합니다

- Android Emulator 실행 시 그래픽 오류가 나는 경우
- `auto` 설정에서 검은 화면 또는 렌더링 문제가 생기는 경우
- 여러 AVD의 `config.ini` 를 일일이 열어 수정하기 번거로운 경우
- Windows 11 환경에서 빠르게 GPU 모드를 바꾸고 싶은 경우

## 포함 파일 예시

- `change_android_gpu_mode_safe.bat`

## 한 줄 요약

Windows에서 Android AVD의 `config.ini` 파일을 찾아  
`hw.gpu.mode=auto` 를 `hw.gpu.mode=host` 로 바꿔주는 배치 도구입니다.
