# K메트로놈 사운드 세트 추가 설명서

현재 오디오 엔진 기준으로 새 `set`을 안전하게 추가하는 방법입니다.

---

# 1. 필수 파일

예를 들어 `set12`를 추가한다면:

```text
app/src/main/assets/sounds/set12/
  ├── strong_pcm.wav
  └── weak_pcm.wav
```

이 두 파일이 실제 재생에 필요한 파일입니다.

원본 보관이 필요하면 다음처럼 MP3도 같이 둘 수 있습니다.

```text
app/src/main/assets/sounds/set12/
  ├── strong_pcm.wav   ← 실제 사용
  ├── weak_pcm.wav     ← 실제 사용
  ├── strong.mp3       ← 선택: 원본 보관
  └── weak.mp3         ← 선택: 원본 보관
```

현재 `SoundManager.kt`는 MP3를 재생하지 않습니다.

---

# 2. WAV 형식

반드시 다음 형식으로 맞추는 것을 원칙으로 합니다.

```text
Container     : WAV (RIFF/WAVE)
Codec         : PCM signed 16-bit little-endian
Sample rate   : 48000 Hz
Channels      : 2 (Stereo)
Bit depth     : 16-bit
```

코드에서도 이 값을 검사합니다. 조건이 맞지 않으면 해당 set 로딩이 실패할 수 있습니다.

---

# 3. 가장 중요한 것은 "앞 무음"

강박과 약박의 파일 앞부분에 무음이 길면 코드상 박 위치와 실제 귀에 들리는 타격 위치가 달라집니다.

예:

```text
파일 시작: |--------------------| CLICK!
            80ms 앞 무음
```

코드는 0ms에 파일을 배치했지만 귀에는 80ms 뒤에 클릭이 들립니다.

따라서 새 사운드를 만들 때:

- transient 직전의 불필요한 무음을 제거
- 강박/약박의 시작 지점을 최대한 동일 기준으로 정렬
- 너무 공격적으로 자르면서 첫 transient를 훼손하지 않기

이 세 가지가 중요합니다.

---

# 4. MP3를 WAV로 바꾸는 방법

**파일 이름만 `.mp3` → `.wav`로 바꾸면 안 됩니다.**

실제로 디코딩하여 PCM WAV로 변환해야 합니다.

FFmpeg를 사용할 경우 예:

```bash
ffmpeg -i strong.mp3 -ar 48000 -ac 2 -c:a pcm_s16le strong_pcm.wav
ffmpeg -i weak.mp3   -ar 48000 -ac 2 -c:a pcm_s16le weak_pcm.wav
```

앞 무음까지 자동 제거하려면 별도의 silence trim을 적용할 수 있지만, 사운드마다 transient 특성이 다르므로 파형을 보고 확인하는 편이 안전합니다.

---

# 5. 형식 확인

FFprobe가 있다면:

```bash
ffprobe -v error -show_entries stream=codec_name,sample_rate,channels,sample_fmt -of default=noprint_wrappers=1 strong_pcm.wav
```

기대값의 예:

```text
codec_name=pcm_s16le
sample_fmt=s16
sample_rate=48000
channels=2
```

Windows 편집 프로그램을 사용한다면 내보내기 옵션에서 동일하게 맞추면 됩니다.

---

# 6. set 이름 규칙

현재 코드가 `assets/sounds` 아래에서 이름이 `set`으로 시작하는 폴더를 검색합니다.

권장:

```text
set0
set1
set2
...
set12
set13
```

숫자형 이름을 계속 쓰는 것이 가장 안전합니다.

---

# 7. 소리 길이는 박 간격보다 길어도 되는가?

됩니다.

현재 엔진은 소리의 tail이 다음 박 또는 다음 마디까지 이어질 수 있도록 원형 PCM 버퍼에 합성합니다.

예를 들어 한 클릭의 잔향이 400ms이고 박 간격이 250ms여도:

```text
CLICK~~~~~~~
      CLICK~~~~~~~
            CLICK~~~~~~~
```

처럼 겹칠 수 있습니다.

이 겹침 자체는 오류가 아닙니다. 메트로놈에서 더 중요한 것은 **각 transient 시작 위치가 정확한가**입니다.

다만 소리가 너무 길고 크면 여러 tail이 겹쳐 clipping처럼 들릴 수 있으므로 볼륨/길이는 청감 테스트가 필요합니다.

---

# 8. 새 세트 추가 후 테스트

최소한 다음 설정으로 들어보는 것을 권장합니다.

```text
4/4, Unit 4, 60 BPM
4/4, Unit 4, 120 BPM
7/8, Unit 8, 120 BPM
7/8, Unit 8, 200 BPM
11/8 또는 13/8 중 하나
```

확인 항목:

- 강박이 정확히 주기적으로 오는가
- 강박 직전 약박이 몰리지 않는가
- 중간에 `다다다`처럼 버퍼링성 리듬이 생기지 않는가
- 새 set과 set0의 시간감이 동일한가
- 강박/약박의 시작 위치가 서로 어긋나지 않는가
- 장시간(5~10분 이상) 재생해도 박 간격이 일정한가

---

# 9. 사운드 세트에서 이상할 때 구분법

## set0는 정상인데 새 set만 이상함

코드보다 WAV 파일을 먼저 의심합니다.

- 앞 무음
- 포맷 오류
- transient 자체가 느린 소리
- fade-in이 들어간 소리
- 파일 안에 이미 두 번의 타격이 들어간 경우

## 모든 set에서 동시에 이상함

오디오 엔진/AudioTrack/서비스 변경을 의심합니다.

## 소리는 정상인데 화면 표시만 흔들림

UI 틱과 오디오가 분리되어 있으므로 `MetronomeService`의 표시용 스케줄링을 확인합니다. 이 경우 오디오 엔진을 함부로 되돌리지 마십시오.

---

# 10. 한 줄 요약

새 사운드 세트는:

> **`setN/strong_pcm.wav` + `setN/weak_pcm.wav`, 48kHz PCM16 Stereo, 앞 무음 정렬**

이 규칙만 지키면 됩니다.
