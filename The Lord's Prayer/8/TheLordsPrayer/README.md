# 주기도문 (TheLord’sPrayer)

주기도문 텍스트를 화면에 표시하고, 음성(mp3)을 재생하는 Android 앱입니다.  
기본 음성(`res/raw/prayer_0815.mp3`)을 제공하며, `assets/sounds/set0`, `set1` … 폴더에 mp3를 추가하면 앱이 자동으로 인식하여 “음성 변경” 버튼으로 음성을 전환할 수 있습니다.

---

## 주요 기능

- 주기도문 텍스트 표시
- 음성 재생 / 일시정지 / 정지
- **음성 변경** 버튼으로 음성 세트(set0, set1, set2 …) 순환
- 각 세트 폴더 안의 mp3를 **랜덤으로 선택하여 재생**
- 세트가 없거나 mp3가 없으면 **기본 음성(res/raw)** 으로 자동 재생

---

## 음성(mp3) 추가 방법

### 1) 폴더 위치

아래 경로에 음성 세트 폴더를 추가합니다.

```
app/src/main/assets/sounds/
```

### 2) 세트 폴더 규칙

- 폴더 이름은 반드시 `set0`, `set1`, `set2`, `set3` … 처럼 **set + 숫자** 형식이어야 합니다.
- 숫자는 0부터 시작하는 것을 권장합니다.

예시:
```
app/src/main/assets/sounds/set0/
app/src/main/assets/sounds/set1/
app/src/main/assets/sounds/set2/
```

### 3) mp3 파일 넣기

각 `setX` 폴더 안에 mp3 파일을 넣으면 됩니다.

- 파일명은 **무작위/한글/영문 어떤 것이든 가능**
- 확장자는 `.mp3`만 인식

예시:
```
app/src/main/assets/sounds/set0/voice_a.mp3
app/src/main/assets/sounds/set0/랜덤이름.mp3
app/src/main/assets/sounds/set1/f01.mp3
```

### 4) 앱에서 전환 방식

- 앱 실행 후 **“음성 변경”** 버튼을 누를 때마다 다음 세트로 넘어갑니다.
- 선택된 세트 내 mp3 중 **1개가 랜덤 재생**됩니다.
- 재생 중에 음성을 변경하면, 변경된 세트 음성으로 **즉시** 전환됩니다.

---

## 기본 음성(res/raw)

기본 음성은 다음 리소스를 사용합니다.

```
app/src/main/res/raw/prayer_0815.mp3
```

- `assets/sounds/set0` 같은 세트가 **아예 없거나**, 세트 내부에 mp3가 없을 때 자동으로 기본 음성이 재생됩니다.

---

## 빌드 및 실행

1. Android Studio로 프로젝트를 엽니다.
2. Gradle Sync를 진행합니다.
3. 디바이스(또는 에뮬레이터)에서 실행합니다.

---

## 문제 해결

### “음성 변경”을 눌렀는데 mp3가 재생되지 않음

- `assets/sounds/set0` 처럼 폴더명이 `set숫자` 규칙인지 확인
- mp3 확장자 `.mp3`인지 확인
- 해당 세트 폴더 안에 mp3가 실제로 존재하는지 확인

### 어떤 mp3는 재생되고 어떤 mp3는 안 됨

- mp3 파일 자체가 손상되었거나 코덱/인코딩 문제가 있을 수 있습니다.
- 다른 mp3(표준 인코딩)로 교체해서 확인해 보세요.

---

## 폴더 구조 예시

```
app/
 └─ src/main/
    ├─ assets/sounds/
    │   ├─ set0/
    │   │   ├─ a.mp3
    │   │   └─ b.mp3
    │   └─ set1/
    │       └─ f01.mp3
    └─ res/raw/
        └─ prayer_0815.mp3
```

---

## 라이선스 / 저작권 주의

- 앱에 포함하거나 추가하는 mp3 파일은 반드시 사용 권한(저작권)을 확인한 뒤 사용하세요.
