@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul 2>&1

title Android AVD GPU Mode Fix

set "SCRIPT=%~f0"
set "ROOT=%USERPROFILE%\.android\avd"
set "LOG=%~dp0avd_gpu_fix_log.txt"
set "FOUND=0"
set "CHANGED=0"
set "SKIPPED=0"
set "FAILED=0"

call :log "==== START %date% %time% ===="
call :log "SCRIPT=%SCRIPT%"
call :log "USERPROFILE=%USERPROFILE%"
call :log "ROOT=%ROOT%"

echo.
echo [INFO] ROOT = %ROOT%

if not exist "%ROOT%" (
    echo [ERROR] Folder not found.
    call :log "ERROR: Folder not found."
    goto :summary
)

for /d %%D in ("%ROOT%\*.avd") do (
    set /a FOUND+=1
    set "CFG=%%~fD\config.ini"

    echo.
    echo [CHECK] %%~fD
    call :log "Checking folder: %%~fD"

    if exist "!CFG!" (
        echo [FOUND] !CFG!
        call :log "Found config: !CFG!"

        powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
          "$p = [string]::Copy($env:CFG);" ^
          "$bak = $p + '.bak';" ^
          "Copy-Item -LiteralPath $p -Destination $bak -Force;" ^
          "$content = Get-Content -LiteralPath $p -Raw -Encoding UTF8;" ^
          "if ($content -match '(?m)^hw\.gpu\.mode=auto$') {" ^
          "  $new = [regex]::Replace($content, '(?m)^hw\.gpu\.mode=auto$', 'hw.gpu.mode=host', 1);" ^
          "  [System.IO.File]::WriteAllText($p, $new, (New-Object System.Text.UTF8Encoding($false)));" ^
          "  exit 10" ^
          "} else {" ^
          "  exit 20" ^
          "}"

        set "RC=!errorlevel!"

        if "!RC!"=="10" (
            set /a CHANGED+=1
            echo [CHANGED] hw.gpu.mode=auto ^> host
            call :log "CHANGED: !CFG!"
        ) else if "!RC!"=="20" (
            set /a SKIPPED+=1
            echo [SKIP] Target text not found.
            call :log "SKIP: Target text not found in !CFG!"
        ) else (
            set /a FAILED+=1
            echo [ERROR] PowerShell failed. code=!RC!
            call :log "ERROR: PowerShell failed. code=!RC! file=!CFG!"
        )
    ) else (
        set /a FAILED+=1
        echo [ERROR] config.ini not found.
        call :log "ERROR: config.ini not found in %%~fD"
    )
)

:summary
echo.
echo ===== SUMMARY =====
echo Folders found : %FOUND%
echo Files changed : %CHANGED%
echo Files skipped : %SKIPPED%
echo Errors        : %FAILED%
echo Log file      : %LOG%
call :log "SUMMARY: FOUND=%FOUND% CHANGED=%CHANGED% SKIPPED=%SKIPPED% FAILED=%FAILED%"
call :log "==== END %date% %time% ===="

echo.
echo A backup file is created as config.ini.bak before editing.
echo Press any key to close.
pause >nul
exit /b

:log
echo %~1>>"%LOG%"
exit /b
