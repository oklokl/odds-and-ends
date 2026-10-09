# 힐링 피젯 토이 (PushPop) 🎮✨

Jetpack Compose와 AndroidSVG를 기반으로 제작된 감성 힐링 피젯 토이(Push-Pop / Pop-It) 안드로이드 애플리케이션입니다.  
실제 실리콘 푸시팝 토이를 만지는 듯한 시각적 입체감, 탄성감 있는 스프링 애니메이션, 경쾌한 사운드 피드백을 제공합니다.

---

## 📱 앱 개요 및 주요 기능

### 1. 게임 및 인터랙션 모드
- **🎮 시소·오뚝이 모드 (Seesaw / Wobble Mode)**:
  - 버튼을 누르면 대칭 위치의 버튼 및 인접 버튼이 시소처럼 상호 연쇄 반응합니다.
  - 끝없이 뽁뽁이를 누르며 연속적인 리듬감과 스트레스 해소를 느낄 수 있는 자유 피젯 모드입니다.
- **🎯 올클리어 모드 (All-Clear Mode)**:
  - 9개의 모든 버튼을 쏙 들어가게 눌러 완성하는 목표 달성 모드입니다.
  - 하단에 실시간 진행 상황 게이지(Progress Indicator)가 표시되며, 올클리어 달성 시 피치 상승 사운드 및 축하 오버레이 애니메이션이 연출된 후 리셋됩니다.

### 2. 시각적 & 청각적 피드백
- **3D 실리콘 질감 & 동적 테마**:
  - 9개의 버튼이 각각 고유의 비비드 컬러(Red, Orange, Yellow, Green, Cyan, Purple, Pink, Teal, Lilac)를 가집니다.
  - AndroidSVG 기반의 동적 색상 치환과 자연스러운 구체 광택(Specular Highlight) 및 소프트 섀도우를 적용했습니다.
- **물리 기반 스프링 인터랙션**:
  - `animateFloatAsState`와 `Spring.DampingRatioMediumBouncy`를 활용하여 누를 때 쫀득하고 탄력 있는 반응을 구현했습니다.
- **저지연 입체 음향**:
  - Android `SoundPool`을 활용해 터치 지연 없이 즉각적인 '뽁' 클릭 사운드를 재생하며, 클리어 시 피치를 조절하여 성취감을 극대화했습니다.

---

## 🏗️ 프로젝트 구조

```
MyApplication3/
├── app/
│   ├── src/main/
│   │   ├── assets/                      # 게임 에셋
│   │   │   ├── pushpop_board.svg        # 3×3 원형 음각 홈이 파인 베이스 보드판
│   │   │   ├── pushpop_button.svg       # 돌출된(Raised) 3D 실리콘 푸시팝 버튼
│   │   │   ├── pushpop_button_pressed.svg # 눌려 들어간(Pressed) 3D 실리콘 버튼
│   │   │   └── pushpop_click.wav        # 팝 클릭 사운드 에셋
│   │   ├── java/com/example/myapplication/
│   │   │   ├── MainActivity.kt          # 앱 진입점, SVG 렌더러, 게임 로직 및 UI 컴포저블
│   │   │   └── ui/theme/                # Material 3 테마 및 컬러 정의
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
└── README.md
```

---

## 🛠️ 기술 스택

- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose (Material 3)
- **Vector Graphics Engine**: AndroidSVG (`com.caverock.androidsvg:androidsvg-aar:1.4`)
- **Audio Engine**: Android `SoundPool`
- **Animation**: Compose Physics-based Animation (`SpringSpec`)
- **Target SDK**: Android 35 (VanillaIceCream) / Min SDK 24

---

## 💡 개발 과정의 핵심 난제 & AI가 가장 해결하기 어려웠던 부분

개발 과정 중 가장 까다로웠고 일반적인 AI 자동 수정으로 쉽게 해결되지 않았던 핵심 문제는 **"버튼이 정중앙에 오지 않고 각 홈마다 좌상단으로 심하게 치우치며 우하단에 붉은 잔상이 남는 현상"**이었습니다.

단순히 Compose 레이아웃의 `offset`이나 `Alignment`만 수정해서는 해결되지 않았던 복합적인 원인과 최종 해결 과정은 다음과 같습니다.

### 1. 배경 SVG와 전경 컴포넌트의 이중 렌더링 (Ghost Artifacts)
- **문제**:
  - 배경으로 쓰인 `pushpop_board.svg` 안에 이미 정적인 9개 버튼이 그려져 있었습니다.
  - 그 위에 Compose가 `pushpop_button.svg`를 덧씌워 렌더링하고 있었기 때문에, 버튼을 누르거나 크기를 조정할 때마다 배경판의 정적 버튼이 뒤에서 삐져나오는 고스트 현상이 발생했습니다.
- **해결**:
  - `pushpop_board.svg`에서 정적 버튼 요소를 완전히 제거하고, 순수한 9개의 원형 음각 홈(`<use href="#hole"/>`)만 남겨 배경과 인터랙티브 버튼의 역할을 엄격히 분리했습니다.

### 2. SVG 자체의 내부 좌표계 왜곡 (Off-Center Canvas)
- **문제**:
  - 버튼 SVG 캔버스는 512×512 크기였으므로 중심축은 정확히 `(256, 256)`이어야 했습니다.
  - 그러나 기존 `pushpop_button.svg`는 버튼 구체가 `cy="225"`로 약 31px(전체의 6% 이상) 위로 쏠려 제작되어 있었습니다.
  - 설상가상으로 눌린 상태(`pushpop_button_pressed.svg`)는 `cy="256"`으로 제작되어 있어, 버튼 상태가 바뀔 때마다 중심 위치가 튀는 문제가 있었습니다.
- **해결**:
  - 돌출 상태와 눌린 상태의 모든 구체, 그림자, 테두리, 하이라이트의 중심축을 `cx="256", cy="256"`으로 완벽히 동심원(Concentric) 정렬했습니다.

### 3. 하드코딩된 비대칭 림(Rim)과 동적 테마 누락
- **문제**:
  - 입체감을 연출하려던 의도로 버튼 하단에 `cy="239"`, `r="204"` 크기의 비대칭 림과 하단 호(Path)가 들어가 있었습니다.
  - 이 림의 색상이 `#ffb2b0`(분홍), `#e71c32`(적색), `#8e1023`(암적색)으로 하드코딩되어 있었는데, Kotlin 코드의 테마 치환 로직은 버튼 본체 4색상만 교체하고 림 색상은 건드리지 않았습니다.
  - 결과적으로 초록, 파랑, 노랑 등 모든 버튼의 우하단에 흉측한 붉은 초승달 형태가 삐져나왔고, 이 시각적 덩어리 때문에 구체가 좌상단으로 더욱 밀려 보였습니다.
- **해결**:
  - 특정 색상에 종속된 비대칭 림을 제거하고, 구체 하단 음영 및 테두리 효과를 중립적인 알파 블렌딩(반투명 Black/White)으로 재설계했습니다. 이제 어떤 테마 색상이 들어가도 이질감 없는 자연스러운 3D 입체감이 연출됩니다.

### 4. 휴리스틱 오프셋의 악순환 제거
- **문제**:
  - 이전 개발 과정에서 SVG가 위로 쏠려 있는 것을 눈대중으로 맞추기 위해 Compose 코드에 `y = ... - btnSize * 0.035f` 같은 인위적인 수직 오프셋을 추가하여 불일치가 더욱 심화되었습니다.
- **해결**:
  - 인위적인 오프셋을 모두 걷어내고, 수학적으로 보드 홈의 중심 비율 `(boardWidth * xPct, boardHeight * yPct)`과 버튼 박스의 중심 `(x - btnSize / 2, y - btnSize / 2)`이 1:1로 정확하게 일치하도록 정립했습니다.
  - 버튼 크기를 보드 홀의 외벽과 내벽 비율에 맞추어 `boardWidth * (188f / 960f)`로 최적화했습니다.

---

## 🚀 실행 방법

1. Android Studio에서 본 프로젝트를 엽니다.
2. Gradle Sync를 완료합니다.
3. Android 기기 또는 에뮬레이터를 연결하고 `Run 'app'` (Shift + F10)을 실행합니다.
