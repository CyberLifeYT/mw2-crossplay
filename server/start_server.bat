@echo off
rem Lobby host that stays up: restarts mw2-mp.exe 10 s after it ends.
rem Create the lobby once in the game (SYSTEM LINK or PRIVATE MATCH);
rem this wrapper only keeps the process alive, it cannot re-host a menu.
cd /d "%~dp0"
:loop
for /f "tokens=*" %%t in ('powershell -NoProfile -Command "Get-Date -Format o"') do set NOW=%%t
echo [%NOW%] starting mw2-mp.exe >> server.log
start /wait /high mw2-mp.exe
echo [%NOW%] exited (%errorlevel%), restarting in 10 s >> server.log
timeout /t 10 /nobreak >nul
goto loop
