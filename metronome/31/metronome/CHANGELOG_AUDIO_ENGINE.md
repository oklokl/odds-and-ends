# Audio Engine Change Log

## 2026-09-28 — PCM AudioTrack 엔진으로 전환

### 문제

- 강박 직전 약박이 갑자기 빨라지는 체감
- 몇 박이 `다다다`처럼 몰려 들림
- 정상 재생과 불규칙 재생이 간헐적으로 반복
- 화면/계산은 정상인데 실제 오디오가 리듬을 타는 듯한 현상

### 1차 수정

- 늦어진 타이머가 다음 박에서 무리하게 catch-up하지 않도록 보정
- 정밀 대기를 추가
- 오디오 전용 고우선순위 HandlerThread 사용
- WakeLock의 10분 제한을 제거하고 재생 수명주기에 맞춰 관리

### 결과

- 스케줄러 자체의 급격한 간격 보정 위험은 줄었으나 실제 소리의 불규칙성이 완전히 사라지지는 않음

### 2차 / 최종 구조 변경

`SoundManager.kt`:

- SoundPool 기반 박별 재생 제거
- `strong_pcm.wav`, `weak_pcm.wav` 사용
- 48kHz / PCM16 / Stereo 형식 강제 검증
- 한 마디 전체 PCM을 샘플 단위로 사전 합성
- `AudioTrack.MODE_STATIC` 사용
- `setLoopPoints()`로 네이티브 무한 반복
- 큰 마디/기기 제약 시 MODE_STREAM 연속 PCM 폴백
- tail이 다음 박/마디로 이어질 수 있도록 circular mix

`MetronomeService.kt`:

- 일반 소리를 틱 함수에서 직접 재생하지 않음
- 오디오와 UI/플래시/진동 스케줄을 분리
- BPM/Unit/Beat/Sound 변경 시 오디오 루프 재구성

사운드 자산:

- 각 `setN`에 `strong_pcm.wav`, `weak_pcm.wav` 추가
- 원본 MP3는 보관하되 현재 재생 엔진에서는 사용하지 않음
- PCM WAV는 48kHz / PCM16 / Stereo로 통일
- 불필요한 선행 무음을 줄여 transient 시작 위치를 맞춤

### 검증 포인트

핵심 회귀 테스트:

```text
7/8
Unit 8
120 BPM
=> 250ms per beat
=> 1.75s per measure
```

사용자 청감 테스트에서 이전의 버퍼링성 `단다다단...` 현상이 사라지고 일정한 박자로 정상 동작함을 확인.

### 유지보수 결론

오디오 타이밍 문제를 다시 예방하려면 다음 원칙을 유지할 것:

> **실제 박자 소리는 Java/Kotlin 타이머가 매번 발사하지 않고, 미리 구성한 PCM 샘플 시간축을 AudioTrack이 연속 재생하도록 한다.**
