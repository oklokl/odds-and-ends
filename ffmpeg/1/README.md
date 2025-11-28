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

FFmpeg Manager는 Windows에서 FFmpeg를 자동으로 설치하고 제거할 수 있는 간단하면서도 강력한 배치 스크립트입니다. 더 이상 수동 다운로드, 환경 변수 설정, 설치 가이드 검색이 필요 없습니다!

### 왜 이 도구를 사용해야 하나요?

- 🚀 **원클릭 설치** - 몇 분 안에 FFmpeg 다운로드, 압축 해제, 설정 완료
- 🔄 **간편한 제거** - 한 번의 명령으로 깔끔하게 제거
- 📝 **자동 로그 기록** - 문제 해결을 위한 완전한 설치 로그
- 🎯 **스마트 PATH 관리** - PATH 길이 제한 문제 해결
- ✅ **중복 방지** - 중복된 PATH 항목 방지
- 🛡️ **안전하고 투명** - 오픈소스, 숨겨진 작업 없음

---

## ✨ 주요 기능

### 설치 모드
- ✅ 사용자 지정 URL에서 FFmpeg 다운로드
- ✅ 자동으로 압축 해제 및 파일 정리
- ✅ 실행 파일과 DLL을 `bin` 폴더로 복사
- ✅ 시스템 PATH에 FFmpeg 등록 (사용자 수준)
- ✅ 중복 PATH 항목 방지
- ✅ 긴 PATH 변수 처리 (1024자 제한 없음)
- ✅ 확인 후 기존 설치 덮어쓰기

### 제거 모드
- ✅ PATH에서 FFmpeg 깔끔하게 제거
- ✅ 설치된 모든 파일 삭제
- ✅ 로그 기록과 함께 완전한 정리

### 추가 기능
- 📊 실시간 진행 상황 표시 (6단계 설치)
- 📁 Downloads 폴더에 로그 파일 자동 생성
- 🔒 관리자 권한 자동 요청
- 💬 성공/실패 메시지 팝업
- 🔍 상세한 오류 로그

---

## 📥 다운로드

### 방법 1: 직접 다운로드
1. [Releases](../../releases) 페이지에서 최신 버전 다운로드
2. `ffmpeg_installer.bat` 파일 다운로드

### 방법 2: Git Clone
```bash
git clone https://github.com/your-username/ffmpeg-manager-windows.git
cd ffmpeg-manager-windows
```

---

## 🚀 사용법

### 설치하기

1. **관리자 권한으로 실행**
   - `ffmpeg_installer.bat` 파일을 **마우스 오른쪽 클릭**
   - **"관리자 권한으로 실행"** 선택

2. **모드 선택**
   ```
   1. Install (Install FFmpeg)
   2. Uninstall (Remove FFmpeg)
   
   Select [1,2]?
   ```
   - **1** 입력 → 설치
   - **2** 입력 → 제거

3. **URL 입력** (설치 모드인 경우)
   - 팝업 창이 나타나면 FFmpeg 다운로드 URL 입력
   - 예시: `https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl-shared.zip`

4. **자동 설치 진행**
   ```
   [1/6] Creating folder...
   [2/6] Downloading...
   [3/6] Extracting archive...
   [4/6] Copying files to bin folder...
   [5/6] Setting environment variable...
   [6/6] Installation Complete!
   ```

5. **컴퓨터 재시작**
   - 환경 변수 적용을 위해 컴퓨터를 재시작하세요

### 제거하기

1. **관리자 권한으로 실행**
2. **모드 선택** → **2** 입력 (Uninstall)
3. **자동 제거 진행**
   ```
   [1/3] Removing from PATH...
   [2/3] Deleting folder...
   [3/3] Uninstall complete!
   ```
4. **컴퓨터 재시작**

---

## 📸 스크린샷

### 시작 화면
```
============================================
   FFmpeg Manager
============================================

Log: C:\Users\사용자이름\Downloads\ffmpeg_log.txt

1. Install (Install FFmpeg)
2. Uninstall (Remove FFmpeg)

Select [1,2]?
```

### 설치 진행
```
============================================
[2/6] Downloading...
============================================
URL: https://github.com/BtbN/FFmpeg-Builds/releases/...

Please wait, downloading...
Done!
```

---

## 🔧 동작 원리

### 설치 과정

#### 1단계: 폴더 생성
```batch
C:\Program Files\ffmpeg\
```

#### 2단계: 다운로드
- 사용자가 입력한 URL에서 FFmpeg zip 파일 다운로드
- 임시 폴더(`%TEMP%`)에 저장
- 기존 파일이 있으면 자동으로 덮어쓰기

#### 3단계: 압축 해제
- Windows 내장 `tar` 명령어 사용
- `C:\Program Files\ffmpeg\` 폴더에 압축 해제

#### 4단계: 파일 복사
- 모든 `.exe` 파일 검색 및 복사:
  - `ffmpeg.exe`
  - `ffplay.exe`
  - `ffprobe.exe`
- 모든 `.dll` 파일 복사:
  - `avcodec-62.dll`
  - `avformat-62.dll`
  - `avutil-60.dll`
  - 기타 필요한 라이브러리

#### 5단계: 환경 변수 설정
- **중복 체크**: 이미 PATH에 있는지 확인
- **PowerShell 사용**: 레지스트리 직접 수정 (길이 제한 없음)
- **사용자 수준 등록**: `HKCU\Environment`
```powershell
[Environment]::SetEnvironmentVariable('Path', $path + ';C:\Program Files\ffmpeg\bin', 'User')
```

#### 6단계: 완료
- 성공 메시지 팝업
- 로그 파일 저장: `%USERPROFILE%\Downloads\ffmpeg_log.txt`

### 제거 과정

#### 1단계: PATH에서 제거
- 레지스트리에서 현재 PATH 읽기
- `C:\Program Files\ffmpeg\bin` 문자열 제거
- 업데이트된 PATH 저장

#### 2단계: 폴더 삭제
```batch
rd /s /q "C:\Program Files\ffmpeg"
```

#### 3단계: 완료
- 제거 완료 메시지 팝업

---

## 📁 파일 구조

### 설치 후
```
C:\Program Files\ffmpeg\
├── bin\
│   ├── ffmpeg.exe        ← 동영상 변환
│   ├── ffplay.exe        ← 동영상 재생
│   ├── ffprobe.exe       ← 파일 정보 확인
│   ├── avcodec-62.dll    ← 코덱 라이브러리
│   ├── avformat-62.dll   ← 포맷 라이브러리
│   ├── avutil-60.dll     ← 유틸리티 라이브러리
│   └── ...               ← 기타 DLL 파일
└── [압축 해제된 기타 파일들]
```

### 로그 파일
```
C:\Users\사용자이름\Downloads\
├── ffmpeg_log.txt        ← 전체 설치/제거 로그
└── ffmpeg_error.txt      ← 오류 로그 (오류 발생 시)
```

---

## 💡 FFmpeg 사용 예시

설치 후 명령 프롬프트나 PowerShell에서 바로 사용 가능합니다:

### 동영상 변환
```bash
ffmpeg -i input.mp4 output.avi
```

### MP4를 MP3로 변환
```bash
ffmpeg -i video.mp4 -vn audio.mp3
```

### 동영상 크기 조정
```bash
ffmpeg -i input.mp4 -vf scale=1280:720 output.mp4
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

### 문제: "관리자 권한이 필요합니다" 메시지가 반복됨
**해결:**
1. 파일을 **마우스 오른쪽 클릭**
2. **"관리자 권한으로 실행"** 선택
3. UAC 팝업에서 **"예"** 클릭

### 문제: URL 입력창이 나타나지 않음
**해결:**
1. `%USERPROFILE%\Downloads\ffmpeg_error.txt` 확인
2. PowerShell 실행 정책 확인:
   ```powershell
   Get-ExecutionPolicy
   ```
3. 필요 시 정책 변경:
   ```powershell
   Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
   ```

### 문제: 다운로드가 실패함
**해결:**
1. 인터넷 연결 확인
2. URL이 올바른지 확인
3. 방화벽/백신 프로그램 확인
4. 다른 FFmpeg 빌드 URL 시도

### 문제: PATH 설정이 실패함
**원인:** PATH가 너무 긴 경우 (1024자 이상)

**해결:** 이 스크립트는 PowerShell을 사용하여 PATH 길이 제한 문제를 자동으로 해결합니다!

### 문제: ffmpeg 명령어가 인식되지 않음
**해결:**
1. 컴퓨터를 재시작하세요
2. 명령 프롬프트를 다시 열어보세요
3. PATH 확인:
   ```cmd
   echo %PATH%
   ```
4. `C:\Program Files\ffmpeg\bin`이 포함되어 있는지 확인

---

## 📋 시스템 요구사항

- **운영체제**: Windows 10 또는 Windows 11
- **권한**: 관리자 권한 필요
- **저장 공간**: 약 200MB (FFmpeg 버전에 따라 다름)
- **인터넷**: 다운로드를 위한 인터넷 연결

---

## 🔍 기술 세부사항

### 사용된 기술
- **배치 스크립트**: 핵심 로직
- **PowerShell**: URL 입력, PATH 관리, 파일 다운로드
- **Windows tar**: 압축 해제 (Windows 10 1803 이상 내장)

### 특별한 기능

#### PATH 길이 제한 해결
기존 `setx` 명령어의 1024자 제한을 PowerShell을 통한 레지스트리 직접 수정으로 해결:
```powershell
[Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
```

#### 중복 방지
PATH 등록 전 이미 존재하는지 확인:
```batch
echo %PATH% | find /i "%ProgramFiles%\ffmpeg\bin"
```

#### 안전한 파일 덮어쓰기
다운로드 전 기존 파일 자동 삭제:
```batch
if exist "%TEMP%\ffmpeg.zip" del "%TEMP%\ffmpeg.zip"
```

---

## 📝 로그 파일 예시

### 성공적인 설치 로그
```
============================================ 
FFmpeg Manager Log - 2025-11-28 14:10:50.41 
============================================ 

[14:10:50.45] Script started 
[14:10:50.49] Admin confirmed 
[14:10:50.53] Asking user choice 
[14:10:52.35] User selected: Install 
[14:10:52.35] Requesting URL input 
[14:11:05.56] URL received: https://github.com/...
[14:11:12.67] [1/6] Creating folder 
[14:11:12.71] Folder created 
[14:11:12.75] [2/6] Downloading 
[14:11:15.02] Download completed 
[14:11:15.03] [3/6] Extracting 
[14:11:15.64] Extraction completed 
[14:11:15.65] [4/6] Copying files 
[14:11:15.68] ffmpeg.exe 
[14:11:15.68] ffplay.exe 
[14:11:15.68] ffprobe.exe 
[14:11:23.22] Copied 3 files 
[14:11:23.26] [5/6] Checking PATH 
[14:11:23.32] PATH updated
[14:11:23.35] [6/6] Installation completed
```

---

## 🤝 기여하기

이슈와 풀 리퀘스트를 환영합니다!

1. 이 저장소를 포크하세요
2. 기능 브랜치를 만드세요 (`git checkout -b feature/AmazingFeature`)
3. 변경사항을 커밋하세요 (`git commit -m 'Add some AmazingFeature'`)
4. 브랜치에 푸시하세요 (`git push origin feature/AmazingFeature`)
5. 풀 리퀘스트를 열어주세요

---

## 📄 라이선스

이 프로젝트는 MIT 라이선스를 따릅니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

---

## 🙏 감사의 말

- **FFmpeg 팀**: 훌륭한 멀티미디어 프레임워크 제공
- **BtbN**: Windows용 FFmpeg 빌드 제공 ([FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds))
- **모든 기여자들**: 이 프로젝트를 개선하는 데 도움을 주신 분들

---

## 📞 문의

문제가 발생하거나 질문이 있으시면:
- [Issues](../../issues) 페이지에서 이슈 생성
- 이메일: your-email@example.com

---

## 🎯 로드맵

- [ ] GUI 버전 개발
- [ ] 자동 업데이트 기능
- [ ] FFmpeg 버전 선택 기능
- [ ] 다국어 지원 (영어, 한국어, 일본어)
- [ ] 설정 파일 지원

---

<div align="center">

### 💖 이 프로젝트가 도움이 되었다면 ⭐️를 눌러주세요!

---

**제작 아이디어**: 동우 님  
**개발**: Claude (Anthropic AI)

Made with ❤️ for the FFmpeg community

</div>
