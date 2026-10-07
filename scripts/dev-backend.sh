#!/usr/bin/env bash
#
# Runs the seven business services locally, without Docker, and optionally the gateway.
#
# For a machine where Docker is not available. It expects PostgreSQL on DB_PORT (55432 by
# default) and starts each service from its built jar, so run `make build` first. Kafka is not
# required: events are switched off here. Redis is optional for the seven services - it is a cache
# they degrade without, and readiness does not depend on it - but the gateway needs it for rate
# limiting, so the gateway starts only when asked for and only when Redis answers.
#
#   scripts/dev-backend.sh                  start the seven services
#   scripts/dev-backend.sh --with-gateway   start them and the gateway (needs Redis)
#   scripts/dev-backend.sh stop             stop everything this script started
#   scripts/dev-backend.sh status           show what is ready; exits non-zero if anything is not
#
# Each jar is copied to RUN_DIR before it runs, so `mvn install` can rewrite target/*.jar while
# the services keep running from their own copies; a JVM whose jar is replaced under it fails
# later, on the first class it had not loaded yet. Logs go to LOG_DIR, beside the local database
# rather than under /tmp, which macOS sweeps; the previous run's log is kept as <name>.log.1.

set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd -P)"

SERVICES=(
  identity-service:8081
  org-service:8082
  orchestrator-service:8083
  memory-service:8084
  knowledge-service:8085
  integrations-service:8086
  analytics-service:8087
)
GATEWAY=gateway:8080

RUN_DIR="${RUN_DIR:-$HOME/.aiwos-dev/run}"
LOG_DIR="${LOG_DIR:-$HOME/.aiwos-dev/logs}"
# Seven JVMs booting at once on a laptop take a while; the wait ends early once all are ready.
READY_TIMEOUT="${READY_TIMEOUT:-180}"
# How long a service gets to finish its graceful shutdown (25 s per phase) before it is killed.
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"
mkdir -p "$RUN_DIR" "$LOG_DIR"

readiness_url() {
  echo "http://127.0.0.1:$1/actuator/health/readiness"
}

# Ready means the readiness group answers 200. -f turns a 503 (a service that is up but not
# ready, for example without its database) into a failure instead of counting it as running.
is_ready() {
  curl -fsS -m 2 -o /dev/null "$(readiness_url "$1")" 2>/dev/null
}

pid_file() {
  echo "$RUN_DIR/$1.pid"
}

# A PID file outlives a crash or a reboot, and the system may since have handed its PID to an
# unrelated program. The PID counts only while that process is still running this service's
# copied jar; otherwise the file is stale, is removed, and nothing is ever signalled through it.
running_pid() {
  local name="$1" file pid
  file="$(pid_file "$name")"
  [[ -f "$file" ]] || return 1
  pid="$(cat "$file" 2>/dev/null || true)"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null \
    && ps -ww -o command= -p "$pid" 2>/dev/null | grep -qF -- "$RUN_DIR/$name.jar"; then
    echo "$pid"
    return 0
  fi
  rm -f "$file"
  return 1
}

# Services started before this script wrote PID files run straight from target/. They are found
# by their command line, but only when it is this checkout's jar - an absolute path into it, or a
# relative one run from its root - so a second clone's services are never touched.
legacy_pids() {
  local name="$1" pid cwd
  for pid in $(pgrep -f "services/$name/target/$name.jar" 2>/dev/null || true); do
    if ps -ww -o command= -p "$pid" 2>/dev/null | grep -qF -- "$ROOT/services/$name/target/$name.jar"; then
      echo "$pid"
      continue
    fi
    cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)"
    [[ "$cwd" == "$ROOT" ]] && echo "$pid"
  done
  return 0
}

listener_on() {
  command -v lsof >/dev/null 2>&1 || return 1
  lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -n 1
}

report() {
  local name="$1" port="$2"
  if is_ready "$port"; then
    printf "  up    %-22s http://localhost:%s\n" "$name" "$port"
    return 0
  fi
  printf "  down  %-22s (log: %s/%s.log)\n" "$name" "$LOG_DIR" "$name"
  return 1
}

status() {
  local down=0 entry
  for entry in "${SERVICES[@]}"; do
    report "${entry%%:*}" "${entry##*:}" || down=$((down + 1))
  done
  # The gateway is optional, so it is reported only when it was started or something answers on
  # its port; an absent gateway is not a failure.
  local gateway_name="${GATEWAY%%:*}" gateway_port="${GATEWAY##*:}"
  if running_pid "$gateway_name" >/dev/null || [[ -n "$(listener_on "$gateway_port")" ]]; then
    report "$gateway_name" "$gateway_port" || down=$((down + 1))
  else
    printf "  off   %-22s (start with --with-gateway)\n" "$gateway_name"
  fi
  [[ $down -eq 0 ]]
}

# Sends SIGTERM to every service at once, waits for them to finish their graceful shutdown, and
# only then SIGKILLs whatever is left, so a restart never races an old JVM for its port.
stop() {
  local entry name pid legacy pids=() names=()
  for entry in "${SERVICES[@]}" "$GATEWAY"; do
    name="${entry%%:*}"
    if pid="$(running_pid "$name")"; then
      pids+=("$pid"); names+=("$name")
    fi
    rm -f "$(pid_file "$name")"
    # One `stop` also clears a stack started the old way, straight from target/.
    for legacy in $(legacy_pids "$name"); do
      pids+=("$legacy"); names+=("$name")
    done
  done

  if [[ ${#pids[@]} -gt 0 ]]; then
    kill -TERM "${pids[@]}" 2>/dev/null || true
    local waited=0 alive i
    while :; do
      alive=0
      for pid in "${pids[@]}"; do
        kill -0 "$pid" 2>/dev/null && alive=$((alive + 1))
      done
      [[ $alive -eq 0 || $waited -ge $STOP_TIMEOUT ]] && break
      sleep 1
      waited=$((waited + 1))
    done
    for i in "${!pids[@]}"; do
      if kill -0 "${pids[$i]}" 2>/dev/null; then
        echo "  ${names[$i]} (pid ${pids[$i]}) did not stop within ${STOP_TIMEOUT}s; killing it." >&2
        kill -KILL "${pids[$i]}" 2>/dev/null || true
      fi
    done
  fi
  echo "Stopped."
}

# Run for every service before any is launched, so a busy port leaves nothing half started.
check_port_free() {
  local name="$1" port="$2" holder
  holder="$(listener_on "$port" || true)"
  if [[ -n "$holder" ]]; then
    echo "Port $port, needed by $name, is already in use by process $holder." >&2
    echo "Stop it first ('scripts/dev-backend.sh stop', or 'kill $holder'), then try again." >&2
    exit 1
  fi
}

launch() {
  local name="$1" port="$2"
  local jar="services/$name/target/$name.jar"
  local copy="$RUN_DIR/$name.jar"
  local log="$LOG_DIR/$name.log"

  # Copied under a temporary name and moved into place, so a JVM still reading the previous copy
  # keeps its own file rather than seeing it rewritten.
  cp "$jar" "$copy.tmp"
  mv -f "$copy.tmp" "$copy"

  [[ -f "$log" ]] && mv -f "$log" "$log.1"
  printf '=== %s: %s started from %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$name" "$copy" >"$log"

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
    java -jar "$copy" >>"$log" 2>&1 &
  echo $! >"$(pid_file "$name")"
}

with_gateway=false
command="start"
for arg in "$@"; do
  case "$arg" in
    start | stop | status) command="$arg" ;;
    --with-gateway) with_gateway=true ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: scripts/dev-backend.sh [start|stop|status] [--with-gateway]" >&2
      exit 2
      ;;
  esac
done

case "$command" in
  stop)
    stop
    exit 0
    ;;
  status)
    status
    exit $?
    ;;
esac

TO_START=("${SERVICES[@]}")
if $with_gateway; then
  if command -v redis-cli >/dev/null 2>&1 && redis-cli -h "${REDIS_HOST:-127.0.0.1}" -p "${REDIS_PORT:-6379}" ping 2>/dev/null | grep -q PONG; then
    TO_START+=("$GATEWAY")
  else
    echo "Not starting the gateway: Redis does not answer on ${REDIS_HOST:-127.0.0.1}:${REDIS_PORT:-6379}." >&2
    echo "Start Redis (for example 'brew services start redis') and run this again with --with-gateway." >&2
  fi
fi

for entry in "${TO_START[@]}"; do
  name="${entry%%:*}"
  if [[ ! -f "services/$name/target/$name.jar" ]]; then
    echo "Missing services/$name/target/$name.jar. Run 'make build' first." >&2
    exit 1
  fi
done

stop >/dev/null
for entry in "${TO_START[@]}"; do
  check_port_free "${entry%%:*}" "${entry##*:}"
done
for entry in "${TO_START[@]}"; do
  launch "${entry%%:*}" "${entry##*:}"
done

echo "Starting ${#TO_START[@]} services (logs in $LOG_DIR)..."
started=$SECONDS
pending=("${TO_START[@]}")
while :; do
  # Written for the bash 3.2 macOS ships, where expanding an empty array under set -u is an error.
  still=()
  exited=0
  for entry in "${pending[@]}"; do
    is_ready "${entry##*:}" && continue
    still+=("$entry")
    running_pid "${entry%%:*}" >/dev/null || exited=$((exited + 1))
  done
  [[ ${#still[@]} -eq 0 ]] && break
  pending=("${still[@]}")
  # Stop waiting once the time is up, or once every service still pending has already exited:
  # a process that is gone will never become ready.
  if [[ $((SECONDS - started)) -ge $READY_TIMEOUT || $exited -eq ${#pending[@]} ]]; then
    echo "" >&2
    echo "Not ready after $((SECONDS - started))s:" >&2
    for entry in "${pending[@]}"; do
      name="${entry%%:*}"
      state="still starting"
      running_pid "$name" >/dev/null || state="exited"
      echo "" >&2
      echo "--- $name (port ${entry##*:}, $state): last 20 lines of $LOG_DIR/$name.log" >&2
      tail -n 20 "$LOG_DIR/$name.log" >&2 || true
    done
    echo "" >&2
    status || true
    exit 1
  fi
  sleep 3
done

status
