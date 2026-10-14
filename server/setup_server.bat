@echo off
rem First-time setup for an MW2 Crossplay lobby host.
rem Usage: setup_server.bat [path-to-iso] [server-name]
rem   Needs your own Xbox 360 MW2 disc image (v1.0.557, USA/Europe).
rem   Installs ~7 GB of game files into game/ under the folder given at
rem   install time (or beside these files), then writes .env.
cd /d "%~dp0"
if not "%~1"=="" ( set ISO=%~1 ) else ( set /p ISO="Path to MW2 Xbox 360 ISO: " )
if not "%~2"=="" ( set SERVERNAME=%~2 ) else ( set SERVERNAME=CrossplayServer )
if "%ISO%"=="" ( echo No ISO given. & exit /b 1 )
if "%MW2DIR%"=="" ( set MW2DIR=%CD% )
echo Installing game files into %MW2DIR%\game ...
mw2-launcher.exe --install "%ISO%" --install-dir "%MW2DIR%"
if errorlevel 1 ( echo Install failed - see the message above. & exit /b 1 )
> .env echo MW2_NAME=%SERVERNAME%
>> .env echo MW2_LAN_PORT=3074
>> .env echo MW2_LAN_ACCEPT=1
>> .env echo MW2_NO_AUDIO=1
>> .env echo MW2_LOG_FILE=server.log
echo.
echo Done. .env written (name %SERVERNAME%, port 3074).
echo Start the host with start_server.bat, then create the lobby once in
echo the game: MULTIPLAYER -^> SYSTEM LINK (or PLAY ONLINE -^> PRIVATE MATCH).
