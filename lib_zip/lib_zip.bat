@echo off
setlocal EnableExtensions EnableDelayedExpansion

pushd "%~dp0" || (echo FAIL: pushd & pause & exit /b 1)

set "SCRIPT_VER=v3"
set "LIB=%CD%\app\build\intermediates\merged_native_libs\release\mergeReleaseNativeLibs\out\lib"
set "ZIP=%CD%\lib.zip"
set "LOG=%CD%\lib_zip_log.txt"

echo [%SCRIPT_VER%] Working dir : "%CD%"
echo [%SCRIPT_VER%] Target lib  : "%LIB%"
echo [%SCRIPT_VER%] Output zip  : "%ZIP%"
echo.

> "%LOG%" echo === %DATE% %TIME% / %SCRIPT_VER% ===
>>"%LOG%" echo WorkingDir=%CD%
>>"%LOG%" echo LIB=%LIB%
>>"%LOG%" echo ZIP=%ZIP%

echo [1/5] Check lib folder...
if not exist "%LIB%\" (
  echo ERROR: lib folder not found.
  echo See log: "%LOG%"
  >>"%LOG%" echo ERROR: lib folder not found.
  pause
  popd
  exit /b 1
)

echo [2/5] Delete old zip if exists...
if exist "%ZIP%" del /f /q "%ZIP%" >>"%LOG%" 2>&1

echo [3/5] Create zip (PowerShell Compress-Archive)...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "try { $ErrorActionPreference='Stop'; $lib='%LIB%'; $zip='%ZIP%'; if(Test-Path $zip){Remove-Item $zip -Force}; Compress-Archive -Path $lib -DestinationPath $zip -Force; 'PS:OK' } catch { 'PS:ERROR: ' + $_.Exception.Message; exit 1 }" >>"%LOG%" 2>&1

set "PSCODE=!ERRORLEVEL!"
>>"%LOG%" echo PowerShellExitCode=!PSCODE!

echo [4/5] Verify output...
if exist "%ZIP%" (
  echo OK: "%ZIP%"
  >>"%LOG%" echo OK: zip created
) else (
  echo FAIL: zip not created.
  echo Check log: "%LOG%"
  >>"%LOG%" echo FAIL: zip not created
)

echo [5/5] Done.
pause
popd
endlocal
