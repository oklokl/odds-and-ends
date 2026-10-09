# Three-Body Orbit (삼체 문제 궤도 시뮬레이터)

**Three-Body Orbit**은 물리학의 대표적인 난제인 **삼체 문제(Three-Body Problem)**를 바탕으로 한 인터랙티브 우주 궤도 시뮬레이션 및 우주 아트 앱입니다. 뉴턴의 중력 법칙에 따라 끊임없이 변화하는 세 천체의 카오스적이고 매혹적인 궤도 역학을 실시간으로 시각화하고 사용자가 직접 개입할 수 있습니다.

---

## 🌟 주요 기능 (Key Features)

1. **실시간 천체 물리 시뮬레이션 (Real-Time Physics Simulation)**
   - 만유인력 법칙($F = G \frac{m_1 m_2}{r^2}$)을 정밀하게 계산하여 세 천체 간의 상호작용과 카오스 동역학을 구현합니다.
   - 8자 궤도(Figure-8), 라그랑주 포인트(Lagrange Points), 타원 궤도(Elliptical), 카오스 댄스 등 다양한 천체 배치 프리셋 제공

2. **인터랙티브 상호작용 (Interactive Touch & Impulse)**
   - 화면 터치와 드래그를 통해 천체에 중력 임펄스를 가하거나, 새로운 천체 추가, 블랙홀 생성, 슬링샷(Slingshot) 가속 체험 가능
   - 햅틱 피드백(Haptic Feedback)을 통해 천체 간의 조우와 중력 충돌을 생생하게 전달

3. **고급 텔레메트리 및 튜닝 (Telemetry & Tuning)**
   - 총 에너지, 운동 에너지, 위치 에너지, 각운동량 및 현재 카오스 시대(Era) 실시간 모니터링
   - 질량, 중력 상수($G$), 시간 간격($dt$), 궤도 잔상 길이 등 물리 파라미터 커스텀 튜닝

4. **아름다운 우주 아트 시각화 (Cosmos Art & Visuals)**
   - 빛나는 궤도 잔상(Luminous Orbital Trails)과 중력장 선(Field Lines) 렌더링
   - Jetpack Compose와 고성능 Canvas를 활용한 매끄러운 60fps 우주 그래픽

---

## 🛠️ 기술 스택 (Tech Stack)

- **UI Framework**: Jetpack Compose, Material 3, Edge-to-Edge
- **Language**: Kotlin 100%
- **Concurrency**: Kotlin Coroutines & Flow
- **Rendering**: Custom Jetpack Compose Canvas View
- **AI Integration**: Gemini API (Cosmic Insights / AI features)

---

## 🚀 로컬 실행 방법 (Run Locally)

**필수 요구사항:** [Android Studio](https://developer.android.com/studio)

1. 안드로이드 스튜디오에서 프로젝트 디렉터리 오픈 (`Open`)
2. 프로젝트 루트에 `.env` 파일 생성 후 `GEMINI_API_KEY` 설정 (예시: `.env.example` 참조)
3. 에뮬레이터 또는 실제 안드로이드 기기에서 앱 실행 (`Run`)
