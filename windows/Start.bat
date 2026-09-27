@echo off
cd /d "%~dp0"

echo ==================================================
echo   Clash Simple for Windows  (Script Edition)
echo ==================================================
echo.
echo Starting... a window will open shortly.
echo Keep this black window open while using the proxy.
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0SimpleClash.ps1"

if errorlevel 1 (
  echo.
  echo [!] Failed to start.
  echo     If Windows Smart App Control blocked it, please read
  echo     README-IMPORTANT.txt in this folder.
  echo.
  pause
)