#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.production}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.prod.yml}"
BACKUP_ROOT="${BACKUP_ROOT:-$ROOT_DIR/backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$BACKUP_ROOT/$STAMP"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE" >&2
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

mkdir -p "$BACKUP_DIR"

POSTGRES_WAS_RUNNING=false
RUNNING_CONTAINER="$(compose ps --status running -q postgres)"
if [[ -n "$RUNNING_CONTAINER" ]]; then
  POSTGRES_WAS_RUNNING=true
fi

MINIO_WAS_RUNNING=false
RESTART_SERVICES=()
for service in minio backend frontend caddy; do
  RUNNING_CONTAINER="$(compose ps --status running -q "$service")"
  if [[ -n "$RUNNING_CONTAINER" ]]; then
    RESTART_SERVICES+=("$service")
    if [[ "$service" == "minio" ]]; then
      MINIO_WAS_RUNNING=true
    fi
  fi
done

MAINTENANCE_ACTIVE=false
finish_maintenance() {
  if [[ "$MAINTENANCE_ACTIVE" != "true" ]]; then
    return 0
  fi
  local status=0
  if (( ${#RESTART_SERVICES[@]} > 0 )); then
    echo "Restoring services after backup maintenance window"
    if ! compose start --wait --wait-timeout "$WAIT_TIMEOUT_SECONDS" "${RESTART_SERVICES[@]}" >/dev/null; then
      echo "Backup finished, but one or more previously running services failed to restart" >&2
      status=1
    fi
  fi
  if [[ "$MINIO_WAS_RUNNING" != "true" ]]; then
    if ! compose stop minio >/dev/null; then
      echo "Backup finished, but MinIO could not be returned to its previous stopped state" >&2
      status=1
    fi
  fi
  if [[ "$POSTGRES_WAS_RUNNING" != "true" ]]; then
    if ! compose stop postgres >/dev/null; then
      echo "Backup finished, but PostgreSQL could not be returned to its previous stopped state" >&2
      status=1
    fi
  fi
  MAINTENANCE_ACTIVE=false
  return "$status"
}

on_exit() {
  local status=$?
  trap - EXIT
  if ! finish_maintenance; then
    status=1
  fi
  exit "$status"
}
trap on_exit EXIT

MAINTENANCE_ACTIVE=true

echo "Entering backup maintenance window"
compose stop caddy frontend backend minio >/dev/null
for service in caddy frontend backend minio; do
  RUNNING_CONTAINER="$(compose ps --status running -q "$service")"
  if [[ -n "$RUNNING_CONTAINER" ]]; then
    echo "Cannot continue: $service is still running after the maintenance stop" >&2
    exit 1
  fi
done

compose up -d --no-recreate --wait --wait-timeout "$WAIT_TIMEOUT_SECONDS" postgres minio >/dev/null
compose stop minio >/dev/null
RUNNING_CONTAINER="$(compose ps --status running -q minio)"
if [[ -n "$RUNNING_CONTAINER" ]]; then
  echo "Cannot continue: MinIO is still running and cannot be captured consistently" >&2
  exit 1
fi

echo "Backing up PostgreSQL to $BACKUP_DIR/postgres.dump"
compose exec -T postgres \
  pg_dump -U "$MAMOJI_POSTGRES_USER" -d "$MAMOJI_POSTGRES_DB" --format=custom --compress=9 \
  > "$BACKUP_DIR/postgres.dump"

echo "Backing up MinIO data to $BACKUP_DIR/minio-data.tar.gz"
MINIO_CONTAINER="$(compose ps --all -q minio)"
if [[ -z "$MINIO_CONTAINER" ]]; then
  echo "MinIO container is unavailable" >&2
  exit 1
fi
docker run --rm --volumes-from "$MINIO_CONTAINER" "${MAMOJI_BACKUP_HELPER_IMAGE:-alpine:latest}" \
  sh -c "cd /data && tar -czf - ." \
  > "$BACKUP_DIR/minio-data.tar.gz"

cat > "$BACKUP_DIR/manifest.env" <<EOF
created_at=$STAMP
postgres_db=$MAMOJI_POSTGRES_DB
postgres_user=$MAMOJI_POSTGRES_USER
minio_bucket=${MAMOJI_MINIO_BUCKET:-mamoji}
compose_file=$COMPOSE_FILE
consistency=quiesced_backend_and_object_storage
EOF

(cd "$BACKUP_DIR" && sha256sum postgres.dump minio-data.tar.gz > SHA256SUMS)

RETENTION_DAYS="${MAMOJI_BACKUP_RETENTION_DAYS:-14}"
find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime +"$RETENTION_DAYS" -print -exec rm -rf {} +

finish_maintenance
trap - EXIT
echo "Backup completed: $BACKUP_DIR"
