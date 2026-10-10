#!/usr/bin/env bash
# @find: aws server script, deploy, rollback, init, generate secrets, /opt/aiwos/.env, ecr login, docker compose up, wait healthy, status, logs, aiwos.sh
# @what: Operates the AI Workforce OS stack on the EC2 server: first-boot setup with generated secrets, deploy of an image tag, rollback, status and logs.
# @flow: Called by the CloudFormation user data (init) and by .github/workflows/deploy.yml over SSM (deploy); run by hand through SSM Session Manager
#
#   aiwos.sh init                 first boot: write /opt/aiwos/.env (secrets made here with openssl,
#                                 once), the nginx host files, then deploy "latest" (or build it)
#   aiwos.sh deploy [TAG]         pull TAG from ECR (default: the current one), start it, wait
#   aiwos.sh rollback [TAG]       deploy the previous tag (or TAG) again
#   aiwos.sh status               containers, health and the deployed tag
#   aiwos.sh logs [SERVICE...]    follow logs (all services when none is named)
#   aiwos.sh url                  print the public address
#
# Runs as root (SSM runs commands as root). Settings for init come from /opt/aiwos/bootstrap.env,
# which the user data writes; secrets are generated only when .env does not exist yet and are
# never printed.

set -euo pipefail

AIWOS_HOME="${AIWOS_HOME:-/opt/aiwos}"
REPO="$AIWOS_HOME/repo"
ENV_FILE="$AIWOS_HOME/.env"
BOOTSTRAP_ENV="$AIWOS_HOME/bootstrap.env"
HISTORY="$AIWOS_HOME/deploy-history"
COMPOSE_FILE="$REPO/infra/aws/docker-compose.prod.yml"
CORE_SERVICES="identity organisation orchestrator memory knowledge integrations analytics web caddy"

log() { printf '[aiwos %s] %s\n' "$(date -u '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

compose() {
  docker compose --project-name aiwos --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

load_env() {
  [[ -f "$ENV_FILE" ]] || die "$ENV_FILE does not exist; run: aiwos.sh init"
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
}

env_value() {
  grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n 1 | cut -d= -f2- || true
}

# Replaces KEY's line, or appends it, through a temporary file so a crash never leaves half a file.
set_env_value() {
  local key="$1" value="$2" tmp="$ENV_FILE.tmp"
  awk -v key="$key" -v value="$value" '
    BEGIN { done = 0 }
    index($0, key "=") == 1 { if (!done) { print key "=" value; done = 1 }; next }
    { print }
    END { if (!done) print key "=" value }
  ' "$ENV_FILE" >"$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$ENV_FILE"
}

is_true() {
  case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
    1 | true | yes | on) return 0 ;;
    *) return 1 ;;
  esac
}

# ---- init -------------------------------------------------------------------------------------

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

cmd_init() {
  [[ -f "$BOOTSTRAP_ENV" ]] || die "$BOOTSTRAP_ENV is missing (written by the CloudFormation user data)"
  # shellcheck disable=SC1090
  . "$BOOTSTRAP_ENV"
  : "${PUBLIC_IP:?PUBLIC_IP missing from bootstrap.env}"
  : "${AWS_REGION:?AWS_REGION missing from bootstrap.env}"
  : "${ECR_REGISTRY:?ECR_REGISTRY missing from bootstrap.env}"

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
  set_env_value AIWOS_IMAGE_PREFIX "$ECR_REGISTRY/aiwos"
  [[ -n "$(env_value AIWOS_IMAGE_TAG)" ]] || set_env_value AIWOS_IMAGE_TAG latest
  set_env_value AIWOS_CONFIG_DIR "$AIWOS_HOME/config"
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

  log "Public address: https://$host (demo data: $demo, local model: $local_model)"
  cmd_deploy "$(env_value AIWOS_IMAGE_TAG)"
}

# ---- deploy -----------------------------------------------------------------------------------

ecr_login() {
  local registry
  registry="$(env_value AIWOS_IMAGE_PREFIX)"
  registry="${registry%%/*}"
  aws ecr get-login-password --region "$(env_value AWS_REGION)" \
    | docker login --username AWS --password-stdin "$registry" >/dev/null
}

wait_healthy() {
  local deadline=$((SECONDS + ${1:-1200})) pending service id state
  while ((SECONDS < deadline)); do
    pending=""
    for service in $CORE_SERVICES; do
      id="$(compose ps -q "$service" 2>/dev/null || true)"
      if [[ -z "$id" ]]; then
        pending="$pending $service(missing)"
        continue
      fi
      state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")"
      case "$state" in
        healthy | running) ;;
        *) pending="$pending $service($state)" ;;
      esac
    done
    if [[ -z "$pending" ]]; then
      log "All services are healthy"
      return 0
    fi
    # Once a minute, so the SSM output GitHub reads (first 24,000 characters) stays short.
    (((SECONDS / 15) % 4 == 0)) && log "Waiting for:$pending"
    sleep 15
  done
  log "Still not healthy:$pending"
  return 1
}

cmd_deploy() {
  local tag="${1:-}"
  load_env
  # Keep the "aiwos" command in step with the checked-out source. In /usr/bin, so "sudo aiwos"
  # works from a Session Manager shell (sudo's path leaves out /usr/local/bin).
  install -m 0755 "$REPO/infra/aws/aiwos.sh" /usr/bin/aiwos 2>/dev/null || true
  [[ -n "$tag" ]] || tag="${AIWOS_IMAGE_TAG:-latest}"
  set_env_value AIWOS_IMAGE_TAG "$tag"
  load_env
  log "Deploying image tag $tag"
  cmd_url

  if ecr_login && compose pull --quiet; then
    log "Pulled images for $tag from ECR"
  elif [[ "$tag" == "latest" ]]; then
    # First boot before any push from GitHub: build the images here from the checked-out source.
    log "No images in ECR yet; building them on this server (about 15 minutes)"
    DOCKER_BUILDKIT=1 compose build
  else
    die "Could not pull image tag $tag from ECR"
  fi

  compose up -d --remove-orphans
  if ! wait_healthy 1500; then
    compose ps
    die "The stack did not become healthy; see: aiwos logs"
  fi
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$tag" >>"$HISTORY"
  # Keep recent images for a quick rollback; drop what is more than a week old and unused.
  docker image prune -af --filter "until=168h" >/dev/null 2>&1 || true
  log "Deployed $tag"
  cmd_url
}

cmd_rollback() {
  local tag="${1:-}"
  if [[ -z "$tag" ]]; then
    [[ -f "$HISTORY" ]] || die "No deployment history in $HISTORY"
    local current
    current="$(tail -n 1 "$HISTORY" | awk '{print $2}')"
    tag="$(awk '{print $2}' "$HISTORY" | grep -vx "$current" | tail -n 1 || true)"
    [[ -n "$tag" ]] || die "No earlier tag to roll back to"
  fi
  # The compose file that matches those images, when the tag is a commit of this repository.
  if git -C "$REPO" cat-file -e "$tag^{commit}" 2>/dev/null; then
    git -C "$REPO" checkout --quiet --force "$tag"
  fi
  log "Rolling back to $tag"
  cmd_deploy "$tag"
}

cmd_status() {
  load_env
  log "Deployed tag: ${AIWOS_IMAGE_TAG:-unknown}; source: $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  compose ps
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
  deploy) shift; cmd_deploy "${1:-}" ;;
  rollback) shift; cmd_rollback "${1:-}" ;;
  status) cmd_status ;;
  logs) shift; cmd_logs "$@" ;;
  url) cmd_url ;;
  *)
    sed -n '6,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
