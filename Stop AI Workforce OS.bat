@echo off
rem Double-click to stop AI Workforce OS. Data is kept for the next start.
cd /d "%~dp0"
docker compose -f infra\launcher\docker-compose.yml stop
echo.
echo   AI Workforce OS has stopped.
timeout /t 10 >nul
