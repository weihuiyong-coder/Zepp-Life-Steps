@echo off
setlocal
title Zepp-Life-Steps Local
echo Zepp-Life-Steps - Local Windows launcher
echo Keep this window open while using the webpage.
echo First startup installs dependencies and builds the app.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-Local.ps1" %*
set "launchResult=%ERRORLEVEL%"
echo.
if not "%launchResult%"=="0" echo Startup failed. Check the message above and README.md.
pause
exit /b %launchResult%
