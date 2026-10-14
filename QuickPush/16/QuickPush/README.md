# 🐰 K 푸쉬팝 (QuickPush)

**K 푸쉬팝**은 남녀노소 누구나 즐길 수 있는 3D 실리콘 버블 인터랙션 & 감성 힐링 피젯 토이(QuickPush) 시뮬레이션 게임입니다.  
귀여운 토끼 콘솔 디자인과 통통 튀는 실리콘 팝 사운드, 미세 진동 햅틱 피드백, 감성적인 피아노 배경음악을 제공합니다.

---

## ✨ 주요 특징 (Key Features)

- 🚀 **5가지 다채로운 게임 모드**:
  - **돌파 모드 (Breakthrough)**: 점등된 LED 불빛을 모두 누르고 스테이지를 돌파하는 스피드/순발력 모드 (시간 초과 시 GAME OVER 팝업 및 전 단계 재도전 지원)
  - **자유 힐링 모드 (Free Play)**: 시간제한이나 실패 없이 편안하게 즐기는 힐링 모드 (4,000점 단위 축하 배너)
  - **무한 퐁퐁 모드 (Infinite Auto-Pop) [NEW]**: 선풍기 버튼처럼 누른 자리는 들어가고 다른 곳에서 퐁퐁! 솟아오르는 신개념 무한 힐링 팝
  - **기억력 모드 (Memory / Pattern)**: 깜빡이며 켜지는 불빛 순서를 기억하고 따라 누르는 두뇌 트레이닝 모드 (초보자 2개부터 완만한 진행 & 5단계 돌파 배너)
  - **스피드 모드 (Time Attack)**: 30초/60초/120초 정직한 물리 타이머 동안 최고 점수에 도전하는 아케이드 모드
- ⏱️ **세로형 실시간 타이머 게이지 바 (`TimerGaugeBar`)**:
  - 토끼 본체 좌측 상단에 배치된 네오모픽 캡슐 게이지로, 시간에 따라 민트 그린 ➜ 앰버 옐로우 ➜ 네온 레드로 실시간 수위 전환
- 🏆 **4단계 난이도 설정 (`[DIFF]`)**:
  - 상단 컨트롤 버튼: `[MODE]` `[POWER]` `[VOL]` `[DIFF]`
  - 초보자(유치원생 수준의 넉넉한 타이머), 중급자(중학생 수준), 고수(대학생 수준 초고속) 지원
- 🔴 **SVG 기반 Breathing Red Circle (타깃 버튼 인디케이터)**:
  - 흐릿한 번짐을 완전히 제거하고, 1~1.5mm의 또렷하고 굵직한 네이티브 빨간색 숨쉬는 서클 테두리 장착 (120fps 하드웨어 가속)
- 🔄 **리얼 토이 뒷면 푸시 (Back Push)**: 실제 전자 장난감처럼 하단 판을 밀어 올려 버블을 앞으로 한 번에 튕겨내는 손맛 구현
- 📱 **완벽한 반응형 뷰포트 & 가로/세로/태블릿 대응**:
  - 스마트폰, 폴더블, 대화면 태블릿, 가로 회전 모드에서도 9개 버블과 게이지 바가 100% 최적의 비율로 표시
- 🎵 **스마트 오디오 & 무한 BGM 시스템**:
  - 첫 1회차 순차 재생 후 무한 랜덤 셔플 연속 재생
  - 유튜브/전화 복귀 시 오디오 포커스 즉시 자동 복구 및 알림 수신(Ducking) 후 100% 원래 볼륨 복원
  - 시스템 `onTrimMemory(20)` 간섭 버그를 완벽히 차단하여 모든 앱 닫기 후 재진입 시에도 100% 무조건 재생
- 🛡️ **안전한 전연령 클린 앱**:
  - Google Play Age Signals API (0.0.4) 탑재로 미성년자 보호 및 법률 준수
  - 상용 광고 SDK(AdMob 등) 없음, 인앱 결제 없음, 개인정보 수집 없음 (Zero Collection)

---

## 🛠️ 기술 스택 (Tech Stack)

- **Platform**: Android (minSdk 24, targetSdk 37, compileSdk 37)
- **Version**: v13.0 (VersionCode 13)
- **Language**: Kotlin 2.2.10
- **UI Toolkit**: Jetpack Compose (BOM 2025.02.00) + Material 3
- **Architecture**: MVVM, StateFlow, Coroutines
- **Build System**: Android Gradle Plugin 9.5 (R8 Full Optimization & Resource Shrinking 적용)
- **Compliance & Security**: Google Play Age Signals API, Android Vitals DEX Code Optimization 준수

---

## 🚀 로컬 빌드 및 실행 (Run Locally)

1. **Android Studio** 최신 버전을 실행합니다.
2. `Open`을 눌러 프로젝트 폴더(`QuickPush`)를 불러옵니다.
3. Gradle Sync가 완료된 후 실행 버튼(Run)을 눌러 에뮬레이터나 실기기에서 실행합니다.

---

## 📄 문서 링크 (Documents)

- 📖 [앱 매뉴얼 및 기능 가이드 (APP_MANUAL.md)](./APP_MANUAL.md)
- 🔋 [배터리 최적화 & 프로세스 재생성 가이드 (BATTERY_OPTIMIZATION_LIFECYCLE_GUIDE.md)](./BATTERY_OPTIMIZATION_LIFECYCLE_GUIDE.md)
- 🎵 [Android BGM 생명주기 아키텍처 가이드 (ANDROID_BGM_LIFECYCLE_ARCHITECTURE.md)](./ANDROID_BGM_LIFECYCLE_ARCHITECTURE.md)
- 🛠️ [AI 트러블슈팅 프롬프트 가이드 (AI_TROUBLESHOOTING_PROMPT_GUIDE.md)](./AI_TROUBLESHOOTING_PROMPT_GUIDE.md)
- 🔒 [개인정보 처리방침 (privacy_policy.txt)](./privacy_policy.txt)
- 🏪 [구글 스토어 등록 정보 텍스트 (store_listing_description.txt)](./store_listing_description.txt)
