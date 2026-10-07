@echo off
rem Double-click to start AI Workforce OS on Windows.
rem Needs only Docker Desktop. Builds and starts every service, then opens the app in the browser.
rem The first launch builds everything and takes several minutes; later launches take about one.
rem
rem From a command prompt it also takes options:
rem   --demo / --no-demo   load the sample workspace and demo sign-ins, or not (default: on)
rem   --new-keys           replace an older install's published encryption key with a private one
rem and reads AIWOS_WEB_PORT (default 4173) and AIWOS_EXPOSE_LAN=1, which opens the app to other
rem computers on the network and is refused while demo data is on.
rem
rem The first start writes private settings, including the encryption key for stored credentials,
rem to infra\launcher\.env (see infra\launcher\prepare-env.ps1). Keep that file.

setlocal EnableExtensions
cd /d "%~dp0"
if "%AIWOS_WEB_PORT%"=="" set "AIWOS_WEB_PORT=4173"
set "URL=http://localhost:%AIWOS_WEB_PORT%"
set "ENV_FILE=infra\launcher\.env"
set "COMPOSE=docker compose --env-file %ENV_FILE% -f infra\launcher\docker-compose.yml"

echo.
echo   AI Workforce OS
echo   ---------------

where docker >nul 2>&1
if errorlevel 1 (
  start "" "https://www.docker.com/products/docker-desktop/"
  echo.
  echo   Docker Desktop is not installed. Install it from the page just opened,
  echo   start it once, then double-click this file again.
  goto :fail
)

docker info >nul 2>&1
if not errorlevel 1 goto :docker_ready
echo   Starting Docker Desktop...
if exist "%ProgramFiles%\Docker\Docker\Docker Desktop.exe" start "" "%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
for /l %%i in (1,1,90) do (
  docker info >nul 2>&1 && goto :docker_ready
  timeout /t 2 /nobreak >nul
)
echo.
echo   Docker did not start within three minutes. Open Docker Desktop, wait for it
echo   to say it is running, then try again.
goto :fail

:docker_ready
docker compose version >nul 2>&1
if errorlevel 1 (
  echo.
  echo   This Docker installation has no "docker compose". Update Docker Desktop and try again.
  goto :fail
)

rem Below 6 GB, eight JVMs, PostgreSQL, Redis and Qdrant start swapping or being killed. Docker
rem Desktop reports about 256 MB less than its Memory setting, so the warning starts at 5.5 GB:
rem a 6 GB setting passes it. The comparison runs in PowerShell because cmd's arithmetic stops
rem at 2 GB.
powershell -NoProfile -Command "$m = [int64]0; [void][int64]::TryParse(((docker info --format '{{.MemTotal}}') | Out-String).Trim(), [ref]$m); if ($m -gt 0 -and $m -lt 5.5GB) { exit 1 } else { exit 0 }"
if not errorlevel 1 goto :memory_ok
echo.
echo   WARNING: Docker has less than 6 GB of memory, and AI Workforce OS needs at least 6 GB.
echo   It may start slowly or stop with out-of-memory errors. To give Docker more: open
echo   Docker Desktop, choose Settings, then Resources, and set Memory to 6 GB or more. With the
echo   WSL 2 engine, add "memory=6GB" under [wsl2] in %UserProfile%\.wslconfig instead, then
echo   restart Docker Desktop and double-click this file again.
echo.
:memory_ok

rem On a first start, ask about demo data unless an option or AIWOS_DEMO_ENABLED already says.
rem An install from before the settings file existed is not asked: it always had demo data.
set "DEMO_FLAG="
for %%a in (%*) do (
  if /i "%%~a"=="--demo" set "DEMO_FLAG=--demo"
  if /i "%%~a"=="--no-demo" set "DEMO_FLAG=--no-demo"
)
set "ASK_FLAG="
if exist "%ENV_FILE%" goto :prepare
if not "%DEMO_FLAG%"=="" goto :prepare
if not "%AIWOS_DEMO_ENABLED%"=="" goto :prepare
docker volume inspect aiwos-app_postgres-data >nul 2>&1
if not errorlevel 1 goto :prepare
echo.
echo   Load the demo workspace? It adds sample data and five demo sign-ins that share a
echo   published password, which is what a sales demo needs. Answer N for a private install.
choice /C YN /T 60 /D Y /M "  Load demo data"
if errorlevel 2 (set "ASK_FLAG=--no-demo") else (set "ASK_FLAG=--demo")

:prepare
powershell -NoProfile -ExecutionPolicy Bypass -File infra\launcher\prepare-env.ps1 %* %ASK_FLAG%
if errorlevel 3 (
  echo.
  echo   Nothing was started.
  goto :fail
)
if errorlevel 1 (
  echo.
  echo   The launcher could not prepare its settings in %ENV_FILE%.
  goto :fail
)

set "DEMO="
for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"AIWOS_DEMO_ENABLED=" "%ENV_FILE%"') do set "DEMO=%%b"
rem The normalised value wins over whatever spelling the shell had: the services switch demo data
rem on only for exactly "true".
set "AIWOS_DEMO_ENABLED=%DEMO%"

rem Set here on every start, so the web port is published beyond this computer only on request.
rem prepare-env.ps1 has already refused sharing while demo sign-ins exist.
set "AIWOS_BIND_ADDRESS=127.0.0.1"
for %%v in (1 true yes y on) do if /i "%AIWOS_EXPOSE_LAN%"=="%%v" set "AIWOS_BIND_ADDRESS=0.0.0.0"

echo   Building and starting all services. The first run takes several minutes.
echo.
rem --remove-orphans clears containers an older launcher started and this one no longer runs.
%COMPOSE% up -d --build --wait --wait-timeout 900 --remove-orphans
if errorlevel 1 (
  echo.
  echo   Some services did not start. Run this in this folder to see why:
  echo   %COMPOSE% logs
  goto :fail
)

echo.
echo   Ready: %URL%
if /i "%DEMO%"=="true" (
  echo   Demo sign-in: owner@demo.aiworkforce.os / demo-workspace-2026
  echo   Demo data is on. To start without it, run this file from a command prompt with --no-demo.
) else (
  echo   Create your workspace at %URL%/create-workspace
)
if "%AIWOS_BIND_ADDRESS%"=="0.0.0.0" echo   Shared on the network: other computers can open http://%COMPUTERNAME%:%AIWOS_WEB_PORT%
echo   To stop everything, double-click "Stop AI Workforce OS.bat".
rem Older launchers kept an event log in this volume; nothing reads it now. It is left for the
rem user to remove rather than deleted here.
docker volume inspect aiwos-app_redpanda-data >nul 2>&1
if errorlevel 1 goto :no_old_volume
echo   An older version left an unused volume. To free its disk space, run:
echo     docker volume rm aiwos-app_redpanda-data
:no_old_volume
echo.
start "" "%URL%"
timeout /t 15 >nul
exit /b 0

:fail
echo.
pause
exit /b 1
