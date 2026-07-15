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
LOAD_MODE="${MAMOJI_LOAD_MODE:-read}"
LOAD_CONCURRENCY="${MAMOJI_LOAD_CONCURRENCY:-8}"
LOAD_OPERATIONS="${MAMOJI_LOAD_OPERATIONS:-200}"
LOAD_TIMEOUT_MS="${MAMOJI_LOAD_TIMEOUT_MS:-10000}"
LOAD_P95_LIMIT_MS="${MAMOJI_LOAD_P95_LIMIT_MS:-2000}"
LOAD_MAX_ERROR_RATE_PERCENT="${MAMOJI_LOAD_MAX_ERROR_RATE_PERCENT:-0}"
LOAD_WRITE_EVERY="${MAMOJI_LOAD_WRITE_EVERY:-20}"
LOAD_WARMUP_ROUNDS="${MAMOJI_LOAD_WARMUP_ROUNDS:-1}"
LOAD_COMPANY_ID="${MAMOJI_LOAD_COMPANY_ID:-}"
LOAD_TOKEN="${MAMOJI_LOAD_TOKEN:-}"
LOAD_EMAIL="${MAMOJI_LOAD_EMAIL:-${MAMOJI_SMOKE_EMAIL:-${MAMOJI_BOOTSTRAP_ADMIN_EMAIL:-}}}"
LOAD_PASSWORD="${MAMOJI_LOAD_PASSWORD:-${MAMOJI_SMOKE_PASSWORD:-${MAMOJI_BOOTSTRAP_ADMIN_PASSWORD:-}}}"

if [[ "$LOAD_MODE" != "read" && "$LOAD_MODE" != "mixed" ]]; then
  echo "MAMOJI_LOAD_MODE must be read or mixed" >&2
  exit 1
fi

if [[ "$LOAD_MODE" == "mixed" && "${MAMOJI_LOAD_ALLOW_WRITES:-no}" != "yes" ]]; then
  echo "Mixed mode creates and deletes temporary categories; set MAMOJI_LOAD_ALLOW_WRITES=yes to continue" >&2
  exit 1
fi

if [[ -z "$LOAD_TOKEN" && ( -z "$LOAD_EMAIL" || -z "$LOAD_PASSWORD" ) ]]; then
  echo "Provide MAMOJI_LOAD_TOKEN or load-test credentials via MAMOJI_LOAD_EMAIL/MAMOJI_LOAD_PASSWORD" >&2
  exit 1
fi

BASE_URL="$BASE_URL" \
API_BASE_URL="$API_BASE_URL" \
HEALTH_URL="$HEALTH_URL" \
LOAD_MODE="$LOAD_MODE" \
LOAD_CONCURRENCY="$LOAD_CONCURRENCY" \
LOAD_OPERATIONS="$LOAD_OPERATIONS" \
LOAD_TIMEOUT_MS="$LOAD_TIMEOUT_MS" \
LOAD_P95_LIMIT_MS="$LOAD_P95_LIMIT_MS" \
LOAD_MAX_ERROR_RATE_PERCENT="$LOAD_MAX_ERROR_RATE_PERCENT" \
LOAD_WRITE_EVERY="$LOAD_WRITE_EVERY" \
LOAD_WARMUP_ROUNDS="$LOAD_WARMUP_ROUNDS" \
LOAD_COMPANY_ID="$LOAD_COMPANY_ID" \
LOAD_TOKEN="$LOAD_TOKEN" \
LOAD_EMAIL="$LOAD_EMAIL" \
LOAD_PASSWORD="$LOAD_PASSWORD" \
node <<'NODE'
const { performance } = require("node:perf_hooks");

const trimSlash = (value) => value.replace(/\/+$/, "");
const baseUrl = trimSlash(process.env.BASE_URL);
const apiBaseUrl = trimSlash(process.env.API_BASE_URL);
const healthUrl = process.env.HEALTH_URL;
const mode = process.env.LOAD_MODE;
const suppliedToken = process.env.LOAD_TOKEN;
const email = process.env.LOAD_EMAIL;
const password = process.env.LOAD_PASSWORD;

function positiveInteger(name, value, { allowZero = false } = {}) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || (allowZero ? parsed < 0 : parsed < 1) || String(parsed) !== String(value)) {
    throw new Error(`${name} must be ${allowZero ? "a non-negative" : "a positive"} integer`);
  }
  return parsed;
}

const concurrency = positiveInteger("MAMOJI_LOAD_CONCURRENCY", process.env.LOAD_CONCURRENCY);
const operationCount = positiveInteger("MAMOJI_LOAD_OPERATIONS", process.env.LOAD_OPERATIONS);
const timeoutMs = positiveInteger("MAMOJI_LOAD_TIMEOUT_MS", process.env.LOAD_TIMEOUT_MS);
const p95LimitMs = positiveInteger("MAMOJI_LOAD_P95_LIMIT_MS", process.env.LOAD_P95_LIMIT_MS);
const writeEvery = positiveInteger("MAMOJI_LOAD_WRITE_EVERY", process.env.LOAD_WRITE_EVERY);
const warmupRounds = positiveInteger("MAMOJI_LOAD_WARMUP_ROUNDS", process.env.LOAD_WARMUP_ROUNDS, { allowZero: true });
const maxErrorRate = Number(process.env.LOAD_MAX_ERROR_RATE_PERCENT);
if (!Number.isFinite(maxErrorRate) || maxErrorRate < 0 || maxErrorRate > 100) {
  throw new Error("MAMOJI_LOAD_MAX_ERROR_RATE_PERCENT must be between 0 and 100");
}

const samples = [];
const failures = [];
const pendingCleanup = new Set();
const runId = `${Date.now()}-${process.pid}`;
const categoryPrefix = `__load_smoke_${runId}_`;
let token = suppliedToken;
let ownsToken = false;
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

const percentile = (values, ratio) => {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * ratio) - 1)];
};

const scopedUrl = (path, companyId, params = {}) => {
  const url = new URL(apiBaseUrl + path);
  if (companyId) url.searchParams.set("companyId", String(companyId));
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") url.searchParams.set(key, String(value));
  }
  return url.toString();
};

async function request(label, url, options = {}, measured = true) {
  const startedAt = performance.now();
  try {
    const response = await fetch(url, {
      ...options,
      redirect: "error",
      signal: AbortSignal.timeout(timeoutMs),
    });
    const text = await response.text();
    const elapsedMs = performance.now() - startedAt;
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}${text ? `: ${text.slice(0, 160)}` : ""}`);
    }
    if (measured) samples.push({ label, elapsedMs, ok: true, status: response.status });
    return { response, text, json: text ? JSON.parse(text) : null };
  } catch (error) {
    const elapsedMs = performance.now() - startedAt;
    if (measured) {
      samples.push({ label, elapsedMs, ok: false, status: 0 });
      failures.push(`${label}: ${error.message || error}`);
    }
    throw error;
  }
}

const authHeaders = (json = false) => ({
  Authorization: `Bearer ${token}`,
  ...(json ? { "Content-Type": "application/json" } : {}),
});

async function acquireSession() {
  await request("health", healthUrl, {}, false);
  if (token) return;
  const login = await request("login", apiBaseUrl + "/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json", Origin: baseUrl },
    body: JSON.stringify({ email, password }),
  }, false);
  token = login.json?.token;
  if (!token) throw new Error("login did not return a token");
  ownsToken = true;
}

async function selectCompany() {
  const result = await request("companies", apiBaseUrl + "/enterprise/companies", { headers: authHeaders() }, false);
  if (!Array.isArray(result.json) || result.json.length === 0) throw new Error("no accessible company was returned");
  const requested = process.env.LOAD_COMPANY_ID ? Number(process.env.LOAD_COMPANY_ID) : null;
  const company = requested ? result.json.find((item) => Number(item.id) === requested) : result.json[0];
  if (!company) throw new Error(`company ${process.env.LOAD_COMPANY_ID} is not accessible to the load-test user`);
  return Number(company.id);
}

function readTargets(companyId) {
  return [
    { label: "auth.me", url: apiBaseUrl + "/auth/me" },
    { label: "stats.overview", url: scopedUrl("/stats/overview", companyId) },
    { label: "transactions.list", url: scopedUrl("/transactions", companyId, { page: 0, size: 20 }) },
    { label: "accounts.summary", url: scopedUrl("/accounts/summary", companyId) },
    { label: "budgets.active", url: scopedUrl("/budgets/active", companyId) },
    { label: "receipts.summary", url: scopedUrl("/receipts/summary", companyId) },
    { label: "enterprise.summary", url: scopedUrl("/enterprise/summary", companyId) },
  ];
}

async function writeCategoryPair(companyId, workerId, operationIndex) {
  const name = `${categoryPrefix}${workerId}_${operationIndex}`;
  const created = await request("category.create", apiBaseUrl + "/categories", {
    method: "POST",
    headers: authHeaders(true),
    body: JSON.stringify({ companyId, name, type: "expense", icon: "test", color: "#64748b" }),
  });
  const categoryId = Number(created.json?.id);
  if (!Number.isFinite(categoryId) || categoryId <= 0) throw new Error("category.create did not return an id");
  pendingCleanup.add(categoryId);
  await request("category.delete", scopedUrl(`/categories/${categoryId}`, companyId), {
    method: "DELETE",
    headers: authHeaders(),
  });
  pendingCleanup.delete(categoryId);
}

async function cleanup(companyId) {
  const cleanupFailures = [];
  if (mode === "mixed") {
    try {
      const categories = await request("category.cleanup.list", scopedUrl("/categories", companyId), {
        headers: authHeaders(),
      }, false);
      for (const category of Array.isArray(categories.json) ? categories.json : []) {
        const categoryId = Number(category.id);
        if (String(category.name || "").startsWith(categoryPrefix) && Number.isFinite(categoryId) && categoryId > 0) {
          pendingCleanup.add(categoryId);
        }
      }
    } catch (error) {
      cleanupFailures.push(`list: ${error.message || error}`);
    }
  }
  for (const categoryId of [...pendingCleanup]) {
    try {
      await request("category.cleanup", scopedUrl(`/categories/${categoryId}`, companyId), {
        method: "DELETE",
        headers: authHeaders(),
      }, false);
      pendingCleanup.delete(categoryId);
    } catch (error) {
      cleanupFailures.push(`${categoryId}: ${error.message || error}`);
    }
  }
  return cleanupFailures;
}

function printSummary(elapsedMs) {
  const successful = samples.filter((sample) => sample.ok);
  const total = samples.length;
  const errorCount = total - successful.length;
  const errorRate = total === 0 ? 100 : errorCount / total * 100;
  const latencies = successful.map((sample) => sample.elapsedMs);
  const groups = new Map();
  for (const sample of samples) {
    const group = groups.get(sample.label) || [];
    group.push(sample);
    groups.set(sample.label, group);
  }

  console.log(`Load smoke mode=${mode} company=${process.env.RESOLVED_COMPANY_ID} concurrency=${concurrency} operations=${operationCount}`);
  console.log(`HTTP samples=${total} success=${successful.length} errors=${errorCount} errorRate=${errorRate.toFixed(2)}% elapsed=${elapsedMs.toFixed(0)}ms`);
  console.log(`Latency success p50=${percentile(latencies, 0.50).toFixed(1)}ms p95=${percentile(latencies, 0.95).toFixed(1)}ms p99=${percentile(latencies, 0.99).toFixed(1)}ms`);
  for (const [label, group] of [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const ok = group.filter((sample) => sample.ok);
    const values = ok.map((sample) => sample.elapsedMs);
    console.log(`  ${label.padEnd(22)} count=${String(group.length).padStart(4)} errors=${String(group.length - ok.length).padStart(3)} p95=${percentile(values, 0.95).toFixed(1)}ms`);
  }
  if (failures.length > 0) {
    console.error("First failures:");
    for (const failure of failures.slice(0, 10)) console.error(`  - ${failure}`);
  }
  return { errorRate, p95: percentile(latencies, 0.95), total };
}

async function main() {
  let companyId;
  let cleanupFailures = [];
  try {
    await acquireSession();
    companyId = await selectCompany();
    process.env.RESOLVED_COMPANY_ID = String(companyId);
    const targets = readTargets(companyId);

    for (let round = 0; round < warmupRounds; round += 1) {
      await Promise.all(targets.map((target) => request(target.label, target.url, { headers: authHeaders() }, false)));
    }

    let cursor = 0;
    const startedAt = performance.now();
    const workers = Array.from({ length: concurrency }, (_, workerId) => (async () => {
      while (true) {
        const operationIndex = cursor;
        cursor += 1;
        if (operationIndex >= operationCount) return;
        try {
          if (mode === "mixed" && operationIndex % writeEvery === 0) {
            await writeCategoryPair(companyId, workerId, operationIndex);
          } else {
            const target = targets[operationIndex % targets.length];
            await request(target.label, target.url, { headers: authHeaders() });
          }
        } catch {
          // The request helper records workload failures; workers continue to collect a useful sample.
        }
      }
    })());
    await Promise.all(workers);
    const elapsedMs = performance.now() - startedAt;
    if (mode === "mixed") await sleep(1000);
    cleanupFailures = await cleanup(companyId);
    const summary = printSummary(elapsedMs);

    if (cleanupFailures.length > 0) {
      throw new Error(`temporary category cleanup failed: ${cleanupFailures.join(", ")}`);
    }
    if (summary.total === 0) throw new Error("load smoke produced no HTTP samples");
    if (summary.errorRate > maxErrorRate) {
      throw new Error(`error rate ${summary.errorRate.toFixed(2)}% exceeded ${maxErrorRate.toFixed(2)}%`);
    }
    if (summary.p95 > p95LimitMs) {
      throw new Error(`p95 ${summary.p95.toFixed(1)}ms exceeded ${p95LimitMs}ms`);
    }
  } finally {
    if (companyId && pendingCleanup.size > 0) {
      cleanupFailures = await cleanup(companyId);
      if (cleanupFailures.length > 0) console.error(`Cleanup still pending: ${cleanupFailures.join(", ")}`);
    }
    if (ownsToken && token) {
      await request("logout", apiBaseUrl + "/auth/logout", { method: "POST", headers: authHeaders() }, false).catch(() => {});
    }
  }
}

main()
  .then(() => console.log("Concurrency smoke passed"))
  .catch((error) => {
    console.error(error.message || error);
    process.exit(1);
  });
NODE
