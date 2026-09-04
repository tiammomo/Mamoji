#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE_DIR="$ROOT_DIR/scripts/tests/fixtures"
STATE_ROOT="$(mktemp -d)"
trap 'rm -rf -- "$STATE_ROOT"' EXIT

run_backup() {
  local state_dir="$1"
  local backup_root="$2"
  shift 2
  mkdir -p "$state_dir" "$backup_root"
  env \
    PATH="$FIXTURE_DIR/backup-bin:$PATH" \
    ENV_FILE="$FIXTURE_DIR/backup.env" \
    COMPOSE_FILE="$ROOT_DIR/docker-compose.prod.yml" \
    BACKUP_ROOT="$backup_root" \
    MAMOJI_BACKUP_FAKE_STATE_DIR="$state_dir" \
    "$@" \
    "$ROOT_DIR/scripts/backup-prod.sh"
}

kept_state="$STATE_ROOT/kept-state"
kept_backups="$STATE_ROOT/kept-backups"
kept_output="$(run_backup "$kept_state" "$kept_backups" MAMOJI_BACKUP_KEEP_APPLICATION_STOPPED=true)"
[[ "$kept_output" == *"Backup completed:"* ]]
[[ -e "$kept_state/backend.stopped" ]]
[[ -e "$kept_state/frontend.stopped" ]]
[[ -e "$kept_state/caddy.stopped" ]]
[[ ! -e "$kept_state/minio.stopped" ]]
[[ ! -e "$kept_state/postgres.stopped" ]]
grep -q 'start --wait --wait-timeout 180 minio' "$kept_state/docker.log"
if grep -q 'start .*backend\|start .*frontend\|start .*caddy' "$kept_state/docker.log"; then
  echo "Successful deployment backup unexpectedly restarted an application service" >&2
  exit 1
fi
kept_backup_dir="$(find "$kept_backups" -mindepth 1 -maxdepth 1 -type d)"
(cd "$kept_backup_dir" && sha256sum --check SHA256SUMS >/dev/null)

normal_state="$STATE_ROOT/normal-state"
run_backup "$normal_state" "$STATE_ROOT/normal-backups" >/dev/null
for service in postgres minio backend frontend caddy; do
  [[ ! -e "$normal_state/$service.stopped" ]]
done

failed_state="$STATE_ROOT/failed-state"
if run_backup "$failed_state" "$STATE_ROOT/failed-backups" \
  MAMOJI_BACKUP_KEEP_APPLICATION_STOPPED=true \
  MAMOJI_BACKUP_FAKE_OBJECT_FAILURE=true >"$STATE_ROOT/failed.out" 2>"$STATE_ROOT/failed.err"; then
  echo "Backup unexpectedly accepted an object-storage backup failure" >&2
  exit 1
fi
grep -q 'simulated object backup failure' "$STATE_ROOT/failed.err"
for service in postgres minio backend frontend caddy; do
  [[ ! -e "$failed_state/$service.stopped" ]]
done

echo "Backup maintenance script tests passed"
