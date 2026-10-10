@echo off
REM @find: stop app windows, launcher, one-click stop, shut down, docker compose stop, keep data
REM @what: Windows one-click script that stops the launcher stack while keeping all data for the next start.
REM @flow: Calls docker compose stop on infra\launcher\docker-compose.yml
rem Double-click to stop AI Workforce OS. Data is kept for the next start.
cd /d "%~dp0"
docker compose -f infra\launcher\docker-compose.yml stop
echo.
echo   AI Workforce OS has stopped.
timeout /t 10 >nul
