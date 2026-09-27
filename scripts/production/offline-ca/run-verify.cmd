@echo off
pushd "%~dp0"
if errorlevel 1 exit /b 1
powershell.exe -NoProfile -File "%~dp0offline-ca.ps1" -Action Verify
set "result=%ERRORLEVEL%"
popd
pause
exit /b %result%
