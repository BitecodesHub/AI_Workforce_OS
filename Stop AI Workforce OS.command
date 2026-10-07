#!/usr/bin/env bash
# Double-click to stop AI Workforce OS. Data is kept for the next start.
cd "$(dirname "$0")" || exit 1
docker compose -f infra/launcher/docker-compose.yml stop
echo ""
echo "  AI Workforce OS has stopped."
read -r -t 10 -p "This window closes in 10 seconds." _ || true
