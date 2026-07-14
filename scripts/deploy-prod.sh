#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.production}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.prod.yml}"
SKIP_BACKUP="${SKIP_BACKUP:-false}"
SKIP_SMOKE="${SKIP_SMOKE:-false}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

ENV_FILE="$ENV_FILE" COMPOSE_FILE="$COMPOSE_FILE" "$ROOT_DIR/scripts/check-prod-env.sh"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

WAIT_TIMEOUT_SECONDS="${MAMOJI_SERVICE_WAIT_TIMEOUT_SECONDS:-180}"
if ! [[ "$WAIT_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "MAMOJI_SERVICE_WAIT_TIMEOUT_SECONDS must be a positive integer" >&2
  exit 1
fi

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

if [[ "$SKIP_BACKUP" != "true" ]]; then
  ENV_FILE="$ENV_FILE" COMPOSE_FILE="$COMPOSE_FILE" "$ROOT_DIR/scripts/backup-prod.sh"
fi

compose config >/dev/null
compose up -d --build --wait --wait-timeout "$WAIT_TIMEOUT_SECONDS"
compose ps

if [[ "$SKIP_SMOKE" != "true" ]]; then
  ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/smoke-prod.sh"
fi

echo "Deployment completed"
