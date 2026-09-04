#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE_DIR="$ROOT_DIR/scripts/tests/fixtures"
STATE_ROOT="$(mktemp -d)"
trap 'rm -rf -- "$STATE_ROOT"' EXIT

run_smoke() {
  local state_dir="$1"
  shift
  mkdir -p "$state_dir"
  env \
    PATH="$FIXTURE_DIR/replica-smoke-bin:$PATH" \
    ENV_FILE="$FIXTURE_DIR/replica-smoke.env" \
    COMPOSE_FILE="$ROOT_DIR/docker-compose.prod.yml" \
    MAMOJI_REPLICA_SMOKE_FAKE_STATE_DIR="$state_dir" \
    MAMOJI_REPLICA_SMOKE_RETRY_DELAY_SECONDS=1 \
    "$@" \
    "$ROOT_DIR/scripts/replica-smoke.sh"
}

readonly_state="$STATE_ROOT/readonly"
readonly_output="$(run_smoke "$readonly_state")"
[[ "$readonly_output" == *"Replica smoke passed with replicas=2 failover=no"* ]]
[[ ! -e "$readonly_state/lifecycle.log" ]]
if grep -q 'fake-token-that-is-long-enough' "$readonly_state/docker.log"; then
  echo "Replica smoke exposed its session token in a Docker command" >&2
  exit 1
fi

override_state="$STATE_ROOT/override"
override_output="$(run_smoke "$override_state" \
  MAMOJI_REPLICA_SMOKE_EXPECTED_REPLICAS=3 \
  MAMOJI_REPLICA_SMOKE_FAKE_REPLICAS=3)"
[[ "$override_output" == *"Replica smoke passed with replicas=3 failover=no"* ]]

failover_state="$STATE_ROOT/failover"
failover_output="$(run_smoke "$failover_state" MAMOJI_REPLICA_SMOKE_ALLOW_RESTART=yes)"
[[ "$failover_output" == *"Verified public failover with one backend replica stopped"* ]]
[[ "$failover_output" == *"Replica smoke passed with replicas=2 failover=yes"* ]]
grep -qx 'stop backend-1' "$failover_state/lifecycle.log"
grep -qx 'start backend-1' "$failover_state/lifecycle.log"
[[ ! -e "$failover_state/backend-1.stopped" ]]

cleanup_state="$STATE_ROOT/cleanup"
if run_smoke "$cleanup_state" \
  MAMOJI_REPLICA_SMOKE_ALLOW_RESTART=yes \
  MAMOJI_REPLICA_SMOKE_FAKE_PUBLIC_FAILURE=yes \
  MAMOJI_REPLICA_SMOKE_MAX_ATTEMPTS=1 >"$STATE_ROOT/cleanup.out" 2>"$STATE_ROOT/cleanup.err"; then
  echo "Replica smoke unexpectedly accepted failed public failover" >&2
  exit 1
fi
grep -q 'Public health did not recover' "$STATE_ROOT/cleanup.err"
grep -q 'Restoring stopped backend replica after smoke failure' "$STATE_ROOT/cleanup.err"
grep -qx 'stop backend-1' "$cleanup_state/lifecycle.log"
grep -qx 'start backend-1' "$cleanup_state/lifecycle.log"
[[ ! -e "$cleanup_state/backend-1.stopped" ]]

mismatch_state="$STATE_ROOT/mismatch"
if run_smoke "$mismatch_state" MAMOJI_REPLICA_SMOKE_FAKE_REPLICAS=1 >"$STATE_ROOT/mismatch.out" 2>"$STATE_ROOT/mismatch.err"; then
  echo "Replica smoke unexpectedly accepted a missing backend replica" >&2
  exit 1
fi
grep -q 'Expected 2 backend replicas, found 1' "$STATE_ROOT/mismatch.err"

echo "Replica smoke script tests passed"
