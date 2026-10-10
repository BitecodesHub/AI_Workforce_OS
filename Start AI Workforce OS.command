#!/usr/bin/env bash
# @find: start app mac, launcher, one-click start, double-click, docker desktop, run the app, first launch, demo data, --demo, --no-demo, --new-keys, AIWOS_WEB_PORT, AIWOS_EXPOSE_LAN, open browser, memory check
# @what: macOS and Linux one-click launcher that prepares private settings, builds and starts every service with Docker, then opens the app in the browser.
# @flow: Calls infra/launcher/prepare-env.sh then docker compose with infra/launcher/docker-compose.yml
#
# Double-click to start AI Workforce OS on macOS (or run it from a terminal on Linux).
# Needs only Docker Desktop. Builds and starts every service, then opens the app in the browser.
# The first launch builds everything and takes several minutes; later launches take about one.
#
# From a terminal it also takes options:
#   --demo / --no-demo   load the sample workspace and demo sign-ins, or not (default: on)
#   --new-keys           replace an older install's published encryption key with a private one
# and reads AIWOS_WEB_PORT (default 4173) and AIWOS_EXPOSE_LAN=1, which opens the app to other
# computers on the network and is refused while demo data is on.
#
# The first start writes private settings, including the encryption key for stored credentials,
# to infra/launcher/.env (see infra/launcher/prepare-env.sh). Keep that file.

cd "$(dirname "$0")" || exit 1
ENV_FILE="infra/launcher/.env"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f infra/launcher/docker-compose.yml)
PORT="${AIWOS_WEB_PORT:-4173}"
URL="http://localhost:$PORT"
# Below 6 GB, eight JVMs, PostgreSQL, Redis and Qdrant start swapping or being killed. Docker
# Desktop reports about 256 MB less than its Memory setting, so the warning starts at 5.5 GB:
# a 6 GB setting passes it.
MIN_MEMORY_BYTES=$((11 * 512 * 1024 * 1024))

fail() {
  echo ""
  echo "  $1"
  echo ""
  read -r -p "Press Return to close this window." _
  exit 1
}

open_url() {
  if command -v open >/dev/null 2>&1; then open "$1"; else xdg-open "$1" >/dev/null 2>&1 || true; fi
}

env_value() {
  grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n 1 | cut -d= -f2-
}

echo ""
echo "  AI Workforce OS"
echo "  ---------------"

if ! command -v docker >/dev/null 2>&1; then
  open_url "https://www.docker.com/products/docker-desktop/"
  fail "Docker Desktop is not installed. Install it from the page just opened, start it once, then double-click this file again."
fi

if ! docker info >/dev/null 2>&1; then
  echo "  Starting Docker Desktop..."
  open -a Docker 2>/dev/null || true
  for _ in $(seq 1 90); do
    docker info >/dev/null 2>&1 && break
    sleep 2
  done
  docker info >/dev/null 2>&1 || fail "Docker did not start within three minutes. Open Docker Desktop, wait for it to say it is running, then try again."
fi

docker compose version >/dev/null 2>&1 || fail "This Docker installation has no 'docker compose'. Update Docker Desktop and try again."

memory="$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)"
if [[ "$memory" =~ ^[0-9]+$ && "$memory" -gt 0 && "$memory" -lt "$MIN_MEMORY_BYTES" ]]; then
  echo ""
  echo "  WARNING: Docker has $(awk -v b="$memory" 'BEGIN { printf "%.1f", b / 1073741824 }') GB of memory, and AI Workforce OS needs at least 6 GB."
  echo "  It may start slowly or stop with out-of-memory errors. To give Docker more:"
  echo "  open Docker Desktop, choose Settings, then Resources, set Memory to 6 GB or more,"
  echo "  choose Apply and restart, then double-click this file again."
  echo ""
fi

bash infra/launcher/prepare-env.sh "$@"
case $? in
  0) ;;
  3) fail "Nothing was started." ;;
  *) fail "The launcher could not prepare its settings in $ENV_FILE." ;;
esac

demo="$(env_value AIWOS_DEMO_ENABLED)"
# The normalised value wins over whatever spelling the shell had ("1", "yes"): the services switch
# demo data on only for exactly "true".
export AIWOS_DEMO_ENABLED="$demo"
# Set here on every start, so the web port is published beyond this computer only on request;
# a value left in the shell or in .env cannot widen it. prepare-env.sh has already refused
# sharing while demo sign-ins exist.
case "$(printf '%s' "${AIWOS_EXPOSE_LAN:-0}" | tr '[:upper:]' '[:lower:]')" in
  1 | true | yes | y | on) export AIWOS_BIND_ADDRESS=0.0.0.0 ;;
  *) export AIWOS_BIND_ADDRESS=127.0.0.1 ;;
esac

echo "  Building and starting all services. The first run takes several minutes."
echo ""
# --remove-orphans clears containers an older launcher started and this one no longer runs.
"${COMPOSE[@]}" up -d --build --wait --wait-timeout 900 --remove-orphans \
  || fail "Some services did not start. Run '${COMPOSE[*]} logs' in this folder to see why."

echo ""
echo "  Ready: $URL"
if [[ "$demo" == "true" ]]; then
  echo "  Demo sign-in: owner@demo.aiworkforce.os / demo-workspace-2026"
  echo "  Demo data is on. To start without it, run this file from a terminal with --no-demo."
else
  echo "  Create your workspace at $URL/create-workspace"
fi
if [[ "$AIWOS_BIND_ADDRESS" == "0.0.0.0" ]]; then
  address="$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')"
  echo "  Shared on the network: other computers can open http://${address:-this-computer}:$PORT"
fi
echo "  To stop everything, double-click 'Stop AI Workforce OS.command'."
# Older launchers kept an event log in this volume; nothing reads it now. It is left for the
# user to remove rather than deleted here.
if docker volume inspect aiwos-app_redpanda-data >/dev/null 2>&1; then
  echo "  An older version left an unused volume. To free its disk space, run:"
  echo "    docker volume rm aiwos-app_redpanda-data"
fi
echo ""
open_url "$URL"
