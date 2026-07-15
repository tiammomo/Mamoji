#!/usr/bin/env bash
set -euo pipefail

if [[ "${MAMOJI_WORKFLOW_ALLOW_WRITES:-no}" != "yes" ]]; then
  echo "Workflow smoke creates and removes temporary accounting data; set MAMOJI_WORKFLOW_ALLOW_WRITES=yes to continue" >&2
  exit 1
fi

API_BASE_URL="${API_BASE_URL:-http://localhost:38080/api/v1}" \
HEALTH_URL="${HEALTH_URL:-http://localhost:38080/actuator/health}" \
APP_ORIGIN="${APP_ORIGIN:-http://localhost:33000}" \
WORKFLOW_TOKEN="${MAMOJI_WORKFLOW_TOKEN:-}" \
WORKFLOW_EMAIL="${MAMOJI_WORKFLOW_EMAIL:-test@mamoji.com}" \
WORKFLOW_PASSWORD="${MAMOJI_WORKFLOW_PASSWORD:-123456}" \
WORKFLOW_COMPANY_ID="${MAMOJI_WORKFLOW_COMPANY_ID:-}" \
WORKFLOW_TIMEOUT_MS="${MAMOJI_WORKFLOW_TIMEOUT_MS:-10000}" \
node <<'NODE'
const { performance } = require("node:perf_hooks");

const apiBaseUrl = process.env.API_BASE_URL.replace(/\/+$/, "");
const timeoutMs = Number.parseInt(process.env.WORKFLOW_TIMEOUT_MS, 10);
const runId = `${Date.now()}-${process.pid}`;
const marker = `__workflow_smoke_${runId}`;
const accountName = `${marker}_account`;
const categoryName = `${marker}_category`;
const originalNote = `${marker}_create`;
const updatedNote = `${marker}_updated`;
const initialBalance = 1000;
const originalAmount = 12.34;
const updatedAmount = 23.45;
const timings = [];

if (!Number.isInteger(timeoutMs) || timeoutMs < 1000) {
  throw new Error("MAMOJI_WORKFLOW_TIMEOUT_MS must be an integer of at least 1000");
}

let token = process.env.WORKFLOW_TOKEN;
let ownsToken = false;
let companyId = null;
let accountId = null;
let categoryId = null;
let transactionId = null;

const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};

const assertMoney = (actual, expected, label) => {
  const difference = Math.abs(Number(actual) - Number(expected));
  assert(Number.isFinite(difference) && difference < 0.005, `${label}: expected ${expected}, received ${actual}`);
};

async function request(label, pathOrUrl, options = {}, { measure = true, allowNotFound = false } = {}) {
  const url = pathOrUrl.startsWith("http") ? pathOrUrl : `${apiBaseUrl}${pathOrUrl}`;
  const startedAt = performance.now();
  const response = await fetch(url, {
    ...options,
    redirect: "error",
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await response.text();
  const elapsedMs = performance.now() - startedAt;
  if (measure) timings.push({ label, elapsedMs });
  if (allowNotFound && response.status === 404) return null;
  if (!response.ok) {
    throw new Error(`${label} failed with HTTP ${response.status}${text ? `: ${text.slice(0, 240)}` : ""}`);
  }
  return text ? JSON.parse(text) : null;
}

const authHeaders = (json = false) => ({
  Authorization: `Bearer ${token}`,
  ...(json ? { "Content-Type": "application/json" } : {}),
});

const scopedPath = (path, params = {}) => {
  const url = new URL(`${apiBaseUrl}${path}`);
  if (companyId) url.searchParams.set("companyId", String(companyId));
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") url.searchParams.set(key, String(value));
  }
  return url.toString();
};

async function acquireSession() {
  await request("health", process.env.HEALTH_URL, {}, { measure: false });
  if (token) return;
  const login = await request("login", "/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json", Origin: process.env.APP_ORIGIN },
    body: JSON.stringify({
      email: process.env.WORKFLOW_EMAIL,
      password: process.env.WORKFLOW_PASSWORD,
    }),
  });
  token = login?.token;
  assert(token, "login did not return a token");
  ownsToken = true;
}

async function resolveCompany() {
  const companies = await request("companies", "/enterprise/companies", { headers: authHeaders() });
  assert(Array.isArray(companies) && companies.length > 0, "no accessible company was returned");
  const requested = process.env.WORKFLOW_COMPANY_ID ? Number(process.env.WORKFLOW_COMPANY_ID) : null;
  const company = requested ? companies.find((item) => Number(item.id) === requested) : companies[0];
  assert(company, `company ${process.env.WORKFLOW_COMPANY_ID} is not accessible`);
  companyId = Number(company.id);
}

async function discoverTemporaryIds() {
  if (!accountId) {
    const accounts = await request("cleanup.accounts.list", scopedPath("/accounts"), { headers: authHeaders() }, { measure: false });
    accountId = Array.isArray(accounts) ? Number(accounts.find((item) => item.name === accountName)?.id || 0) || null : null;
  }
  if (!categoryId) {
    const categories = await request("cleanup.categories.list", scopedPath("/categories"), { headers: authHeaders() }, { measure: false });
    categoryId = Array.isArray(categories) ? Number(categories.find((item) => item.name === categoryName)?.id || 0) || null : null;
  }
  if (!transactionId) {
    const transactions = await request(
      "cleanup.transactions.list",
      scopedPath("/transactions", { page: 0, size: 100, keyword: marker }),
      { headers: authHeaders() },
      { measure: false },
    );
    const row = Array.isArray(transactions?.content)
      ? transactions.content.find((item) => String(item.note || "").includes(marker))
      : null;
    transactionId = Number(row?.id || 0) || null;
  }
}

async function cleanup() {
  if (!token || !companyId) return [];
  const failures = [];
  try {
    await discoverTemporaryIds();
  } catch (error) {
    failures.push(`discovery: ${error.message || error}`);
  }

  for (const [label, id, path] of [
    ["transaction", transactionId, "/transactions/"],
    ["account", accountId, "/accounts/"],
    ["category", categoryId, "/categories/"],
  ]) {
    if (!id) continue;
    try {
      await request(`cleanup.${label}`, scopedPath(`${path}${id}`), {
        method: "DELETE",
        headers: authHeaders(),
      }, { measure: false, allowNotFound: true });
      if (label === "transaction") transactionId = null;
      if (label === "account") accountId = null;
      if (label === "category") categoryId = null;
    } catch (error) {
      failures.push(`${label} ${id}: ${error.message || error}`);
    }
  }
  return failures;
}

async function runWorkflow() {
  await acquireSession();
  await resolveCompany();

  const account = await request("account.create", "/accounts", {
    method: "POST",
    headers: authHeaders(true),
    body: JSON.stringify({
      companyId,
      name: accountName,
      type: "bank",
      currency: "CNY",
      balance: initialBalance,
      availableBalance: initialBalance,
      includeInNetWorth: true,
      ownerName: "workflow-smoke",
      purpose: "temporary workflow verification",
    }),
  });
  accountId = Number(account?.id);
  assert(accountId > 0, "account.create did not return an id");

  const category = await request("category.create", "/categories", {
    method: "POST",
    headers: authHeaders(true),
    body: JSON.stringify({ companyId, name: categoryName, type: "expense", icon: "test", color: "#64748b" }),
  });
  categoryId = Number(category?.id);
  assert(categoryId > 0, "category.create did not return an id");

  const created = await request("transaction.create", "/transactions", {
    method: "POST",
    headers: authHeaders(true),
    body: JSON.stringify({
      companyId,
      type: 2,
      amount: originalAmount,
      categoryId,
      accountId,
      date: new Date().toISOString().slice(0, 10),
      note: originalNote,
    }),
  });
  transactionId = Number(created?.transaction?.id);
  assert(transactionId > 0, "transaction.create did not return an id");
  assert(Number(created.transaction.accountId) === accountId, "created transaction lost its account relation");
  assert(Number(created.transaction.categoryId) === categoryId, "created transaction lost its category relation");

  const afterCreate = await request("account.after-create", scopedPath(`/accounts/${accountId}`), { headers: authHeaders() });
  assertMoney(afterCreate.balance, initialBalance - originalAmount, "balance after transaction create");

  const updated = await request("transaction.update", scopedPath(`/transactions/${transactionId}`), {
    method: "PUT",
    headers: authHeaders(true),
    body: JSON.stringify({
      amount: updatedAmount,
      categoryId,
      accountId,
      date: new Date().toISOString().slice(0, 10),
      note: updatedNote,
    }),
  });
  assertMoney(updated.amount, updatedAmount, "updated transaction amount");
  assert(updated.note === updatedNote, "updated transaction note was not persisted");

  const afterUpdate = await request("account.after-update", scopedPath(`/accounts/${accountId}`), { headers: authHeaders() });
  assertMoney(afterUpdate.balance, initialBalance - updatedAmount, "balance after transaction update");

  const detail = await request("transaction.detail", scopedPath(`/transactions/${transactionId}`), { headers: authHeaders() });
  assert(detail.note === updatedNote, "transaction detail returned stale data");
  assert(Number(detail.accountId) === accountId && Number(detail.categoryId) === categoryId, "transaction detail relations are inconsistent");

  await request("transaction.delete", scopedPath(`/transactions/${transactionId}`), {
    method: "DELETE",
    headers: authHeaders(),
  });
  transactionId = null;

  const afterDelete = await request("account.after-delete", scopedPath(`/accounts/${accountId}`), { headers: authHeaders() });
  assertMoney(afterDelete.balance, initialBalance, "balance after transaction delete");

  await request("account.delete", scopedPath(`/accounts/${accountId}`), { method: "DELETE", headers: authHeaders() });
  accountId = null;
  await request("category.delete", scopedPath(`/categories/${categoryId}`), { method: "DELETE", headers: authHeaders() });
  categoryId = null;
}

(async () => {
  let cleanupFailures = [];
  try {
    await runWorkflow();
    console.log(`Workflow smoke passed for company=${companyId}`);
    for (const timing of timings) {
      console.log(`  ${timing.label.padEnd(27)} ${timing.elapsedMs.toFixed(1).padStart(8)} ms`);
    }
  } finally {
    cleanupFailures = await cleanup();
    if (ownsToken && token) {
      await request("logout", "/auth/logout", { method: "POST", headers: authHeaders() }, { measure: false }).catch(() => {});
    }
    if (cleanupFailures.length > 0) {
      throw new Error(`temporary workflow cleanup failed: ${cleanupFailures.join("; ")}`);
    }
  }
})().catch((error) => {
  console.error(error.message || error);
  process.exit(1);
});
NODE
