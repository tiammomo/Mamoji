#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.production}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.prod.yml}"
BACKUP_DIR="${1:-}"

if [[ -z "$BACKUP_DIR" ]]; then
  echo "Usage: CONFIRM_RESTORE=yes $0 /path/to/backup-dir" >&2
  exit 1
fi

if [[ "${CONFIRM_RESTORE:-}" != "yes" ]]; then
  echo "Refusing to restore without CONFIRM_RESTORE=yes" >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

if [[ ! -f "$BACKUP_DIR/postgres.dump" || ! -f "$BACKUP_DIR/minio-data.tar.gz" ]]; then
  echo "Backup directory must contain postgres.dump and minio-data.tar.gz" >&2
  exit 1
fi

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

if [[ -f "$BACKUP_DIR/SHA256SUMS" ]]; then
  POSTGRES_SHA="$(awk '$2 == "postgres.dump" || $2 ~ /\/postgres\.dump$/ { print $1; exit }' "$BACKUP_DIR/SHA256SUMS")"
  MINIO_SHA="$(awk '$2 == "minio-data.tar.gz" || $2 ~ /\/minio-data\.tar\.gz$/ { print $1; exit }' "$BACKUP_DIR/SHA256SUMS")"
  if [[ -z "$POSTGRES_SHA" || -z "$MINIO_SHA" ]]; then
    echo "SHA256SUMS must contain postgres.dump and minio-data.tar.gz" >&2
    exit 1
  fi
  (
    cd "$BACKUP_DIR"
    printf '%s  postgres.dump\n%s  minio-data.tar.gz\n' "$POSTGRES_SHA" "$MINIO_SHA" | sha256sum -c -
  )
fi

compose stop backend frontend caddy prometheus >/dev/null 2>&1 || true
compose up -d --wait --wait-timeout "$WAIT_TIMEOUT_SECONDS" postgres minio >/dev/null

echo "Restoring PostgreSQL from $BACKUP_DIR/postgres.dump"
compose exec -T postgres \
  sh -c "dropdb -U \"$MAMOJI_POSTGRES_USER\" --if-exists \"$MAMOJI_POSTGRES_DB\" && createdb -U \"$MAMOJI_POSTGRES_USER\" \"$MAMOJI_POSTGRES_DB\""
compose exec -T postgres \
  pg_restore -U "$MAMOJI_POSTGRES_USER" -d "$MAMOJI_POSTGRES_DB" --no-owner --clean --if-exists \
  < "$BACKUP_DIR/postgres.dump"

echo "Restoring MinIO data from $BACKUP_DIR/minio-data.tar.gz"
MINIO_CONTAINER="$(compose ps -q minio)"
if [[ -z "$MINIO_CONTAINER" ]]; then
  echo "MinIO container is not running" >&2
  exit 1
fi
compose stop minio >/dev/null
docker run --rm -i --volumes-from "$MINIO_CONTAINER" "${MAMOJI_BACKUP_HELPER_IMAGE:-alpine:latest}" \
  sh -ceu 'find /data -mindepth 1 -maxdepth 1 -exec rm -rf -- {} \; && tar -xzf - -C /data' \
  < "$BACKUP_DIR/minio-data.tar.gz"

compose up -d --wait --wait-timeout "$WAIT_TIMEOUT_SECONDS" minio >/dev/null
compose up -d --wait --wait-timeout "$WAIT_TIMEOUT_SECONDS" backend frontend caddy prometheus >/dev/null

echo "Restore completed from: $BACKUP_DIR"
