#!/usr/bin/env bash
#
# Writes infra/launcher/.env, the launcher's private settings, for "Start AI Workforce OS.command".
# prepare-env.ps1 does the same for the Windows .bat; keep the two in step.
#
# The secrets are generated once, on the first start, and never regenerated: every credential an
# administrator stores in the app is encrypted under AIWOS_ENCRYPTION_MASTER_KEY, and decryption
# accepts only the key id it was written with, so a new key would make every stored key
# unreadable. Only the demo switch is ever rewritten, and only when it is asked to change.
#
#   prepare-env.sh [--demo | --no-demo] [--new-keys]
#
#   --demo / --no-demo  load the sample workspace and demo sign-ins, or not (default: on)
#   --new-keys          replace a key anyone can derive from the source with a private one; every
#                       stored AI provider and connector key must then be entered again
#
# Reads AIWOS_DEMO_ENABLED (true/false) and AIWOS_EXPOSE_LAN (1 to share on the network) from the
# environment. Run from the repository root. Exit status: 0 ready, 1 failed, 3 refused to share
# the app on the network.
#
# Written for the bash 3.2 that macOS ships.

set -euo pipefail

ENV_FILE="infra/launcher/.env"
VOLUME="aiwos-app_postgres-data"

# The values every launcher install used before this file existed. An install created then
# encrypted its stored credentials under a key derived from this secret, so it must keep it.
LEGACY_KEY_ID="local-dev"
LEGACY_DEVELOPMENT_SECRET="insecure-local-development-secret-change-me"
LEGACY_DB_PASSWORD="aiwos"

demo_choice=""
new_keys=false
for arg in "$@"; do
  case "$arg" in
    --demo) demo_choice=true ;;
    --no-demo) demo_choice=false ;;
    --new-keys) new_keys=true ;;
    *)
      echo "  Unknown option: $arg (expected --demo, --no-demo or --new-keys)" >&2
      exit 1
      ;;
  esac
done

normalise_bool() {
  case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
    1 | true | yes | y | on) echo true ;;
    0 | false | no | n | off) echo false ;;
    *) echo "" ;;
  esac
}

if [[ -z "$demo_choice" && -n "${AIWOS_DEMO_ENABLED:-}" ]]; then
  demo_choice="$(normalise_bool "$AIWOS_DEMO_ENABLED")"
fi

random_base64_32() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 32
  else
    head -c 32 /dev/urandom | base64 | tr -d '\n'
  fi
}

random_hex_32() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    od -An -tx1 -N32 /dev/urandom | tr -d ' \n'
  fi
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

# Prints its answer, true or false, on stdout; the question goes to stderr so it is still shown
# when the answer is captured.
ask_demo() {
  # Only a person at a terminal can answer; anything else takes the default.
  if [[ -t 0 ]]; then
    local answer=""
    echo "" >&2
    echo "  Load the demo workspace? It adds sample data and five demo sign-ins that share a" >&2
    echo "  published password, which is what a sales demo needs. Answer n for a private install." >&2
    read -r -t 60 -p "  Load demo data? [Y/n] " answer || true
    case "$(printf '%s' "$answer" | tr '[:upper:]' '[:lower:]')" in
      n | no) echo false; return ;;
    esac
  fi
  echo true
}

write_new_env() {
  local key_id="$1" master_key="$2" development_secret="$3" db_password="$4" demo="$5" loaded="$6"
  local tmp="$ENV_FILE.tmp"
  (
    umask 077
    cat >"$tmp" <<EOF
# AI Workforce OS launcher settings, written on $(date '+%Y-%m-%d') by prepare-env.sh.
#
# Private to this computer and never committed. Do not delete it: the keys you store in the app
# are encrypted with AIWOS_ENCRYPTION_MASTER_KEY and cannot be read without it. Keep a copy with
# your backups if the data matters.
AIWOS_ENCRYPTION_KEY_ID=$key_id
AIWOS_ENCRYPTION_MASTER_KEY=$master_key
AIWOS_SECURITY_DEVELOPMENT_SECRET=$development_secret
AIWOS_SECURITY_INTERNAL_SERVICE_SECRET=$(random_hex_32)
# Read by PostgreSQL only when its data volume is first created.
AIWOS_DB_PASSWORD=$db_password
# The sample workspace and demo sign-ins. Change it with --demo or --no-demo.
AIWOS_DEMO_ENABLED=$demo
# Set once demo data has been loaded: the demo sign-ins then exist in this database for good.
AIWOS_DEMO_DATA_LOADED=$loaded
EOF
  )
  mv -f "$tmp" "$ENV_FILE"
}

if [[ ! -f "$ENV_FILE" ]]; then
  if docker volume inspect "$VOLUME" >/dev/null 2>&1; then
    # Created by a launcher from before this file existed. Its stored credentials were encrypted
    # under the published development secret, so that secret and its key id are kept, as is the
    # database password the volume was initialised with. Demo data was always on then.
    write_new_env "$LEGACY_KEY_ID" "" "$LEGACY_DEVELOPMENT_SECRET" "$LEGACY_DB_PASSWORD" \
      "${demo_choice:-true}" true
    echo ""
    echo "  WARNING: this installation was created by an earlier version of the launcher."
    echo "  The AI provider and connector keys stored in it are encrypted with a key that anyone"
    echo "  can work out from the published source code. It is kept so those keys still work."
    echo "  To switch to a private key, start once with --new-keys, then enter your provider and"
    echo "  connector keys again on the Model routing and Connectors pages."
    echo "  If you deleted infra/launcher/.env from a newer install, restore it from a backup"
    echo "  instead: the database password and the encryption key in it cannot be recreated."
  else
    if [[ -z "$demo_choice" ]]; then
      demo_choice="$(ask_demo)"
    fi
    write_new_env "launcher-1" "$(random_base64_32)" "$(random_hex_32)" "$(random_hex_32)" \
      "$demo_choice" "$demo_choice"
    echo "  Created private settings in $ENV_FILE. Keep this file: it holds the encryption key."
  fi
elif [[ -n "$demo_choice" && "$(env_value AIWOS_DEMO_ENABLED)" != "$demo_choice" ]]; then
  set_env_value AIWOS_DEMO_ENABLED "$demo_choice"
fi

if [[ "$(env_value AIWOS_DEMO_ENABLED)" == "true" && "$(env_value AIWOS_DEMO_DATA_LOADED)" != "true" ]]; then
  set_env_value AIWOS_DEMO_DATA_LOADED true
fi

if $new_keys; then
  if [[ "$(env_value AIWOS_ENCRYPTION_KEY_ID)" == "$LEGACY_KEY_ID" || -z "$(env_value AIWOS_ENCRYPTION_MASTER_KEY)" ]]; then
    set_env_value AIWOS_ENCRYPTION_KEY_ID "launcher-1"
    set_env_value AIWOS_ENCRYPTION_MASTER_KEY "$(random_base64_32)"
    set_env_value AIWOS_SECURITY_DEVELOPMENT_SECRET "$(random_hex_32)"
    echo ""
    echo "  New private encryption key written. Keys stored before now can no longer be read:"
    echo "  enter your AI provider and connector keys again on the Model routing and Connectors pages."
  else
    echo "  This installation already has a private encryption key; nothing was changed."
  fi
fi

# Sharing the app on the network is opt-in, and never with demo sign-ins in the database: they
# share a password that is printed on the screen and published in the source.
if [[ "$(normalise_bool "${AIWOS_EXPOSE_LAN:-0}")" == "true" ]]; then
  if [[ "$(env_value AIWOS_DEMO_ENABLED)" == "true" ]]; then
    echo ""
    echo "  Not sharing on the network: demo data is on, and its sign-ins use a published password."
    echo "  Start with --no-demo to share it, or without AIWOS_EXPOSE_LAN to keep it on this computer."
    exit 3
  fi
  if [[ "$(env_value AIWOS_DEMO_DATA_LOADED)" == "true" ]]; then
    echo ""
    echo "  Not sharing on the network: demo data was loaded into this installation earlier, so its"
    echo "  demo sign-ins and their published password still exist. To share the app, start again"
    echo "  from empty data: run 'docker compose -f infra/launcher/docker-compose.yml down -v'"
    echo "  (this deletes all of the app's data), delete infra/launcher/.env, then start with --no-demo."
    exit 3
  fi
fi

exit 0
