#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.production}"
BASE_URL="${BASE_URL:-}"
API_BASE_URL="${API_BASE_URL:-}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

BASE_URL="${BASE_URL:-https://${MAMOJI_PUBLIC_HOST:-localhost}}"
API_BASE_URL="${API_BASE_URL:-${MAMOJI_PUBLIC_API_BASE_URL:-$BASE_URL/api/v1}}"
HEALTH_URL="${HEALTH_URL:-$BASE_URL/healthz}"
LOGIN_PAGE_URL="${LOGIN_PAGE_URL:-$BASE_URL/login}"
SMOKE_EMAIL="${MAMOJI_SMOKE_EMAIL:-${MAMOJI_BOOTSTRAP_ADMIN_EMAIL:-}}"
SMOKE_PASSWORD="${MAMOJI_SMOKE_PASSWORD:-${MAMOJI_BOOTSTRAP_ADMIN_PASSWORD:-}}"
REGISTRATION_MODE="${MAMOJI_REGISTRATION_MODE:-open}"

if [[ -z "$SMOKE_EMAIL" || -z "$SMOKE_PASSWORD" ]]; then
  echo "Missing MAMOJI_SMOKE_EMAIL or MAMOJI_SMOKE_PASSWORD" >&2
  exit 1
fi

node <<NODE
const baseUrl = ${BASE_URL@Q};
const apiBaseUrl = ${API_BASE_URL@Q};
const healthUrl = ${HEALTH_URL@Q};
const loginPageUrl = ${LOGIN_PAGE_URL@Q};
const email = ${SMOKE_EMAIL@Q};
const password = ${SMOKE_PASSWORD@Q};
const registrationMode = ${REGISTRATION_MODE@Q};

async function expectOk(label, promise) {
  const res = await promise;
  if (!res.ok) {
    throw new Error(label + " failed with HTTP " + res.status);
  }
  return res;
}

async function main() {
  await expectOk("health", fetch(healthUrl));
  await expectOk("login page", fetch(loginPageUrl));
  const login = await expectOk("login", fetch(apiBaseUrl + "/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password })
  }));
  const session = await login.json();
  if (!session.token || !session.tokenExpiresAt) {
    throw new Error("login did not return token and tokenExpiresAt");
  }
  const headers = { Authorization: "Bearer " + session.token };
  await expectOk("me", fetch(apiBaseUrl + "/auth/me", { headers }));
  await expectOk("employees", fetch(apiBaseUrl + "/enterprise/employees", { headers }));
  await expectOk("payroll runs", fetch(apiBaseUrl + "/payroll-runs", { headers }));
  await expectOk("tax compliance", fetch(apiBaseUrl + "/enterprise/tax-compliance", { headers }));
  await expectOk("audit logs", fetch(apiBaseUrl + "/audit-logs?size=1", { headers }));
  if (registrationMode === "invite") {
    const blockedRegister = await fetch(apiBaseUrl + "/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: "smoke-" + Date.now() + "@example.invalid",
        nickname: "Smoke",
        password: "Smoke-password-123"
      })
    });
    if (blockedRegister.status !== 403) {
      throw new Error("invite registration expected HTTP 403, got " + blockedRegister.status);
    }
  }
  await expectOk("logout", fetch(apiBaseUrl + "/auth/logout", { method: "POST", headers }));
  const meAfter = await fetch(apiBaseUrl + "/auth/me", { headers });
  if (meAfter.status !== 401) {
    throw new Error("logout token invalidation expected HTTP 401, got " + meAfter.status);
  }
  console.log("Smoke passed:", baseUrl);
}

main().catch((error) => {
  console.error(error.message || error);
  process.exit(1);
});
NODE
