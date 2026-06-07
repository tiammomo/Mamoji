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

if [[ "$SKIP_BACKUP" != "true" ]]; then
  ENV_FILE="$ENV_FILE" COMPOSE_FILE="$COMPOSE_FILE" "$ROOT_DIR/scripts/backup-prod.sh"
fi

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" config >/dev/null
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --build
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps

if [[ "$SKIP_SMOKE" != "true" ]]; then
  ENV_FILE="$ENV_FILE" "$ROOT_DIR/scripts/smoke-prod.sh"
fi

echo "Deployment completed"
