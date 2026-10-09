# Three-Body Orbit (삼체 궤도 시뮬레이터) 🌌

> 소설 및 영화 **'삼체(The Three-Body Problem)'**의 천체역학적 혼돈과 아름다움을 담은 실시간 인터랙티브 우주 시뮬레이터 & 시각화 앱입니다. 세 개의 항성이 서로의 중력으로 얽혀 끊임없이 변화하는 매혹적인 궤도의 춤을 감상하고 직접 개입할 수 있습니다.

---

## ✨ 주요 기능 및 특징 (Key Features)

### 1. 🌟 크리스탈 클릭 사운드 (`crystal_click.wav`)
- 별들을 터치하거나 화면을 탭할 때마다 영롱한 **크리스탈 효과음**(`crystal_click.wav`)이 재생되어 시각적 요동과 함께 공감각적인 몰입감을 선사합니다.
- 설정(Tuning) 메뉴에서 사운드 효과를 간편하게 켜고 끌 수 있습니다.

### 2. 📳 다이내믹 햅틱 진동 피드백
- 화면 터치 시 발생하는 충격파와 세 별이 초근접하여 교차하는 **스윙바이(Swingsby) 현상** 발생 시 기기 진동(`HapticHelper`)을 통해 생생한 물리적 타격감을 전달합니다.

### 3. 🖐️ 3가지 인터랙티브 터치 모드
- **혼돈 충격파 (Chaos Impulse)**: 화면을 터치하면 충격파가 퍼져나가며 세 별에 무작위 충격량과 우주 먼지 파티클을 분출합니다.
- **중력 왜곡원 (Gravity Well)**: 터치 지점에 임시 블랙홀을 생성해 별들을 강력하게 끌어당깁니다.
- **별 직접 던지기 (Star Fling)**: 개별 항성을 직접 드래그하여 원하는 방향과 속도로 날려보낼 수 있습니다.

### 4. 🪐 프리셋 및 천체 궤도 모드
- **상호 공전 댄스 (Mutual Waltz)**: 세 별이 서로의 주위를 감돌며 율동적으로 춤추는 궤도
- **타원 궤도 (Elliptical Dance)**: 부드럽게 순환하는 초기 궤도
- **8자 궤도 (Figure-8 Choreography)**: 수학자 무어(Moore)와 수전(Su Chen)이 발견한 안정적인 8자 교차 정밀 해
- **라그랑주 삼중주 (Lagrange Trio)**: 등변삼각형을 이루며 회전하는 우아한 공전
- **삼체 혼돈 소용돌이 (Trisolaris Chaos)**: 예측 불가능하게 요동치며 휘몰아치는 난류 궤도

### 5. 📊 실시간 텔레메트리 & 시대(Era) 시스템
- 항성 간 거리, 평균 속도, 혼돈 지수(Chaos Index)를 실시간으로 모니터링합니다.
- **안정 주기(Stable Era)**, **난세기(Chaotic Era)**, **삼체 합류(Syzygy)**, **초신성 요동(Nova)** 등 현재 우주의 상태 시대가 실시간으로 전환됩니다.

### 6. ⚙️ 물리 파라미터 튜닝 바텀 시트
- 중력 상수($G$), 카오스 파동 세기(Turbulence), 시뮬레이션 속도, 궤적 잔상 길이 등을 입맛에 맞게 조절할 수 있습니다.
- 중력장 텐션 라인, 햅틱 진동, 크리스탈 사운드 토글 지원.

---

## 🛠️ 기술 스택 (Tech Stack)

- **UI Framework**: Jetpack Compose, Material 3, Canvas 2D Rendering
- **Architecture**: Unidirectional Data Flow (UDF), StateFlow, Custom Physics Loop (`withFrameNanos`)
- **Audio & Haptics**: `SoundPool` (`crystal_click.wav`), `Vibrator` / `VibrationEffect`
- **Language**: Kotlin 1.9+ / 2.0+
- **Build System**: Gradle, Android Gradle Plugin (AGP) 8.x+ / 9.x

---

## 🚀 로컬 실행 및 개발 가이드 (Run Locally)

**사전 요구 사항:** [Android Studio](https://developer.android.com/studio) 설치 필요

1. **프로젝트 열기**
   - 안드로이드 스튜디오를 실행하고 **Open**을 선택한 후 본 프로젝트 디렉터리를 엽니다.
2. **프로젝트 동기화**
   - Gradle 프로젝트 동기화가 완료될 때까지 기다립니다.
3. **디버그 서명 설정 확인**
   - AI Studio 환경 자동 서명 설정에 따라 로컬 디버그 빌드가 원활하도록 기본 디버그 키스토어 설정이 적용되어 있습니다.
4. **앱 실행**
   - 에뮬레이터(Emulator) 또는 안드로이드 실기기를 연결한 후 **Run (`▶`)** 버튼을 눌러 실행합니다.

---

## 📂 프로젝트 구조 (Project Structure)

```tree
com.example/
├── MainActivity.kt          # 앱 진입점 및 전체 화면 상태 관리
├── model/
│   └── ThreeBodyModels.kt   # 천체 데이터 모델, 프리셋, 시대(Era), 터치 모드 정의
├── physics/
│   └── ThreeBodySimulation.kt # 3체 중력 계산, 룬겐쿠타/오일러 적분, 난류 및 파티클 엔진
├── ui/
│   ├── ThreeBodyCanvasView.kt # 2D Canvas 기반 실시간 렌더링 및 제스처 감지
│   └── ThreeBodyControls.kt   # 상태 표시줄, 하단 도크, 물리 튜닝 바텀 시트
└── util/
    ├── HapticHelper.kt      # 햅틱 진동 관리 유틸리티
    └── SoundHelper.kt       # SoundPool 기반 크리스탈 클릭 사운드 관리 유틸리티
res/
└── raw/
    └── crystal_click.wav    # 별 클릭 시 재생되는 크리스탈 사운드 음원
```

---
*Created with passion for celestial mechanics and science fiction.* 🌠
