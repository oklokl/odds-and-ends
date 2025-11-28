@echo off
setlocal enabledelayedexpansion

:: Log files in Downloads folder
set "LOG=%USERPROFILE%\Downloads\ffmpeg_log.txt"
set "ERR=%USERPROFILE%\Downloads\ffmpeg_error.txt"

echo ============================================ > "%LOG%"
echo FFmpeg Manager Log - %date% %time% >> "%LOG%"
echo ============================================ >> "%LOG%"
echo. >> "%LOG%"

echo [%time%] Script started >> "%LOG%"

:: Check admin
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [%time%] No admin rights >> "%LOG%"
    echo.
    echo ============================================
    echo   Administrator rights required!
    echo ============================================
    echo.
    echo Please RIGHT-CLICK this file and select
    echo "Run as administrator"
    echo.
    echo Press any key to auto-request admin rights...
    pause
    echo [%time%] Requesting admin >> "%LOG%"
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo [%time%] Admin confirmed >> "%LOG%"

title FFmpeg Manager

cls
echo.
echo ============================================
echo    FFmpeg Manager
echo ============================================
echo.
echo Log: %LOG%
echo.

:: Ask Install or Uninstall
echo [%time%] Asking user choice >> "%LOG%"
echo 1. Install (Install FFmpeg)
echo 2. Uninstall (Remove FFmpeg)
echo.
choice /C 12 /M "Select"

if errorlevel 2 goto UNINSTALL
if errorlevel 1 goto INSTALL

:INSTALL
echo [%time%] User selected: Install >> "%LOG%"
echo.
echo ============================================
echo   Install Mode
echo ============================================
echo.

:: Get URL using PowerShell (empty input box)
echo [%time%] Requesting URL input >> "%LOG%"
echo URL input window will appear...
echo Please enter FFmpeg download URL.
echo.

powershell -Command "Add-Type -AssemblyName Microsoft.VisualBasic; $url = [Microsoft.VisualBasic.Interaction]::InputBox('Enter FFmpeg download URL:', 'FFmpeg Installer', ''); if ($url) { $url } else { exit 1 }" > "%TEMP%\url.txt" 2>> "%ERR%"

if %errorlevel% neq 0 (
    echo [%time%] URL input cancelled >> "%LOG%"
    echo.
    echo URL input was cancelled.
    echo.
    del "%TEMP%\url.txt" 2>nul
    pause
    exit /b
)

set /p URL=<"%TEMP%\url.txt"
del "%TEMP%\url.txt" 2>nul

if "%URL%"=="" (
    echo [%time%] URL is empty >> "%LOG%"
    echo.
    echo ERROR: URL is empty!
    echo.
    pause
    exit /b
)

echo [%time%] URL received: %URL% >> "%LOG%"
echo.
echo URL: %URL%
echo.

:: Check existing folder
if exist "%ProgramFiles%\ffmpeg" (
    echo [%time%] Existing folder found >> "%LOG%"
    echo.
    echo FFmpeg folder already exists.
    echo.
    choice /C YN /M "Overwrite (Y/N)"
    if errorlevel 2 (
        echo [%time%] User cancelled >> "%LOG%"
        echo.
        echo Installation cancelled.
        pause
        exit /b
    )
    echo [%time%] Deleting old folder >> "%LOG%"
    echo Deleting old folder...
    rd /s /q "%ProgramFiles%\ffmpeg" 2>> "%ERR%"
)

:: Step 1: Create folder
echo.
echo ============================================
echo [1/6] Creating folder...
echo ============================================
echo [%time%] [1/6] Creating folder >> "%LOG%"
md "%ProgramFiles%\ffmpeg" 2>> "%ERR%"
if %errorlevel% neq 0 (
    echo [%time%] ERROR: Folder creation failed >> "%LOG%"
    echo.
    echo ERROR: Cannot create folder!
    echo.
    if exist "%ERR%" type "%ERR%"
    pause
    exit /b
)
echo [%time%] Folder created >> "%LOG%"
echo Done!

:: Step 2: Download
echo.
echo ============================================
echo [2/6] Downloading...
echo ============================================
echo [%time%] [2/6] Downloading >> "%LOG%"
echo URL: %URL%
echo.

:: Delete existing file if present
if exist "%TEMP%\ffmpeg.zip" (
    echo [%time%] Deleting existing download file >> "%LOG%"
    del "%TEMP%\ffmpeg.zip" 2>nul
)

echo Please wait, downloading...

powershell -Command "$p='SilentlyContinue';$ProgressPreference=$p;Invoke-WebRequest -Uri '%URL%' -OutFile '%TEMP%\ffmpeg.zip' -UseBasicParsing" 2>> "%ERR%"

if %errorlevel% neq 0 (
    echo [%time%] ERROR: Download failed >> "%LOG%"
    echo.
    echo ERROR: Download failed!
    echo Please check URL and internet connection.
    echo.
    if exist "%ERR%" type "%ERR%"
    pause
    exit /b
)
echo [%time%] Download completed >> "%LOG%"
echo Done!

:: Check file exists
if not exist "%TEMP%\ffmpeg.zip" (
    echo [%time%] ERROR: File not found >> "%LOG%"
    echo.
    echo ERROR: Downloaded file not found!
    pause
    exit /b
)

:: Step 3: Extract
echo.
echo ============================================
echo [3/6] Extracting archive...
echo ============================================
echo [%time%] [3/6] Extracting >> "%LOG%"
tar -xf "%TEMP%\ffmpeg.zip" -C "%ProgramFiles%\ffmpeg" 2>> "%ERR%"
if %errorlevel% neq 0 (
    echo [%time%] ERROR: Extraction failed >> "%LOG%"
    echo.
    echo ERROR: Extraction failed!
    echo File may be corrupted.
    del "%TEMP%\ffmpeg.zip" 2>nul
    echo.
    if exist "%ERR%" type "%ERR%"
    pause
    exit /b
)
echo [%time%] Extraction completed >> "%LOG%"
echo Done!
del "%TEMP%\ffmpeg.zip" 2>nul

:: Step 4: Copy files
echo.
echo ============================================
echo [4/6] Copying files to bin folder...
echo ============================================
echo [%time%] [4/6] Copying files >> "%LOG%"
if not exist "%ProgramFiles%\ffmpeg\bin" (
    md "%ProgramFiles%\ffmpeg\bin"
)

set COUNT=0
echo.
for /f "delims=" %%a in ('dir /a-d /b /s "%ProgramFiles%\ffmpeg\*.exe" 2^>nul') do (
    copy /y "%%a" "%ProgramFiles%\ffmpeg\bin\" >nul 2>&1
    echo Copied: %%~nxa
    echo [%time%] %%~nxa >> "%LOG%"
    set /a COUNT+=1
)

for /f "delims=" %%a in ('dir /a-d /b /s "%ProgramFiles%\ffmpeg\*.dll" 2^>nul') do (
    copy /y "%%a" "%ProgramFiles%\ffmpeg\bin\" >nul 2>&1
    echo [%time%] DLL: %%~nxa >> "%LOG%"
)

echo.
echo [%time%] Copied %COUNT% files >> "%LOG%"
echo Done! (Total: %COUNT% files)

:: Step 5: Set PATH
echo.
echo ============================================
echo [5/6] Setting environment variable...
echo ============================================
echo [%time%] [5/6] Checking PATH >> "%LOG%"

:: Check if already in PATH
echo %PATH% | find /i "%ProgramFiles%\ffmpeg\bin" >nul
if %errorlevel% equ 0 (
    echo [%time%] PATH already contains ffmpeg\bin >> "%LOG%"
    echo FFmpeg path already in PATH.
    echo Skipping PATH registration.
) else (
    echo [%time%] Adding to PATH >> "%LOG%"
    echo Adding to PATH...
    
    :: Use PowerShell to modify registry directly (no length limit)
    powershell -Command "$path = [Environment]::GetEnvironmentVariable('Path', 'User'); if ($path -notlike '*%ProgramFiles%\ffmpeg\bin*') { [Environment]::SetEnvironmentVariable('Path', $path + ';%ProgramFiles%\ffmpeg\bin', 'User') }" 2>> "%ERR%"
    
    if %errorlevel% neq 0 (
        echo [%time%] ERROR: PATH failed >> "%LOG%"
        echo.
        echo ERROR: Failed to set PATH!
        echo.
        if exist "%ERR%" type "%ERR%"
        pause
        exit /b
    )
    echo [%time%] PATH updated >> "%LOG%"
    echo Done!
)

:: Step 6: Complete
echo.
echo ============================================
echo [6/6] Installation Complete!
echo ============================================
echo [%time%] [6/6] Installation completed >> "%LOG%"
echo.
echo ============================================
echo   SUCCESS!
echo ============================================
echo.
echo Install location: %ProgramFiles%\ffmpeg\bin
echo.
echo IMPORTANT: Please RESTART your computer
echo to apply PATH environment variable!
echo.
echo Log: %LOG%
echo.

powershell -Command "Add-Type -AssemblyName System.Windows.Forms;[System.Windows.Forms.MessageBox]::Show('FFmpeg installation complete!`n`nPlease restart your computer.','Installation Complete','OK','Information')" 2>nul

echo [%time%] Installation finished >> "%LOG%"
echo.
echo Press any key to exit...
pause >nul
exit /b

:UNINSTALL
echo [%time%] User selected: Uninstall >> "%LOG%"
echo.
echo ============================================
echo   Uninstall Mode
echo ============================================
echo.

:: Check if FFmpeg exists
if not exist "%ProgramFiles%\ffmpeg" (
    echo [%time%] FFmpeg not found >> "%LOG%"
    echo FFmpeg is not installed.
    echo.
    pause
    exit /b
)

echo [%time%] Starting uninstall >> "%LOG%"
echo Uninstalling FFmpeg...
echo.

:: Step 1: Remove from PATH
echo [1/3] Removing from PATH...
echo [%time%] [1/3] Removing from PATH >> "%LOG%"

:: Get current PATH
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v PATH 2^>nul') do set "CURRENT_PATH=%%b"

:: Remove FFmpeg path
set "NEW_PATH=!CURRENT_PATH:%ProgramFiles%\ffmpeg\bin;=!"
set "NEW_PATH=!NEW_PATH:;%ProgramFiles%\ffmpeg\bin=!"

:: Update PATH
setx PATH "!NEW_PATH!" >nul 2>> "%ERR%"
if %errorlevel% equ 0 (
    echo [%time%] PATH updated successfully >> "%LOG%"
    echo Removed from PATH!
) else (
    echo [%time%] WARNING: PATH update failed >> "%LOG%"
    echo WARNING: PATH update failed
)

:: Step 2: Delete folder
echo.
echo [2/3] Deleting folder...
echo [%time%] [2/3] Deleting folder >> "%LOG%"
rd /s /q "%ProgramFiles%\ffmpeg" 2>> "%ERR%"
if %errorlevel% equ 0 (
    echo [%time%] Folder deleted >> "%LOG%"
    echo Folder deleted!
) else (
    echo [%time%] ERROR: Folder deletion failed >> "%LOG%"
    echo ERROR: Folder deletion failed
)

:: Step 3: Complete
echo.
echo [3/3] Uninstall complete!
echo [%time%] [3/3] Uninstall completed >> "%LOG%"
echo.
echo ============================================
echo   UNINSTALL COMPLETE!
echo ============================================
echo.
echo FFmpeg has been removed.
echo.
echo IMPORTANT: Please RESTART your computer
echo to apply PATH changes!
echo.
echo Log: %LOG%
echo.

powershell -Command "Add-Type -AssemblyName System.Windows.Forms;[System.Windows.Forms.MessageBox]::Show('FFmpeg uninstall complete!`n`nPlease restart your computer.','Uninstall Complete','OK','Information')" 2>nul

echo [%time%] Uninstall finished >> "%LOG%"
echo.
echo Press any key to exit...
pause >nul
exit /b
