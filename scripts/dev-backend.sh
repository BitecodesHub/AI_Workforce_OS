#!/usr/bin/env bash
#
# Runs the seven business services locally, without Docker.
#
# For a machine where Docker is not available. It expects PostgreSQL on DB_PORT (55432 by
# default) and starts each service from its built jar, so run `make build` first. Redis and
# Kafka are not required: the health checks that would need them are switched off here, and the
# platform degrades rather than failing when they are absent.
#
#   scripts/dev-backend.sh          start everything
#   scripts/dev-backend.sh stop     stop everything
#   scripts/dev-backend.sh status   show what is running

set -euo pipefail
cd "$(dirname "$0")/.."

SERVICES=(
  identity-service:8081
  org-service:8082
  orchestrator-service:8083
  memory-service:8084
  knowledge-service:8085
  integrations-service:8086
  analytics-service:8087
)
LOG_DIR="${LOG_DIR:-/tmp/aiwos-logs}"
mkdir -p "$LOG_DIR"

status() {
  for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"; port="${entry##*:}"
    if curl -s -m 2 -o /dev/null "http://127.0.0.1:$port/actuator/health"; then
      printf "  up    %-22s http://localhost:%s\n" "$name" "$port"
    else
      printf "  down  %-22s (log: %s/%s.log)\n" "$name" "$LOG_DIR" "$name"
    fi
  done
}

stop() {
  pkill -f "target/.*-service.jar" 2>/dev/null || true
  echo "Stopped."
}

case "${1:-start}" in
  stop)   stop; exit 0 ;;
  status) status; exit 0 ;;
esac

stop >/dev/null
for entry in "${SERVICES[@]}"; do
  name="${entry%%:*}"; port="${entry##*:}"
  jar="services/$name/target/$name.jar"
  if [[ ! -f "$jar" ]]; then
    echo "Missing $jar. Run 'make build' first." >&2
    exit 1
  fi
  nohup env \
    DB_HOST="${DB_HOST:-127.0.0.1}" DB_PORT="${DB_PORT:-55432}" \
    DB_USER="${DB_USER:-aiwos}" DB_PASSWORD="${DB_PASSWORD:-aiwos}" DB_NAME="${DB_NAME:-aiwos}" \
    SERVER_PORT="$port" \
    AIWOS_JWKS_URI="http://127.0.0.1:8081/.well-known/jwks.json" \
    AIWOS_URL_IDENTITY="http://127.0.0.1:8081" AIWOS_URL_ORGANISATION="http://127.0.0.1:8082" \
    AIWOS_URL_ORCHESTRATOR="http://127.0.0.1:8083" AIWOS_URL_MEMORY="http://127.0.0.1:8084" \
    AIWOS_URL_KNOWLEDGE="http://127.0.0.1:8085" AIWOS_URL_INTEGRATIONS="http://127.0.0.1:8086" \
    AIWOS_URL_ANALYTICS="http://127.0.0.1:8087" \
    AIWOS_EVENTS_ENABLED=false SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
    MANAGEMENT_HEALTH_REDIS_ENABLED=false \
    MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db \
    java -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
done

echo "Starting seven services..."
for _ in $(seq 1 30); do
  sleep 3
  up=0
  for entry in "${SERVICES[@]}"; do
    curl -s -m 2 -o /dev/null "http://127.0.0.1:${entry##*:}/actuator/health" && up=$((up + 1))
  done
  [[ $up -eq ${#SERVICES[@]} ]] && break
done
status
