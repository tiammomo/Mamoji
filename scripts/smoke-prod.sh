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
SMOKE_MAX_ATTEMPTS="${MAMOJI_SMOKE_MAX_ATTEMPTS:-12}"
SMOKE_RETRY_DELAY_SECONDS="${MAMOJI_SMOKE_RETRY_DELAY_SECONDS:-5}"

if [[ -z "$SMOKE_EMAIL" || -z "$SMOKE_PASSWORD" ]]; then
  echo "Missing MAMOJI_SMOKE_EMAIL or MAMOJI_SMOKE_PASSWORD" >&2
  exit 1
fi

if ! [[ "$SMOKE_MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || ! [[ "$SMOKE_RETRY_DELAY_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "Smoke retry settings must be positive integers" >&2
  exit 1
fi

BASE_URL="$BASE_URL" \
API_BASE_URL="$API_BASE_URL" \
HEALTH_URL="$HEALTH_URL" \
LOGIN_PAGE_URL="$LOGIN_PAGE_URL" \
SMOKE_EMAIL="$SMOKE_EMAIL" \
SMOKE_PASSWORD="$SMOKE_PASSWORD" \
REGISTRATION_MODE="$REGISTRATION_MODE" \
SMOKE_MAX_ATTEMPTS="$SMOKE_MAX_ATTEMPTS" \
SMOKE_RETRY_DELAY_SECONDS="$SMOKE_RETRY_DELAY_SECONDS" \
node <<'NODE'
const baseUrl = process.env.BASE_URL;
const apiBaseUrl = process.env.API_BASE_URL;
const healthUrl = process.env.HEALTH_URL;
const loginPageUrl = process.env.LOGIN_PAGE_URL;
const email = process.env.SMOKE_EMAIL;
const password = process.env.SMOKE_PASSWORD;
const registrationMode = process.env.REGISTRATION_MODE;
const maxAttempts = Number.parseInt(process.env.SMOKE_MAX_ATTEMPTS, 10);
const retryDelayMs = Number.parseInt(process.env.SMOKE_RETRY_DELAY_SECONDS, 10) * 1000;

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const smokeFetch = (url, options = {}) => fetch(url, {
  ...options,
  signal: AbortSignal.timeout(15000)
});

async function expectOk(label, promise) {
  const res = await promise;
  if (!res.ok) {
    throw new Error(label + " failed with HTTP " + res.status);
  }
  return res;
}

async function waitForOk(label, url) {
  let lastError;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      const response = await smokeFetch(url);
      if (response.ok) {
        return response;
      }
      lastError = new Error(label + " failed with HTTP " + response.status);
      await response.body?.cancel().catch(() => {});
    } catch (error) {
      lastError = error;
    }
    if (attempt < maxAttempts) {
      console.error(label + " is not ready (attempt " + attempt + "/" + maxAttempts + ")");
      await sleep(retryDelayMs);
    }
  }
  throw lastError || new Error(label + " did not become ready");
}

async function main() {
  await waitForOk("health", healthUrl);
  const loginPage = await waitForOk("login page", loginPageUrl);
  const loginPageHtml = await loginPage.text();
  if (registrationMode === "invite" && loginPageHtml.includes("test@mamoji.com / 123456")) {
    throw new Error("production login page must not display demo credentials");
  }
  const meBefore = await smokeFetch(apiBaseUrl + "/auth/me");
  if (meBefore.status !== 401) {
    throw new Error("unauthenticated /auth/me expected HTTP 401, got " + meBefore.status);
  }
  const preflight = await smokeFetch(apiBaseUrl + "/auth/login", {
    method: "OPTIONS",
    headers: {
      Origin: baseUrl,
      "Access-Control-Request-Method": "POST",
      "Access-Control-Request-Headers": "content-type"
    }
  });
  if (!preflight.ok) {
    throw new Error("CORS preflight failed with HTTP " + preflight.status);
  }
  const login = await expectOk("login", smokeFetch(apiBaseUrl + "/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json", Origin: baseUrl },
    body: JSON.stringify({ email, password })
  }));
  const session = await login.json();
  if (!session.token || !session.tokenExpiresAt) {
    throw new Error("login did not return token and tokenExpiresAt");
  }
  if (session.token.length < 40) {
    throw new Error("login returned a weak-looking token");
  }
  if (JSON.stringify(session).includes("passwordHash")) {
    throw new Error("login response leaked passwordHash");
  }
  const headers = { Authorization: "Bearer " + session.token };
  const me = await expectOk("me", smokeFetch(apiBaseUrl + "/auth/me", { headers }));
  if ((await me.text()).includes("passwordHash")) {
    throw new Error("/auth/me leaked passwordHash");
  }
  await expectOk("employees", smokeFetch(apiBaseUrl + "/enterprise/employees", { headers }));
  await expectOk("payroll runs", smokeFetch(apiBaseUrl + "/payroll-runs", { headers }));
  await expectOk("tax compliance", smokeFetch(apiBaseUrl + "/enterprise/tax-compliance", { headers }));
  await expectOk("backup status", smokeFetch(apiBaseUrl + "/backup/status", { headers }));
  await expectOk("notifications summary", smokeFetch(apiBaseUrl + "/notifications/summary", { headers }));
  await expectOk("audit logs", smokeFetch(apiBaseUrl + "/audit-logs?size=1", { headers }));
  if (registrationMode === "invite") {
    const blockedRegister = await smokeFetch(apiBaseUrl + "/auth/register", {
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
  await expectOk("logout", smokeFetch(apiBaseUrl + "/auth/logout", { method: "POST", headers }));
  const meAfter = await smokeFetch(apiBaseUrl + "/auth/me", { headers });
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
