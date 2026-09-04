#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.production}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.prod.yml}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

configured_value() {
  local name="$1"
  local default_value="${2:-}"
  if [[ -n "${!name:-}" ]]; then
    printf '%s' "${!name}"
    return
  fi
  (
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    printf '%s' "${!name:-$default_value}"
  )
}

EXPECTED_REPLICAS="$(configured_value MAMOJI_REPLICA_SMOKE_EXPECTED_REPLICAS)"
EXPECTED_REPLICAS="${EXPECTED_REPLICAS:-$(configured_value MAMOJI_BACKEND_REPLICAS 1)}"
SMOKE_EMAIL="$(configured_value MAMOJI_REPLICA_SMOKE_EMAIL)"
SMOKE_EMAIL="${SMOKE_EMAIL:-$(configured_value MAMOJI_SMOKE_EMAIL)}"
SMOKE_EMAIL="${SMOKE_EMAIL:-$(configured_value MAMOJI_BOOTSTRAP_ADMIN_EMAIL)}"
SMOKE_PASSWORD="$(configured_value MAMOJI_REPLICA_SMOKE_PASSWORD)"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-$(configured_value MAMOJI_SMOKE_PASSWORD)}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-$(configured_value MAMOJI_BOOTSTRAP_ADMIN_PASSWORD)}"
PUBLIC_HOST="$(configured_value MAMOJI_PUBLIC_HOST localhost)"
PUBLIC_API_BASE_URL="$(configured_value MAMOJI_PUBLIC_API_BASE_URL)"
BASE_URL="${BASE_URL:-https://$PUBLIC_HOST}"
API_BASE_URL="${API_BASE_URL:-${PUBLIC_API_BASE_URL:-$BASE_URL/api/v1}}"
HEALTH_URL="${HEALTH_URL:-$BASE_URL/healthz}"
APP_ORIGIN="${APP_ORIGIN:-$BASE_URL}"
ALLOW_RESTART="$(configured_value MAMOJI_REPLICA_SMOKE_ALLOW_RESTART no)"
MAX_ATTEMPTS="$(configured_value MAMOJI_REPLICA_SMOKE_MAX_ATTEMPTS 30)"
RETRY_DELAY_SECONDS="$(configured_value MAMOJI_REPLICA_SMOKE_RETRY_DELAY_SECONDS 2)"
HTTP_TIMEOUT_SECONDS="$(configured_value MAMOJI_REPLICA_SMOKE_HTTP_TIMEOUT_SECONDS 10)"
STOP_TIMEOUT_SECONDS="$(configured_value MAMOJI_REPLICA_SMOKE_STOP_TIMEOUT_SECONDS 45)"

for setting in EXPECTED_REPLICAS MAX_ATTEMPTS RETRY_DELAY_SECONDS HTTP_TIMEOUT_SECONDS STOP_TIMEOUT_SECONDS; do
  value="${!setting}"
  if ! [[ "$value" =~ ^[1-9][0-9]*$ ]]; then
    echo "$setting must be a positive integer" >&2
    exit 1
  fi
done

if (( EXPECTED_REPLICAS < 2 )); then
  echo "Replica smoke requires at least two expected backend replicas" >&2
  exit 1
fi

if [[ "$ALLOW_RESTART" != "no" && "$ALLOW_RESTART" != "yes" ]]; then
  echo "MAMOJI_REPLICA_SMOKE_ALLOW_RESTART must be yes or no" >&2
  exit 1
fi

if [[ -z "$SMOKE_EMAIL" || -z "$SMOKE_PASSWORD" ]]; then
  echo "Missing replica smoke credentials" >&2
  exit 1
fi

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

container_is_running() {
  [[ "$(docker inspect --format '{{.State.Running}}' "$1" 2>/dev/null || true)" == "true" ]]
}

container_is_healthy() {
  [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$1" 2>/dev/null || true)" == "healthy" ]]
}

wait_for_container_health() {
  local container_id="$1"
  local attempt
  for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1)); do
    if container_is_running "$container_id" \
      && container_is_healthy "$container_id" \
      && docker exec "$container_id" curl -fsS --max-time "$HTTP_TIMEOUT_SECONDS" \
        http://localhost:38080/actuator/health/readiness >/dev/null; then
      return 0
    fi
    sleep "$RETRY_DELAY_SECONDS"
  done
  return 1
}

wait_for_public_health() {
  local attempt
  for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1)); do
    if curl -fsS --max-time "$HTTP_TIMEOUT_SECONDS" "$HEALTH_URL" >/dev/null; then
      return 0
    fi
    sleep "$RETRY_DELAY_SECONDS"
  done
  return 1
}

container_authenticated_status() {
  local container_id="$1"
  local method="$2"
  local path="$3"
  printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" | docker exec -i "$container_id" \
    curl -sS --max-time "$HTTP_TIMEOUT_SECONDS" --output /dev/null --write-out '%{http_code}' \
    --request "$method" --config - "http://localhost:38080$path"
}

public_authenticated_status() {
  local method="$1"
  local url="$2"
  printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" | \
    curl -sS --max-time "$HTTP_TIMEOUT_SECONDS" --output /dev/null --write-out '%{http_code}' \
      --request "$method" --config - "$url"
}

TOKEN=""
STOPPED_CONTAINER=""
BACKEND_IDS=()

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  if [[ -n "$STOPPED_CONTAINER" ]]; then
    if ! container_is_running "$STOPPED_CONTAINER"; then
      echo "Restoring stopped backend replica after smoke failure" >&2
      docker start "$STOPPED_CONTAINER" >/dev/null
    fi
    wait_for_container_health "$STOPPED_CONTAINER" || \
      echo "Backend replica $STOPPED_CONTAINER did not become healthy during cleanup" >&2
  fi
  if [[ -n "$TOKEN" ]]; then
    local container_id
    for container_id in "${BACKEND_IDS[@]}"; do
      if container_is_running "$container_id"; then
        container_authenticated_status "$container_id" POST /api/v1/auth/logout >/dev/null || true
        break
      fi
    done
  fi
  exit "$status"
}
trap cleanup EXIT

mapfile -t BACKEND_IDS < <(compose ps -q backend)
if (( ${#BACKEND_IDS[@]} != EXPECTED_REPLICAS )); then
  echo "Expected $EXPECTED_REPLICAS backend replicas, found ${#BACKEND_IDS[@]}" >&2
  exit 1
fi

for container_id in "${BACKEND_IDS[@]}"; do
  if ! wait_for_container_health "$container_id"; then
    echo "Backend replica $container_id did not become ready" >&2
    exit 1
  fi
done
echo "Verified readiness for ${#BACKEND_IDS[@]} backend replicas"

LOGIN_PAYLOAD="$(SMOKE_EMAIL="$SMOKE_EMAIL" SMOKE_PASSWORD="$SMOKE_PASSWORD" node -e '
  process.stdout.write(JSON.stringify({email: process.env.SMOKE_EMAIL, password: process.env.SMOKE_PASSWORD}));
')"
LOGIN_RESPONSE="$(printf '%s' "$LOGIN_PAYLOAD" | docker exec -i "${BACKEND_IDS[0]}" \
  curl -fsS --max-time "$HTTP_TIMEOUT_SECONDS" \
  --header 'Content-Type: application/json' \
  --header "Origin: $APP_ORIGIN" \
  --data-binary @- \
  http://localhost:38080/api/v1/auth/login)"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | node -e '
  let body = "";
  process.stdin.setEncoding("utf8");
  process.stdin.on("data", (chunk) => { body += chunk; });
  process.stdin.on("end", () => {
    const response = JSON.parse(body);
    if (typeof response.token !== "string" || response.token.length < 40 || !/^[A-Za-z0-9_-]+$/.test(response.token)) {
      process.exit(1);
    }
    process.stdout.write(response.token);
  });
')"

for container_id in "${BACKEND_IDS[@]}"; do
  status="$(container_authenticated_status "$container_id" GET /api/v1/auth/me)"
  if [[ "$status" != "200" ]]; then
    echo "Cross-replica session check failed on $container_id with HTTP $status" >&2
    exit 1
  fi
done
echo "Verified one database-backed session across ${#BACKEND_IDS[@]} replicas"

if [[ "$ALLOW_RESTART" == "yes" ]]; then
  STOPPED_CONTAINER="${BACKEND_IDS[0]}"
  docker stop --time "$STOP_TIMEOUT_SECONDS" "$STOPPED_CONTAINER" >/dev/null

  for container_id in "${BACKEND_IDS[@]:1}"; do
    if ! wait_for_container_health "$container_id"; then
      echo "Remaining backend replica $container_id became unhealthy" >&2
      exit 1
    fi
  done
  if ! wait_for_public_health; then
    echo "Public health did not recover after stopping one backend replica" >&2
    exit 1
  fi
  status="$(public_authenticated_status GET "$API_BASE_URL/auth/me")"
  if [[ "$status" != "200" ]]; then
    echo "Public authenticated request failed over with HTTP $status" >&2
    exit 1
  fi
  echo "Verified public failover with one backend replica stopped"

  docker start "$STOPPED_CONTAINER" >/dev/null
  if ! wait_for_container_health "$STOPPED_CONTAINER"; then
    echo "Restarted backend replica $STOPPED_CONTAINER did not become ready" >&2
    exit 1
  fi
  status="$(container_authenticated_status "$STOPPED_CONTAINER" GET /api/v1/auth/me)"
  if [[ "$status" != "200" ]]; then
    echo "Session was not accepted by restarted backend replica; got HTTP $status" >&2
    exit 1
  fi
  STOPPED_CONTAINER=""
  echo "Verified the stopped backend replica recovered healthy"
fi

status="$(container_authenticated_status "${BACKEND_IDS[-1]}" POST /api/v1/auth/logout)"
if [[ "$status" != "200" ]]; then
  echo "Cross-replica logout failed with HTTP $status" >&2
  exit 1
fi
status="$(container_authenticated_status "${BACKEND_IDS[0]}" GET /api/v1/auth/me)"
if [[ "$status" != "401" ]]; then
  echo "Logged-out session remained valid on another replica; got HTTP $status" >&2
  exit 1
fi
TOKEN=""

echo "Replica smoke passed with replicas=$EXPECTED_REPLICAS failover=$ALLOW_RESTART"
