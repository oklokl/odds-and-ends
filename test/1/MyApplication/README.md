# 📚 Library To-Do App

안드로이드 스튜디오와 제미나이(Gemini) AI 어시스턴트를 활용하여 제작한 감성적인 도서관 테마의 **To-Do 리스트 안드로이드 앱**입니다.

---

## ✨ 주요 기능 (Key Features)

1. **할 일 관리 (To-Do Management)**
   - 텍스트 입력 후 **[추가]** 버튼을 통해 새로운 할 일을 쉽게 등록할 수 있습니다.
   - 개별 항목의 체크박스 또는 카드 전체를 터치하여 **완료/미완료 상태 전환**이 가능합니다.
   - 완료된 항목은 **취소선(`TextDecoration.LineThrough`)**과 함께 흐린 회색 톤으로 표시됩니다.
   - **[선택 완료된 항목만 삭제]**, **[전체 삭제]** 및 개별 삭제 기능을 지원합니다.
   - **[전체 선택 / 전체 해제]** 마스터 체크박스를 통해 리스트 전체를 한 번에 관리할 수 있습니다.

2. **데이터 영구 저장 (Data Persistence)**
   - `SharedPreferences`와 `JSONArray`를 활용하여 앱을 완전히 종료했다가 다시 켜도 사용자가 작성한 할 일 목록과 체크 상태가 그대로 유지됩니다.

3. **감성적인 도서관 배경 및 가독성 디자인**
   - 도서관 테마의 고감도 배경 이미지(`bg_library.png`)가 적용되어 있으며, 반투명 오버레이와 카드를 통해 텍스트 가독성을 높였습니다.

4. **오디오 및 인터랙티브 터치 효과**
   - 버튼 및 리스트 터치 시 안드로이드 시스템 기본 클릭 효과음(`SoundEffectConstants.CLICK`)이 재생됩니다.
   - **커스텀 원형 물결 파동(Circular Ripple Wave) 애니메이션**: 연못에 돌을 던진 것처럼 사용자가 화면(또는 버튼)을 터치한 정확한 위치에서 원형 파동이 부드럽게 확산되며 사라지는 시각적 효과를 제공합니다.

5. **성능 최적화 (Performance Optimization)**
   - `derivedStateOf`를 적용하여 불필요한 전체 Recomposition(재그리기)을 방지합니다.
   - `LazyColumn`에 고유 `UUID` 키(`key = { it.id }`)를 지정하여 리스트 추가/삭제 시 뛰어난 렌더링 성능을 보장합니다.

---

## 🛠️ 기술 스택 (Tech Stack)

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Architecture**: Modern Declarative UI & State Management
- **Local Storage**: SharedPreferences, JSON (`org.json`)
- **Animation & Graphics**: Canvas, Coroutine Animation, PointerInput Gestures

---

## 📱 프로젝트 구조 (Project Structure)

```text
MyApplication/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/myapplication/
│   │   │   ├── MainActivity.kt          # UI 화면, 상태 관리, 커스텀 물결 파동 구현
│   │   │   └── ui/theme/                # Material 3 테마 및 컬러 설정
│   │   └── res/
│   │       ├── drawable/bg_library.png  # 도서관 배경 이미지 리소스
│   │       └── values/strings.xml       # 문자열 리소스
│   └── build.gradle.kts
└── gradle/
    └── libs.versions.toml               # Version Catalog (디펜던시 버전 관리)
```

---

## 🚀 시작하기 (Getting Started)

1. Android Studio를 엽니다.
2. 프로젝트 루트 폴더(`MyApplication`)를 엽니다.
3. Gradle Sync 완료 후 에뮬레이터 또는 실제 안드로이드 기기에 실행(`Run`)합니다.
