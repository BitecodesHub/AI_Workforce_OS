#!/usr/bin/env bash
# @find: aws server script, pull deployment, systemd timer, git fetch, build on server, sha image tags, automatic rollback, flock, deploy-status.json, init, generate secrets, /opt/aiwos/.env, status, logs, aiwos.sh
# @what: Operates the AI Workforce OS stack on the EC2 server: first-boot setup with generated secrets, a self-deploying update loop (fetch, build, start, health check, automatic rollback), manual deploy/rollback, status and logs.
# @flow: Called by the CloudFormation user data (init, install); run every 2 minutes by aiwos-update.timer (update); run by hand through SSM Session Manager as "sudo aiwos ..."
#
#   aiwos init                    write /opt/aiwos/.env (secrets made here with openssl, once) and
#                                 the nginx and Caddy files; reapplies settings to a running stack
#   aiwos install                 install /usr/bin/aiwos, the systemd timer and log rotation
#   aiwos update [--force]        what the timer runs: deploy origin/<branch> if its SHA changed
#   aiwos deploy SHA              build (if needed) and deploy one commit, with automatic rollback
#   aiwos rollback [SHA]          go back to the previous deployed commit (or SHA) and hold there
#                                 until a new commit is pushed
#   aiwos status                  deploy status, containers, memory and disk
#   aiwos logs [SERVICE...]       follow container logs (all services when none is named)
#   aiwos url                     print the public address
#
# Runs as root. Each deploy builds the images on this server from the checked-out commit, one
# service at a time, tags them aiwos/<image>:<sha>, starts them and waits for health; if the stack
# is not healthy within AIWOS_HEALTH_TIMEOUT seconds (default 600) it starts the previous commit's
# images again. Images of the current and previous commit are kept. Runs never overlap (flock).
# Logs: journalctl -u aiwos-update and /var/log/aiwos-deploy.log (rotated). The status file
# served at https://<host>/deploy-status.json holds SHAs, times and results only, never secrets.

set -euo pipefail

AIWOS_HOME="${AIWOS_HOME:-/opt/aiwos}"
REPO="$AIWOS_HOME/repo"
ENV_FILE="$AIWOS_HOME/.env"
BOOTSTRAP_ENV="$AIWOS_HOME/bootstrap.env"
STATE_FILE="$AIWOS_HOME/deploy-state"
STATUS_DIR="$AIWOS_HOME/status"
STATUS_FILE="$STATUS_DIR/deploy-status.json"
CADDY_DIR="$AIWOS_HOME/config/caddy"
LOG_FILE="${AIWOS_LOG_FILE:-/var/log/aiwos-deploy.log}"
LOCK_FILE="/run/aiwos-deploy.lock"
COMPOSE_FILE="$REPO/infra/aws/docker-compose.prod.yml"
HEALTH_TIMEOUT="${AIWOS_HEALTH_TIMEOUT:-600}"
FIRST_HEALTH_TIMEOUT="${AIWOS_FIRST_HEALTH_TIMEOUT:-1800}"
CORE_SERVICES="identity organisation orchestrator memory knowledge integrations analytics web caddy"
APP_SERVICES="identity organisation orchestrator memory knowledge integrations analytics web"

log() {
  local line
  line="[aiwos $(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"
  printf '%s\n' "$line"
  printf '%s\n' "$line" >>"$LOG_FILE" 2>/dev/null || true
}
die() { log "ERROR: $*"; exit 1; }
now() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
require_root() { [[ $EUID -eq 0 ]] || die "Run as root: sudo aiwos $*"; }

compose() {
  docker compose --project-name aiwos --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

is_true() {
  case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
    1 | true | yes | on) return 0 ;;
    *) return 1 ;;
  esac
}

# ---- small KEY=VALUE files (.env and the deploy state) ------------------------------------------

kv_get() {
  grep -E "^$2=" "$1" 2>/dev/null | tail -n 1 | cut -d= -f2- || true
}

# Replaces KEY's line, or appends it, through a temporary file so a crash never leaves half a file.
kv_set() {
  local file="$1" key="$2" value="$3" tmp="$1.tmp"
  value="$(printf '%s' "$value" | tr '\n' ' ')"
  [[ -f "$file" ]] || (umask 077 && : >"$file")
  awk -v key="$key" -v value="$value" '
    BEGIN { done = 0 }
    index($0, key "=") == 1 { if (!done) { print key "=" value; done = 1 }; next }
    { print }
    END { if (!done) print key "=" value }
  ' "$file" >"$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$file"
}

env_value() { kv_get "$ENV_FILE" "$1"; }
set_env_value() { kv_set "$ENV_FILE" "$1" "$2"; }
state() { kv_get "$STATE_FILE" "$1"; }
set_state() { kv_set "$STATE_FILE" "$1" "$2"; }

load_env() {
  [[ -f "$ENV_FILE" ]] || die "$ENV_FILE does not exist; run: aiwos init"
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
}

# ---- status file (public: SHAs, times and results only) ----------------------------------------

write_status() {
  mkdir -p "$STATUS_DIR"
  chmod 755 "$STATUS_DIR"
  jq -n \
    --arg host "$(env_value AIWOS_PUBLIC_HOST)" \
    --arg branch "$(env_value AIWOS_GIT_BRANCH)" \
    --arg current "$(state CURRENT_SHA)" \
    --arg previous "$(state PREVIOUS_SHA)" \
    --arg held "$(state SKIP_SHA)" \
    --arg phase "$(state PHASE)" \
    --arg sha "$(state ATTEMPT_SHA)" \
    --arg started "$(state ATTEMPT_STARTED)" \
    --arg finished "$(state ATTEMPT_FINISHED)" \
    --arg result "$(state ATTEMPT_RESULT)" \
    --arg message "$(state ATTEMPT_MESSAGE)" \
    --arg success "$(state LAST_SUCCESS_AT)" \
    --arg checked "$(state LAST_CHECK_AT)" \
    --arg tls "$(state TLS_VALID)" \
    --arg updated "$(now)" \
    'def n: if . == "" then null else . end;
     {host: ($host|n), branch: ($branch|n),
      current_sha: ($current|n), previous_sha: ($previous|n),
      skipped_sha: ($held|n),
      phase: (if $phase == "" then "idle" else $phase end),
      last_attempt: {sha: ($sha|n), result: ($result|n), message: ($message|n),
                     started_at: ($started|n), finished_at: ($finished|n)},
      last_success_at: ($success|n), last_check_at: ($checked|n),
      tls_certificate_valid: (if $tls == "" then null else ($tls == "true") end),
      updated_at: $updated}' >"$STATUS_FILE.tmp"
  chmod 644 "$STATUS_FILE.tmp"
  mv -f "$STATUS_FILE.tmp" "$STATUS_FILE"
}

# Records the end of an attempt. Results: deployed, build_failed, rolled_back, rollback_failed, failed.
finish_attempt() {
  set_state ATTEMPT_RESULT "$1"
  set_state ATTEMPT_MESSAGE "$2"
  set_state ATTEMPT_FINISHED "$(now)"
  set_state PHASE idle
  write_status
  log "Result: $1 - $2"
}

set_phase() {
  set_state PHASE "$1"
  write_status
}

# ---- init and install ---------------------------------------------------------------------------

write_secrets() {
  (
    umask 077
    cat >"$ENV_FILE.tmp" <<EOF
# AI Workforce OS server settings, written on $(date -u '+%Y-%m-%d') by aiwos.sh init.
#
# Private to this server. Do not delete it: keys stored in the app are encrypted with
# AIWOS_ENCRYPTION_MASTER_KEY and cannot be read without it, and PostgreSQL's password was set
# from AIWOS_DB_PASSWORD when its volume was created.
AIWOS_ENCRYPTION_KEY_ID=aws-1
AIWOS_ENCRYPTION_MASTER_KEY=$(openssl rand -base64 32)
AIWOS_SECURITY_DEVELOPMENT_SECRET=$(openssl rand -hex 32)
AIWOS_SECURITY_INTERNAL_SERVICE_SECRET=$(openssl rand -hex 32)
AIWOS_DB_PASSWORD=$(openssl rand -hex 24)
EOF
  )
  mv -f "$ENV_FILE.tmp" "$ENV_FILE"
  log "Generated new secrets in $ENV_FILE"
}

# Caddy reads its file from a mounted directory; copied on every deploy so a changed Caddyfile in the
# repository takes effect (reloaded without a restart when Caddy is running).
sync_caddy_config() {
  mkdir -p "$CADDY_DIR"
  if ! cmp -s "$REPO/infra/aws/Caddyfile" "$CADDY_DIR/Caddyfile"; then
    install -m 0644 "$REPO/infra/aws/Caddyfile" "$CADDY_DIR/Caddyfile.tmp"
    mv -f "$CADDY_DIR/Caddyfile.tmp" "$CADDY_DIR/Caddyfile"
    if [[ -n "$(compose ps -q caddy 2>/dev/null || true)" ]]; then
      compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 \
        || compose restart caddy >/dev/null 2>&1 || true
      log "Caddy configuration updated"
    fi
  fi
}

cmd_init() {
  require_root init
  [[ -f "$BOOTSTRAP_ENV" ]] || die "$BOOTSTRAP_ENV is missing (written by the CloudFormation user data)"
  # shellcheck disable=SC1090
  . "$BOOTSTRAP_ENV"
  : "${PUBLIC_IP:?PUBLIC_IP missing from bootstrap.env}"
  : "${AWS_REGION:?AWS_REGION missing from bootstrap.env}"

  mkdir -p "$AIWOS_HOME"
  chmod 700 "$AIWOS_HOME"
  [[ -f "$ENV_FILE" ]] || write_secrets

  local host demo local_model environment profiles
  host="$(printf '%s' "$PUBLIC_IP" | tr '.' '-').sslip.io"
  demo=false
  is_true "${DEMO_DATA:-false}" && demo=true
  local_model=true
  is_true "${LOCAL_MODEL:-true}" || local_model=false
  # Demo sign-ins share a published password, and the services refuse to create them in
  # production mode, so a demo server runs in "local" mode (still HTTPS, still private secrets).
  environment=production
  $demo && environment=local
  profiles=""
  $local_model && profiles="ollama"

  # Settings, rewritten on every init so a changed bootstrap.env takes effect; secrets above stay.
  set_env_value COMPOSE_PROFILES "$profiles"
  set_env_value AIWOS_IMAGE_PREFIX aiwos
  set_env_value AIWOS_GIT_BRANCH "${GIT_BRANCH:-main}"
  set_env_value AIWOS_ECR_REGISTRY "${ECR_REGISTRY:-}"
  set_env_value AIWOS_CONFIG_DIR "$AIWOS_HOME/config"
  set_env_value AIWOS_STATUS_DIR "$STATUS_DIR"
  set_env_value AIWOS_PUBLIC_HOST "$host"
  set_env_value AIWOS_PUBLIC_BASE_URL "https://$host"
  set_env_value AIWOS_ENVIRONMENT "$environment"
  set_env_value AIWOS_DEMO_ENABLED "$demo"
  set_env_value AWS_REGION "$AWS_REGION"
  set_env_value AIWOS_BEDROCK_REGION "$AWS_REGION"
  set_env_value AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS true
  set_env_value AIWOS_DEFAULT_ROUTING "bedrock/${BEDROCK_MODEL:-apac.amazon.nova-lite-v1:0}"
  set_env_value AIWOS_LOCAL_FALLBACK "$local_model"
  set_env_value AIWOS_OLLAMA_BASE_URL "http://ollama:11434/v1"
  set_env_value AIWOS_OLLAMA_MODEL "${OLLAMA_MODEL:-qwen2.5:1.5b-instruct}"

  # nginx accepts the public name (and only it, besides loopback and IPs); Caddy terminates TLS and
  # says so in X-Forwarded-Proto.
  mkdir -p "$AIWOS_HOME/config/nginx"
  printf '"%s" 1;\n' "$host" >"$AIWOS_HOME/config/nginx/hosts.map"
  printf 'https https;\n' >"$AIWOS_HOME/config/nginx/proto.map"
  sync_caddy_config
  [[ -f "$STATE_FILE" ]] || set_state PHASE waiting_for_first_deploy
  write_status

  log "Public address: https://$host (demo data: $demo, local model: $local_model)"
  # A running stack picks up changed settings now; on first boot the timer's first run starts it.
  if [[ -n "$(state CURRENT_SHA)" ]]; then
    load_env
    with_lock wait compose up -d --remove-orphans
  fi
}

install_self() {
  # In /usr/bin, so "sudo aiwos" works from a Session Manager shell (sudo's path leaves out
  # /usr/local/bin). Replaced by rename: a running copy keeps reading its old file.
  install -m 0755 "$REPO/infra/aws/aiwos.sh" /usr/bin/aiwos.tmp
  mv -f /usr/bin/aiwos.tmp /usr/bin/aiwos
}

install_units() {
  cat >/etc/systemd/system/aiwos-update.service <<'EOF'
[Unit]
Description=AI Workforce OS: deploy new commits (fetch, build, start, health check, rollback)
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/aiwos update
# A first build takes about 20 minutes; a failed deploy adds a 10-minute wait and a rollback.
TimeoutStartSec=100min
# Builds yield the CPU to the running app.
Nice=10
IOSchedulingClass=best-effort
IOSchedulingPriority=7
SyslogIdentifier=aiwos-deploy
EOF
  cat >/etc/systemd/system/aiwos-update.timer <<'EOF'
[Unit]
Description=AI Workforce OS: check for new commits every 2 minutes

[Timer]
OnBootSec=2min
OnUnitInactiveSec=2min
AccuracySec=15s

[Install]
WantedBy=timers.target
EOF
  cat >/etc/logrotate.d/aiwos <<EOF
$LOG_FILE /var/log/aiwos-bootstrap.log {
    weekly
    maxsize 20M
    rotate 4
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
EOF
  systemctl daemon-reload
  systemctl enable --now aiwos-update.timer >/dev/null 2>&1
}

cmd_install() {
  require_root install
  command -v jq >/dev/null || dnf install -y jq
  command -v logrotate >/dev/null || dnf install -y logrotate
  touch "$LOG_FILE"
  chmod 640 "$LOG_FILE"
  install_self
  install_units
  log "Installed /usr/bin/aiwos and aiwos-update.timer (every 2 minutes)"
}

# ---- locking ------------------------------------------------------------------------------------

# with_lock nowait|wait COMMAND...: one deploy at a time. The timer's run gives up quietly when a
# manual command holds the lock; a manual command waits for the timer's run to finish.
with_lock() {
  local mode="$1"
  shift
  exec 9>"$LOCK_FILE"
  if [[ "$mode" == nowait ]]; then
    flock -n 9 || { log "Another deploy is running; skipping this check"; return 0; }
  else
    flock -n 9 || { log "Waiting for the running deploy to finish"; flock 9; }
  fi
  "$@"
}

# ---- images -------------------------------------------------------------------------------------

image_of() {
  case "$1" in
    organisation) echo org-service ;;
    gateway | web) echo "$1" ;;
    *) echo "$1-service" ;;
  esac
}

build_services() {
  local services="$APP_SERVICES"
  [[ ",$(env_value COMPOSE_PROFILES)," == *",gateway,"* ]] && services="$services gateway"
  echo "$services"
}

# One service at a time, so only one build runs beside the live stack. The Maven stage of
# services.Dockerfile does not depend on the service, so BuildKit compiles the backend once and the
# other six backend images reuse it from the cache.
build_images() {
  local sha="$1" service image
  export DOCKER_BUILDKIT=1 BUILDKIT_PROGRESS=plain AIWOS_IMAGE_TAG="$sha"
  for service in $(build_services); do
    image="aiwos/$(image_of "$service"):$sha"
    if docker image inspect "$image" >/dev/null 2>&1; then
      log "Image $image already built"
      continue
    fi
    log "Building $service ($image); output in $LOG_FILE"
    if ! compose build "$service" >>"$LOG_FILE" 2>&1; then
      log "Build of $service failed; last lines:"
      tail -n 40 "$LOG_FILE" | sed 's/^/  | /'
      return 1
    fi
  done
}

images_present() {
  local sha="$1" service
  for service in $(build_services); do
    docker image inspect "aiwos/$(image_of "$service"):$sha" >/dev/null 2>&1 || return 1
  done
}

# Keeps the images of the current and previous commit; build cache is capped, not emptied, so the
# next build stays incremental.
prune_images() {
  local keep1="$1" keep2="$2" ref tag
  docker image ls --format '{{.Repository}}:{{.Tag}}' | grep -E '^aiwos/' | while read -r ref; do
    tag="${ref##*:}"
    if [[ "$tag" != "$keep1" && "$tag" != "$keep2" ]]; then
      docker rmi "$ref" >/dev/null 2>&1 && log "Removed old image $ref"
    fi
  done || true
  docker image prune -f >/dev/null 2>&1 || true
  docker builder prune -f --keep-storage 6gb >/dev/null 2>&1 || true
}

# Optional (CreateEcrRepositories=true): an off-instance copy of each deployed image.
ecr_copy() {
  local sha="$1" registry service image
  registry="$(env_value AIWOS_ECR_REGISTRY)"
  [[ -n "$registry" ]] || return 0
  if ! aws ecr get-login-password --region "$(env_value AWS_REGION)" \
    | docker login --username AWS --password-stdin "$registry" >/dev/null 2>&1; then
    log "ECR sign-in failed; images not copied (the deploy itself is fine)"
    return 0
  fi
  for service in $(build_services); do
    image="$(image_of "$service")"
    docker tag "aiwos/$image:$sha" "$registry/aiwos/$image:$sha"
    docker push --quiet "$registry/aiwos/$image:$sha" >/dev/null 2>&1 \
      || log "Could not push $image to ECR"
    docker rmi "$registry/aiwos/$image:$sha" >/dev/null 2>&1 || true
  done
  log "Copied images for ${sha:0:12} to ECR"
}

# ---- health -------------------------------------------------------------------------------------

edge_ok() {
  local host jwks
  host="$(env_value AIWOS_PUBLIC_HOST)"
  # Through Caddy on this machine: the web client, and identity behind nginx. -k: a certificate
  # still being issued must not cause a rollback; its validity is reported separately.
  curl -ksf -o /dev/null --max-time 10 --resolve "$host:443:127.0.0.1" "https://$host/" || return 1
  jwks="$(curl -ksf --max-time 10 --resolve "$host:443:127.0.0.1" "https://$host/.well-known/jwks.json")" || return 1
  [[ "$jwks" == *'"keys"'* ]]
}

tls_valid() {
  local host
  host="$(env_value AIWOS_PUBLIC_HOST)"
  if curl -sf -o /dev/null --max-time 10 --resolve "$host:443:127.0.0.1" "https://$host/"; then
    echo true
  else
    echo false
  fi
}

wait_healthy() {
  local deadline="$1" pending service id state last_report=0
  while ((SECONDS < deadline)); do
    pending=""
    for service in $CORE_SERVICES; do
      id="$(compose ps -q "$service" 2>/dev/null || true)"
      if [[ -z "$id" ]]; then
        pending="$pending $service(missing)"
        continue
      fi
      state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo gone)"
      case "$state" in
        healthy | running) ;;
        *) pending="$pending $service($state)" ;;
      esac
    done
    if [[ -z "$pending" ]]; then
      if edge_ok; then
        log "All services are ready and the site answers through Caddy"
        return 0
      fi
      pending=" https-edge"
    fi
    if ((SECONDS - last_report >= 60)); then
      log "Waiting for:$pending"
      last_report=$SECONDS
    fi
    sleep 15
  done
  log "Not healthy in time:$pending"
  return 1
}

# Starts SHA's images with SHA's compose file and waits until DEADLINE (in $SECONDS).
switch_to() {
  local sha="$1" deadline="$2"
  git -C "$REPO" checkout --quiet --force "$sha" || { log "Could not check out ${sha:0:12}"; return 1; }
  set_env_value AIWOS_IMAGE_TAG "$sha"
  load_env
  export AIWOS_IMAGE_TAG="$sha"
  sync_caddy_config
  log "Starting ${sha:0:12}"
  # "up" itself waits for the health-gated dependencies; the overall deadline still decides.
  timeout "$((deadline > SECONDS ? deadline - SECONDS : 1))" \
    docker compose --project-name aiwos --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --remove-orphans >>"$LOG_FILE" 2>&1 \
    || log "compose up returned an error; checking health until the deadline"
  wait_healthy "$deadline"
}

record_unhealthy() {
  {
    echo "---- compose ps at $(now)"
    compose ps 2>&1 || true
    for service in $CORE_SERVICES; do
      echo "---- last log lines of $service"
      compose logs --no-color --tail 30 "$service" 2>&1 || true
    done
  } >>"$LOG_FILE"
  log "Container state and recent logs saved to $LOG_FILE"
}

# ---- deploy -------------------------------------------------------------------------------------

resolve_sha() {
  local ref="$1"
  if ! git -C "$REPO" cat-file -e "$ref^{commit}" 2>/dev/null; then
    git -C "$REPO" fetch --quiet origin || true
  fi
  git -C "$REPO" rev-parse --verify --quiet "$ref^{commit}" || die "Unknown commit: $ref"
}

# deploy_sha SHA REASON: build, start, health check, and roll back to the current commit on failure.
deploy_sha() {
  local target="$1" reason="$2" current previous deadline timeout first=false
  load_env
  current="$(state CURRENT_SHA)"
  previous="$(state PREVIOUS_SHA)"
  [[ -n "$current" ]] || first=true

  set_state ATTEMPT_SHA "$target"
  set_state ATTEMPT_STARTED "$(now)"
  set_state ATTEMPT_FINISHED ""
  set_state ATTEMPT_RESULT in_progress
  set_state ATTEMPT_MESSAGE "$reason"
  set_phase building
  log "Deploying ${target:0:12} ($reason); live now: ${current:0:12}"

  # Called under "|| true", where set -e is off: every step that matters is checked.
  if ! git -C "$REPO" checkout --quiet --force "$target"; then
    finish_attempt failed "Could not check out ${target:0:12}; ${current:0:12} keeps running"
    return 1
  fi
  # First deploy: start Caddy alone now, so /deploy-status.json is served (and the certificate is
  # requested) during the 15-20 minute first build.
  if $first && [[ -z "$(compose ps -q --status running caddy 2>/dev/null || true)" ]]; then
    compose up -d --no-deps caddy >>"$LOG_FILE" 2>&1 || log "Could not start Caddy early; status is served once the stack starts"
  fi
  # The local model is only a fallback; pausing it frees about 1.5 GB for the build.
  local paused_ollama=false
  if [[ -n "$(compose ps -q --status running ollama 2>/dev/null || true)" ]]; then
    compose stop ollama >/dev/null 2>&1 && paused_ollama=true && log "Paused ollama during the build"
  fi

  if ! build_images "$target"; then
    if [[ -n "$current" ]]; then
      git -C "$REPO" checkout --quiet --force "$current"
      export AIWOS_IMAGE_TAG="$current"
    fi
    $paused_ollama && { compose start ollama >/dev/null 2>&1 || true; }
    set_state SKIP_SHA "$target"
    finish_attempt build_failed "The images for ${target:0:12} did not build; ${current:0:12} keeps running"
    return 1
  fi

  set_phase starting
  timeout="$HEALTH_TIMEOUT"
  $first && timeout="$FIRST_HEALTH_TIMEOUT"
  deadline=$((SECONDS + timeout))
  if switch_to "$target" "$deadline"; then
    [[ -n "$current" && "$current" != "$target" ]] && previous="$current"
    set_state CURRENT_SHA "$target"
    set_state PREVIOUS_SHA "$previous"
    set_state SKIP_SHA ""
    set_state LAST_SUCCESS_AT "$(now)"
    set_state TLS_VALID "$(tls_valid)"
    finish_attempt deployed "${target:0:12} is live"
    prune_images "$target" "$previous"
    ecr_copy "$target"
    install_self
    install_units
    return 0
  fi

  record_unhealthy
  set_state SKIP_SHA "$target"
  if $first || ! images_present "$current"; then
    finish_attempt failed "${target:0:12} did not become healthy in ${timeout}s and there is no earlier build to go back to"
    return 1
  fi
  set_phase rolling_back
  log "Rolling back to ${current:0:12}"
  if switch_to "$current" "$((SECONDS + FIRST_HEALTH_TIMEOUT))"; then
    set_state TLS_VALID "$(tls_valid)"
    finish_attempt rolled_back "${target:0:12} did not become healthy in ${timeout}s; rolled back to ${current:0:12}"
  else
    record_unhealthy
    finish_attempt rollback_failed "${target:0:12} failed and ${current:0:12} did not come back healthy either; see $LOG_FILE"
  fi
  install_self
  return 1
}

do_update() {
  local force="$1" branch target current skip
  load_env
  branch="$(env_value AIWOS_GIT_BRANCH)"
  branch="${branch:-main}"
  set_state LAST_CHECK_AT "$(now)"
  if ! git -C "$REPO" fetch --quiet --prune origin "+refs/heads/$branch:refs/remotes/origin/$branch"; then
    write_status
    die "git fetch from origin failed; will retry on the next run"
  fi
  target="$(git -C "$REPO" rev-parse "refs/remotes/origin/$branch")"
  current="$(state CURRENT_SHA)"
  skip="$(state SKIP_SHA)"

  if [[ "$force" != true ]]; then
    if [[ "$target" == "$current" ]]; then
      write_status
      return 0
    fi
    # A commit that already failed (or was rolled back by hand) is not retried until a new commit
    # arrives or "aiwos update --force". With nothing live yet, the first deploy keeps retrying.
    if [[ -n "$current" && "$target" == "$skip" ]]; then
      write_status
      return 0
    fi
  fi
  deploy_sha "$target" "new commit on $branch" || true
}

do_deploy() {
  local sha
  sha="$(resolve_sha "$1")"
  deploy_sha "$sha" "manual deploy"
}

do_rollback() {
  local target="${1:-}" current
  load_env
  current="$(state CURRENT_SHA)"
  [[ -n "$target" ]] || target="$(state PREVIOUS_SHA)"
  [[ -n "$target" ]] || die "No previous deployment to roll back to"
  target="$(resolve_sha "$target")"
  [[ "$target" != "$current" ]] || die "${target:0:12} is already live"
  if deploy_sha "$target" "manual rollback from ${current:0:12}"; then
    # Hold here: the timer would otherwise redeploy the commit just rolled away from.
    set_state SKIP_SHA "$current"
    write_status
    log "Holding at ${target:0:12}; ${current:0:12} will not be redeployed. A new push deploys again."
  fi
}

# ---- commands -----------------------------------------------------------------------------------

cmd_status() {
  if [[ -f "$STATUS_FILE" ]]; then
    jq . "$STATUS_FILE"
  else
    echo "No status yet ($STATUS_FILE)"
  fi
  load_env
  compose ps
  systemctl list-timers aiwos-update.timer --no-pager 2>/dev/null | head -n 2 || true
  free -m
  df -h / | tail -n 1
}

cmd_logs() {
  load_env
  compose logs --tail 200 -f "$@"
}

cmd_url() {
  printf 'AIWOS_URL=https://%s\n' "$(env_value AIWOS_PUBLIC_HOST)"
}

case "${1:-}" in
  init) cmd_init ;;
  install) cmd_install ;;
  update)
    require_root update
    force=false
    [[ "${2:-}" == "--force" ]] && force=true
    with_lock nowait do_update "$force"
    ;;
  deploy)
    require_root deploy
    [[ -n "${2:-}" ]] || die "Usage: aiwos deploy SHA"
    with_lock wait do_deploy "$2"
    ;;
  rollback)
    require_root rollback
    with_lock wait do_rollback "${2:-}"
    ;;
  status) cmd_status ;;
  logs) shift; cmd_logs "$@" ;;
  url) cmd_url ;;
  *)
    sed -n '6,16p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
