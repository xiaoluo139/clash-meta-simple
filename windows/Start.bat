@echo off
cd /d "%~dp0"
echo Starting Clash Simple for Windows...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0SimpleClash.ps1"
if errorlevel 1 (
  echo.
  echo Failed to start. Please report the messages above.
  pause
)