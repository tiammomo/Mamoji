#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.production}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.prod.yml}"
errors=()

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

value_of() {
  local name="$1"
  printf '%s' "${!name:-}"
}

fail() {
  errors+=("$1")
}

contains_placeholder() {
  local value
  value="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  [[ -z "$value" || "$value" == *"replace-with"* || "$value" == *"example.com"* || "$value" == "123456" || "$value" == "mamoji" || "$value" == "minioadmin" ]]
}

require_real_value() {
  local name="$1"
  local min_length="${2:-1}"
  local value
  value="$(value_of "$name")"
  if contains_placeholder "$value" || (( ${#value} < min_length )); then
    fail "$name must be replaced with a production value of at least $min_length characters"
  fi
}

require_equals() {
  local name="$1"
  local expected="$2"
  local value
  value="$(value_of "$name")"
  if [[ "$value" != "$expected" ]]; then
    fail "$name must be $expected"
  fi
}

require_https_url() {
  local name="$1"
  local value
  value="$(value_of "$name")"
  if contains_placeholder "$value" || [[ "$value" != https://* ]]; then
    fail "$name must be a production https:// URL"
  fi
}

require_no_latest() {
  local name="$1"
  local value
  value="$(value_of "$name")"
  if [[ -z "$value" || "$value" == *":latest" || "$value" == "latest" ]]; then
    fail "$name must pin an explicit version, not latest"
  fi
}

require_real_value MAMOJI_PUBLIC_HOST 4
require_real_value MAMOJI_TLS_EMAIL 6
require_real_value MAMOJI_POSTGRES_PASSWORD 16
require_real_value MAMOJI_BOOTSTRAP_ADMIN_EMAIL 6
require_real_value MAMOJI_BOOTSTRAP_ADMIN_PASSWORD 12
require_real_value MAMOJI_BOOTSTRAP_COMPANY_NAME 2
require_real_value MAMOJI_MINIO_ACCESS_KEY 12
require_real_value MAMOJI_MINIO_SECRET_KEY 16
require_real_value MAMOJI_SMOKE_EMAIL 6
require_real_value MAMOJI_SMOKE_PASSWORD 12

require_equals MAMOJI_RUNTIME_ENVIRONMENT production
require_equals MAMOJI_BOOTSTRAP_MODE bootstrap
require_equals MAMOJI_REGISTRATION_MODE invite
require_equals MAMOJI_PASSWORD_REQUIRE_COMPLEXITY true
require_equals MAMOJI_FLYWAY_ENABLED true
require_equals MAMOJI_OUTBOX_ENABLED true
require_equals MAMOJI_OUTBOX_CONSUMER_ENABLED true
require_equals MAMOJI_SCHEMA_COMPATIBILITY_ENABLED false
require_equals MAMOJI_OBJECT_STORAGE_ENABLED true

require_https_url MAMOJI_PUBLIC_API_BASE_URL
require_https_url MAMOJI_MINIO_EXTERNAL_URL

require_no_latest MAMOJI_CADDY_VERSION
require_no_latest MAMOJI_MINIO_VERSION
require_no_latest MAMOJI_PROMETHEUS_VERSION
require_no_latest MAMOJI_BACKUP_HELPER_IMAGE

if [[ -z "${MAMOJI_ALLOWED_ORIGINS:-}" ]]; then
  fail "MAMOJI_ALLOWED_ORIGINS must not be empty"
else
  IFS=',' read -r -a allowed_origins <<< "$MAMOJI_ALLOWED_ORIGINS"
  for origin in "${allowed_origins[@]}"; do
    origin="${origin#"${origin%%[![:space:]]*}"}"
    origin="${origin%"${origin##*[![:space:]]}"}"
    if [[ "$origin" != https://* || "$origin" == *"localhost"* || "$origin" == *"127.0.0.1"* || "$origin" == *"example.com"* || "$origin" == *"*"* ]]; then
      fail "MAMOJI_ALLOWED_ORIGINS contains a non-production origin: $origin"
    fi
  done
fi

if ! [[ "${MAMOJI_PASSWORD_MIN_LENGTH:-0}" =~ ^[0-9]+$ ]] || (( MAMOJI_PASSWORD_MIN_LENGTH < 12 )); then
  fail "MAMOJI_PASSWORD_MIN_LENGTH must be at least 12"
fi

if (( ${#errors[@]} > 0 )); then
  printf 'Production environment check failed:\n' >&2
  printf ' - %s\n' "${errors[@]}" >&2
  exit 1
fi

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" config >/dev/null
echo "Production environment check passed"
