# FFmpeg Manager for Windows 🎬

<div align="center">

[![Windows](https://img.shields.io/badge/Windows-10%2F11-0078D6?style=for-the-badge&logo=windows&logoColor=white)](https://www.microsoft.com/windows)
[![FFmpeg](https://img.shields.io/badge/FFmpeg-Latest-007808?style=for-the-badge&logo=ffmpeg&logoColor=white)](https://ffmpeg.org/)
[![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](LICENSE)

**Windows용 원클릭 FFmpeg 설치 및 관리 도구**

[기능](#-주요-기능) • [다운로드](#-다운로드) • [사용법](#-사용법) • [동작 원리](#-동작-원리) • [문제 해결](#-문제-해결)

</div>

---

## 📖 소개

FFmpeg Manager는 Windows에서 FFmpeg를 **완전 자동으로** 설치하고 제거할 수 있는 배치 스크립트입니다. 수동 다운로드, 압축 해제, 환경 변수 설정 등 복잡한 과정을 단 한 번의 클릭으로 해결합니다!

### 🌟 왜 이 도구를 사용해야 하나요?

- 🚀 **완전 자동화** - URL만 입력하면 모든 것이 자동으로 처리됩니다
- 🔄 **설치/제거 통합** - 하나의 파일로 설치와 제거 모두 가능
- 📝 **상세한 로그** - 모든 과정이 기록되어 문제 해결이 쉽습니다
- 🎯 **스마트 관리** - 중복 설치 방지, PATH 자동 관리
- ✅ **안정성** - 수많은 테스트를 거쳐 완성된 신뢰할 수 있는 도구
- 🛡️ **안전** - 오픈소스, 숨겨진 작업 없음

---

## ✨ 주요 기능

### 📥 설치 모드
- ✅ 사용자가 원하는 FFmpeg 빌드 URL 입력 가능
- ✅ 자동 다운로드 및 압축 해제 (Windows 내장 tar 사용)
- ✅ 실행 파일(exe)과 라이브러리(dll)를 bin 폴더로 자동 정리
- ✅ 환경 변수(PATH) 자동 등록 (중복 방지 기능 포함)
- ✅ 기존 설치 덮어쓰기 확인
- ✅ 설치 중 문제 발생 시에도 안내 메시지 표시

### 🗑️ 제거 모드
- ✅ PATH에서 FFmpeg 경로만 정확히 제거
- ✅ 설치된 모든 파일 완전 삭제
- ✅ 깔끔한 제거 프로세스

### 🔧 추가 기능
- 📊 실시간 6단계 설치 진행 상황 표시
- 📁 Downloads 폴더에 자동 로그 생성
- 🔒 관리자 권한 자동 요청 (UAC)
- 💬 설치 완료 시 팝업 메시지
- 🔍 설치 실패 시 상세한 수동 설치 가이드
- ⚡ PATH 길이 제한 없음 (1024자 제한 해결)

---

## 📥 다운로드

### 방법 1: Releases에서 다운로드
1. [Releases](../../releases) 페이지 방문
2. 최신 버전의 `ffmpeg_installer.bat` 다운로드

### 방법 2: Git Clone
```bash
git clone https://github.com/your-username/ffmpeg-manager-windows.git
cd ffmpeg-manager-windows
```

---

## 🚀 사용법

### 📥 설치하기

#### 1단계: 관리자 권한으로 실행
- `ffmpeg_installer.bat` 파일을 **마우스 오른쪽 클릭**
- **"관리자 권한으로 실행"** 선택
- UAC 팝업에서 **"예"** 클릭

#### 2단계: 모드 선택
```
============================================
   FFmpeg Manager
============================================

Log: C:\Users\사용자이름\Downloads\ffmpeg_log.txt

1. Install (Install FFmpeg)
2. Uninstall (Remove FFmpeg)

Select [1,2]?
```
**1** 입력 → Enter

#### 3단계: FFmpeg URL 입력
팝업 창이 나타나면 FFmpeg 다운로드 URL을 입력하세요.

**추천 URL (최신 빌드):**
```
https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl-shared.zip
```

#### 4단계: 자동 설치 진행
```
[1/6] Creating folder...          ✓ Done
[2/6] Downloading...               ✓ Done
[3/6] Extracting archive...        ✓ Done
[4/6] Copying files to bin...      ✓ Done (3 files)
[5/6] Setting environment...       ✓ Done
[6/6] Installation Complete!       ✓ SUCCESS!
```

#### 5단계: 컴퓨터 재시작
환경 변수 적용을 위해 **컴퓨터를 재시작**하세요.

#### 6단계: 설치 확인
명령 프롬프트를 열고 테스트:
```bash
ffmpeg -version
```

---

### 🗑️ 제거하기

#### 1단계: 관리자 권한으로 실행
#### 2단계: 모드 선택 → **2** 입력
#### 3단계: 자동 제거 진행
```
[1/3] Removing from PATH...       ✓ Done
[2/3] Deleting folder...           ✓ Done
[3/3] Uninstall complete!          ✓ SUCCESS!
```
#### 4단계: 컴퓨터 재시작

---

## 🔧 동작 원리

### 📦 설치 프로세스 (6단계)

#### 1단계: 폴더 생성
```
C:\Program Files\ffmpeg\
```
- 설치 디렉토리 생성
- 기존 폴더가 있으면 덮어쓰기 확인

#### 2단계: 다운로드
- PowerShell `Invoke-WebRequest`로 다운로드
- 임시 폴더(`%TEMP%`)에 저장
- 기존 파일이 있으면 자동 덮어쓰기

#### 3단계: 압축 해제
- Windows 10/11 내장 `tar` 명령어 사용
- `C:\Program Files\ffmpeg\`에 압축 해제

#### 4단계: 파일 정리
모든 `.exe`와 `.dll` 파일을 `bin` 폴더로 복사:
```
C:\Program Files\ffmpeg\bin\
├── ffmpeg.exe    (동영상 변환)
├── ffplay.exe    (동영상 재생)
├── ffprobe.exe   (파일 정보 확인)
├── avcodec-62.dll
├── avformat-62.dll
├── avutil-60.dll
└── ... (기타 DLL 파일들)
```

#### 5단계: 환경 변수 등록
**스마트 PATH 관리:**
1. 레지스트리에서 현재 사용자 PATH 읽기
2. 이미 등록되어 있는지 확인
3. 없으면 추가: `C:\Program Files\ffmpeg\bin`
4. 레지스트리 재확인으로 검증
5. 중복 등록 완전 방지

**기술적 특징:**
- `setx` 명령어 사용 (사용자 수준 등록)
- 레지스트리 직접 확인으로 신뢰성 보장
- PATH 길이 제한 문제 없음

#### 6단계: 설치 완료
- 성공 메시지 팝업 표시
- 로그 파일 저장
- 재부팅 안내

---

### 🗑️ 제거 프로세스 (3단계)

#### 1단계: PATH에서 제거
- 레지스트리에서 현재 PATH 읽기
- `C:\Program Files\ffmpeg\bin` 문자열만 정확히 제거
- 업데이트된 PATH 저장

#### 2단계: 폴더 삭제
```batch
rd /s /q "C:\Program Files\ffmpeg"
```
- 전체 FFmpeg 폴더 삭제

#### 3단계: 완료
- 제거 완료 팝업 표시
- 재부팅 안내

---

## 📁 파일 구조

### 설치 후
```
C:\Program Files\ffmpeg\
├── bin\                          ← 환경 변수에 등록된 폴더
│   ├── ffmpeg.exe
│   ├── ffplay.exe
│   ├── ffprobe.exe
│   ├── avcodec-62.dll
│   ├── avformat-62.dll
│   ├── avutil-60.dll
│   └── ... (기타 DLL)
├── doc\                          ← 문서 (선택사항)
├── include\                      ← 헤더 파일 (개발용)
├── lib\                          ← 라이브러리 (개발용)
└── LICENSE.txt
```

### 로그 파일
```
C:\Users\사용자이름\Downloads\
├── ffmpeg_log.txt               ← 전체 설치/제거 로그
└── ffmpeg_error.txt             ← 오류 발생 시 상세 로그
```

---

## 💡 FFmpeg 사용 예시

설치 후 명령 프롬프트나 PowerShell에서 바로 사용:

### 동영상 형식 변환
```bash
ffmpeg -i input.mp4 output.avi
```

### MP4를 MP3로 변환
```bash
ffmpeg -i video.mp4 -vn audio.mp3
```

### 동영상 해상도 변경
```bash
ffmpeg -i input.mp4 -vf scale=1280:720 output.mp4
```

### 동영상 자르기 (0초~10초)
```bash
ffmpeg -i input.mp4 -ss 00:00:00 -t 00:00:10 output.mp4
```

### 동영상 재생
```bash
ffplay video.mp4
```

### 파일 정보 확인
```bash
ffprobe video.mp4
```

---

## 🛠️ 문제 해결

### ❓ "관리자 권한이 필요합니다" 메시지가 반복됨

**해결 방법:**
1. 파일을 **마우스 오른쪽 클릭**
2. **"관리자 권한으로 실행"** 선택 (꼭!)
3. UAC 팝업에서 **"예"** 클릭

### ❓ URL 입력창이 나타나지 않음

**가능한 원인:**
- PowerShell 실행 정책 제한

**해결 방법:**
1. PowerShell을 관리자 권한으로 실행
2. 다음 명령어 입력:
   ```powershell
   Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
   ```

### ❓ 다운로드가 실패함

**확인 사항:**
- ✅ 인터넷 연결 확인
- ✅ URL이 올바른지 확인
- ✅ 방화벽/백신 프로그램 확인

**대체 URL:**
다른 FFmpeg 빌드를 시도해보세요:
- https://github.com/BtbN/FFmpeg-Builds/releases

### ❓ 환경 변수 등록이 실패함

**자동 등록 실패 시:**
스크립트가 수동 방법을 안내합니다.

**수동 등록 방법:**
1. Windows 검색에서 "환경 변수" 검색
2. "시스템 환경 변수 편집" 클릭
3. "환경 변수" 버튼 클릭
4. "사용자 변수"에서 "Path" 선택
5. "편집" 클릭
6. "새로 만들기" 클릭
7. `C:\Program Files\ffmpeg\bin` 입력
8. 확인 → 확인 → 확인
9. 컴퓨터 재시작

### ❓ ffmpeg 명령어가 인식되지 않음

**해결 방법:**
1. **컴퓨터를 재시작**하세요 (가장 중요!)
2. 명령 프롬프트를 **새로 열어**보세요
3. PATH 확인:
   ```cmd
   echo %PATH%
   ```
4. `C:\Program Files\ffmpeg\bin`이 포함되어 있는지 확인

### ❓ 설치는 완료되었는데 경고 메시지가 나옴

**상황:**
- "Installation Complete!" 메시지가 나왔지만
- "WARNING: PATH update issue"도 표시됨

**확인 방법:**
1. 환경 변수 편집 창 열기
2. "Path" 변수 확인
3. `C:\Program Files\ffmpeg\bin`이 있으면 **성공**입니다!

**실제로는 성공한 경우:**
- 로그에 "Installation completed" 표시
- bin 폴더에 파일들이 정상적으로 복사됨
- 재부팅 후 ffmpeg 명령어가 작동함

---

## 📋 시스템 요구사항

- **운영체제:** Windows 10 (1803 이상) 또는 Windows 11
- **권한:** 관리자 권한 필요
- **저장 공간:** 약 200MB (FFmpeg 버전에 따라 다름)
- **인터넷:** 다운로드를 위한 인터넷 연결
- **필수 구성 요소:** 
  - PowerShell (Windows 기본 내장)
  - tar 명령어 (Windows 10 1803+ 기본 내장)

---

## 🔍 기술 세부사항

### 사용된 기술
- **배치 스크립트 (Batch Script):** 핵심 로직 및 제어 흐름
- **PowerShell:** 
  - URL 입력창 (Microsoft.VisualBasic.Interaction)
  - 파일 다운로드 (Invoke-WebRequest)
  - 완료 메시지 팝업 (System.Windows.Forms.MessageBox)
- **Windows tar:** 압축 해제 (Windows 10 1803+ 내장)
- **레지스트리 명령어:** 환경 변수 관리
  - `reg query` - PATH 읽기
  - `setx` - PATH 업데이트

### 핵심 기술 구현

#### 1. 관리자 권한 자동 요청
```batch
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)
```

#### 2. URL 입력 팝업
```batch
powershell -Command "Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.Interaction]::InputBox('Enter FFmpeg download URL:', 'FFmpeg Installer', '')"
```

#### 3. 스마트 PATH 중복 방지
```batch
:: 현재 PATH 읽기
for /f "skip=2 tokens=2*" %%a in ('reg query "HKCU\Environment" /v PATH') do set "CURRENT_USER_PATH=%%b"

:: 이미 있는지 확인
echo !CURRENT_USER_PATH! | find /i "ffmpeg\bin" >nul
if not errorlevel 1 (
    echo Already registered!
    exit /b
)

:: 추가
setx PATH "!CURRENT_USER_PATH!;C:\Program Files\ffmpeg\bin"
```

#### 4. 설치 검증
```batch
:: 2초 대기 (레지스트리 업데이트)
timeout /t 2 /nobreak >nul

:: 다시 읽어서 확인
for /f "skip=2 tokens=2*" %%a in ('reg query "HKCU\Environment" /v PATH') do set "NEW_PATH=%%b"
echo !NEW_PATH! | find /i "ffmpeg\bin" >nul
if not errorlevel 1 echo Success!
```

#### 5. 서브루틴 활용
```batch
if "%PATH_EXISTS%"=="1" call :PATH_ALREADY_EXISTS
if "%PATH_EXISTS%"=="0" call :ADD_TO_PATH
goto INSTALLATION_COMPLETE

:PATH_ALREADY_EXISTS
echo Already exists!
exit /b

:ADD_TO_PATH
echo Adding...
exit /b
```

### 해결한 기술적 문제들

#### 문제 1: PATH 길이 제한 (1024자)
- **원인:** `setx` 명령어의 기본 제한
- **해결:** 레지스트리 직접 읽기/쓰기로 회피

#### 문제 2: 한글 Windows 인코딩
- **원인:** UTF-8과 코드페이지 충돌
- **해결:** 모든 메시지 영어로 작성, `chcp` 명령어 사용 안 함

#### 문제 3: PowerShell exit code 문제
- **원인:** `errorlevel` 값이 부정확
- **해결:** 레지스트리 재확인으로 실제 성공 여부 검증

#### 문제 4: enabledelayedexpansion과 goto 충돌
- **원인:** 지연 확장 환경에서 goto가 불안정
- **해결:** `call :SUBROUTINE` 방식으로 변경

#### 문제 5: 중복 PATH 등록
- **원인:** 중복 확인 로직 부재
- **해결:** 레지스트리 PATH 사전 확인 후 조건부 등록

---

## 📝 개발 과정 (Development Journey)

이 프로젝트는 여러 기술적 도전과 해결 과정을 거쳐 완성되었습니다.

### 주요 개발 단계

1. **초기 개념 (v1.0)**
   - 기본 다운로드 및 압축 해제 기능

2. **관리자 권한 추가 (v2.0)**
   - VBScript 방식의 UAC 요청

3. **URL 입력 기능 (v3.0)**
   - PowerShell InputBox 구현
   - 인코딩 문제 해결 (한글 Windows 지원)

4. **PATH 자동 등록 (v4.0~v8.0)**
   - 여러 방법 시도 (PowerShell, setx, 레지스트리)
   - PATH 길이 제한 문제 해결
   - 중복 등록 방지 구현

5. **안정성 개선 (v9.0~v12.0)**
   - goto 문제 해결 → call 서브루틴 방식
   - 검증 로직 강화
   - 에러 처리 개선

6. **최종 완성 (v13.0)**
   - 모든 엣지 케이스 처리
   - 상세한 로그 시스템
   - 사용자 친화적 메시지

### 극복한 도전 과제

- ✅ 한글 Windows 인코딩 이슈
- ✅ PowerShell과 Batch의 변수 전달
- ✅ PATH 길이 제한 (1024자)
- ✅ enabledelayedexpansion 환경에서의 제어 흐름
- ✅ 레지스트리 읽기/쓰기 타이밍 이슈
- ✅ 사용자 경험 최적화

---

## 🤝 기여하기

이슈와 풀 리퀘스트를 환영합니다!

### 기여 방법

1. 이 저장소를 포크하세요
2. 기능 브랜치를 만드세요
   ```bash
   git checkout -b feature/AmazingFeature
   ```
3. 변경사항을 커밋하세요
   ```bash
   git commit -m 'Add some AmazingFeature'
   ```
4. 브랜치에 푸시하세요
   ```bash
   git push origin feature/AmazingFeature
   ```
5. 풀 리퀘스트를 열어주세요

### 개선 아이디어
- [ ] GUI 버전 개발
- [ ] 자동 업데이트 기능
- [ ] FFmpeg 버전 선택 기능
- [ ] 설정 파일 지원 (자주 사용하는 URL 저장)
- [ ] 다국어 지원 (영어, 일본어, 중국어)

---

## 📄 라이선스

이 프로젝트는 MIT 라이선스를 따릅니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

---

## 🙏 감사의 말

### 오픈소스 프로젝트
- **[FFmpeg](https://ffmpeg.org/)** - 강력한 멀티미디어 프레임워크
- **[FFmpeg-Builds by BtbN](https://github.com/BtbN/FFmpeg-Builds)** - Windows용 FFmpeg 빌드 제공

### 커뮤니티
- 테스트와 피드백을 제공해주신 모든 분들
- 이슈를 보고하고 개선 아이디어를 제안해주신 분들

---

## 📞 문의 및 지원

### 문제가 발생하셨나요?
- [Issues](../../issues) 페이지에서 이슈 생성
- 기존 이슈를 검색하여 해결 방법 확인

### 질문이 있으신가요?
- [Discussions](../../discussions) 페이지에서 질문하기

### 기능 제안
- [Issues](../../issues)에서 Enhancement 라벨로 제안하기

---

## 📊 프로젝트 통계

- **개발 기간:** 2025년 11월
- **총 개발 시간:** 약 3시간
- **테스트 횟수:** 20+ 회
- **해결한 버그:** 10+ 개
- **코드 라인:** 400+ 줄

---

## 🎯 프로젝트 목표

이 프로젝트의 목표는 **누구나 쉽게 FFmpeg를 설치할 수 있도록** 하는 것입니다.

✅ **달성한 목표:**
- 원클릭 설치
- 완전 자동화
- 초보자도 사용 가능
- 안정적이고 신뢰할 수 있는 도구

---

## 🌟 특별 감사

이 프로젝트가 도움이 되었다면 ⭐️ 스타를 눌러주세요!

---

<div align="center">

### 💖 프로젝트 크레딧

**아이디어 제공:** 동우 님  
**개발 및 구현:** Claude (Anthropic AI)

---

**2025년 11월 제작**

Made with ❤️ for the Windows & FFmpeg community

</div>
