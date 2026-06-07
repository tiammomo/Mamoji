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

mkdir -p "$BACKUP_DIR"

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d postgres minio >/dev/null

echo "Backing up PostgreSQL to $BACKUP_DIR/postgres.dump"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
  pg_dump -U "$MAMOJI_POSTGRES_USER" -d "$MAMOJI_POSTGRES_DB" --format=custom --compress=9 \
  > "$BACKUP_DIR/postgres.dump"

echo "Backing up MinIO data to $BACKUP_DIR/minio-data.tar.gz"
MINIO_CONTAINER="$(docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps -q minio)"
docker run --rm --volumes-from "$MINIO_CONTAINER" "${MAMOJI_BACKUP_HELPER_IMAGE:-alpine:latest}" \
  sh -c "cd /data && tar -czf - ." \
  > "$BACKUP_DIR/minio-data.tar.gz"

cat > "$BACKUP_DIR/manifest.env" <<EOF
created_at=$STAMP
postgres_db=$MAMOJI_POSTGRES_DB
postgres_user=$MAMOJI_POSTGRES_USER
minio_bucket=${MAMOJI_MINIO_BUCKET:-mamoji}
compose_file=$COMPOSE_FILE
EOF

sha256sum "$BACKUP_DIR/postgres.dump" "$BACKUP_DIR/minio-data.tar.gz" > "$BACKUP_DIR/SHA256SUMS"

RETENTION_DAYS="${MAMOJI_BACKUP_RETENTION_DAYS:-14}"
find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime +"$RETENTION_DAYS" -print -exec rm -rf {} +

echo "Backup completed: $BACKUP_DIR"
