import axios from "axios";

const SUBJECT_SCOPED_PATHS = [
  "/accounts",
  "/categories",
  "/budgets",
  "/transactions",
  "/stats",
  "/ledgers",
  "/recurring",
  "/approvals",
  "/search",
];

const activeCompanyId = () => {
  if (typeof window === "undefined") return undefined;
  const value = Number(localStorage.getItem("activeCompanyId"));
  return Number.isFinite(value) && value > 0 ? value : undefined;
};

const isSubjectScopedRequest = (url?: string) =>
  Boolean(url && SUBJECT_SCOPED_PATHS.some((path) => url === path || url.startsWith(`${path}/`)));

const isPlainObject = (value: unknown): value is Record<string, unknown> =>
  Boolean(
    value
    && typeof value === "object"
    && !Array.isArray(value)
    && !(typeof FormData !== "undefined" && value instanceof FormData)
  );

const client = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:38080/api/v1",
  timeout: 20000,
});

client.interceptors.request.use((config) => {
  if (typeof window !== "undefined") {
    const token = localStorage.getItem("token");
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    const companyId = activeCompanyId();
    if (companyId && isSubjectScopedRequest(config.url)) {
      config.params = { ...(config.params || {}), companyId };
      if (isPlainObject(config.data)) {
        config.data = { ...config.data, companyId };
      }
    }
  }
  return config;
});

client.interceptors.response.use(
  (res) => res,
  (error) => {
    if (error.response?.status === 401) {
      if (typeof window !== "undefined") {
        localStorage.removeItem("token");
        localStorage.removeItem("tokenExpiresAt");
        localStorage.removeItem("activeCompanyId");
        localStorage.removeItem("activeSubjectType");
        if (window.location.pathname !== "/login") {
          const next = `${window.location.pathname}${window.location.search}`;
          window.location.replace(`/login?reason=session_expired&next=${encodeURIComponent(next)}`);
        }
      }
    }
    return Promise.reject(error);
  }
);

export default client;
