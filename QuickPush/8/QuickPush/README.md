# 🐰 K 푸쉬팝 (QuickPush)

**K 푸쉬팝**은 남녀노소 누구나 즐길 수 있는 3D 실리콘 버블 인터랙션 & 감성 힐링 피젯 토이(QuickPush) 시뮬레이션 게임입니다.  
귀여운 토끼 콘솔 디자인과 통통 튀는 실리콘 팝 사운드, 미세 진동 햅틱 피드백, 감성적인 피아노 배경음악을 제공합니다.

---

## ✨ 주요 특징 (Key Features)

- 🚀 **4가지 다채로운 게임 모드**:
  - **돌파 모드 (Breakthrough)**: 점등된 LED 불빛을 모두 누르고 스테이지를 돌파하는 스피드/순발력 모드
  - **스피드 모드 (Time Attack)**: 30초/60초/120초 제한 시간 동안 콤보를 쌓으며 최고 점수에 도전하는 모드
  - **기억력 모드 (Memory / Pattern)**: 깜빡이며 켜지는 불빛 순서를 기억하고 따라 누르는 두뇌 트레이닝 모드
  - **자유 힐링 모드 (Free Play)**: 시간제한이나 실패 없이 편안하게 즐기는 힐링 모드
- 🔄 **리얼 토이 뒷면 푸시 (Back Push)**: 실제 전자 장난감처럼 하단 판을 밀어 올려 버블을 앞으로 한 번에 튕겨내는 손맛 구현
- 📱 **완벽한 반응형 뷰포트 & 상단 접기 토글**:
  - 어떤 스마트폰 해상도에서도 9개의 모든 버블이 100% 잘림 없이 한 화면에 노출
  - 상단 메뉴를 클릭 한 번으로 접어 토끼 귀가 최상단으로 밀착되며 게임 보드가 화면 전체로 시원하게 확대되는 대화면 모드 지원
- 🎵 **스마트 오디오 & 배경음악 시스템**:
  - 감성적인 피아노 BGM 순차 재생 및 자동 랜덤 셔플
  - 유튜브, 전화 등 타 미디어 사용 후 복귀 시 **오디오 포커스(AudioFocus) 자동 획득 및 재생 복원(Auto-Recovery)** 지원
- 🛠️ **시스템 & 오디오 진단 로그 뷰어 (Log Viewer)**:
  - 설정 창 하단에서 BGM 및 시스템 생명주기 진단 로그를 확인, 복사, 공유하거나 다운로드(Download) 폴더로 텍스트 파일 내보내기 지원
- 🛡️ **안전한 전연령 클린 앱**:
  - Google Play Age Signals API (0.0.4) 탑재로 미성년자 보호 및 법률 준수
  - 상용 광고 SDK(AdMob 등) 없음, 인앱 결제(Billing) 없음, 민감한 개인정보 수집 없음 (Zero Collection)

---

## 🛠️ 기술 스택 (Tech Stack)

- **Platform**: Android (minSdk 24, targetSdk 37, compileSdk 37)
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
- 🔒 [개인정보 처리방침 (privacy_policy.txt)](./privacy_policy.txt)
- 🏪 [구글 스토어 등록 정보 텍스트 (store_listing_description.txt)](./store_listing_description.txt)
- 🛠️ [AI 트러블슈팅 프롬프트 가이드 (AI_TROUBLESHOOTING_PROMPT_GUIDE.md)](./AI_TROUBLESHOOTING_PROMPT_GUIDE.md)
