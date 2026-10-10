#!/usr/bin/env bash
# @find: stop app mac, launcher, one-click stop, shut down, docker compose stop, keep data
# @what: macOS and Linux one-click script that stops the launcher stack while keeping all data for the next start.
# @flow: Calls docker compose stop on infra/launcher/docker-compose.yml
# Double-click to stop AI Workforce OS. Data is kept for the next start.
cd "$(dirname "$0")" || exit 1
docker compose -f infra/launcher/docker-compose.yml stop
echo ""
echo "  AI Workforce OS has stopped."
read -r -t 10 -p "This window closes in 10 seconds." _ || true
