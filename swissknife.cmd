@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0swissknife.ps1" %*
exit /b %ERRORLEVEL%
