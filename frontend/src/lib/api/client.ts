import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from "axios";

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

type SessionAwareRequestConfig = InternalAxiosRequestConfig & {
  __mamojiSessionToken?: string | null;
};

const storedToken = () => {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("token");
};

const client = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:38080/api/v1",
  timeout: 20000,
});

let sessionExpiryRedirectStarted = false;

client.interceptors.request.use(
  (config) => {
    if (typeof window !== "undefined") {
      const token = storedToken();
      (config as SessionAwareRequestConfig).__mamojiSessionToken = token;
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
  },
  undefined,
  { synchronous: true }
);

client.interceptors.response.use(
  (res) => res,
  (error) => {
    if (error.response?.status === 401) {
      if (typeof window !== "undefined") {
        const requestToken = (error.config as SessionAwareRequestConfig | undefined)?.__mamojiSessionToken;
        // A request from the previous session may finish after a new login. Its 401
        // must not clear the newly established session.
        if (requestToken !== undefined && requestToken !== storedToken()) {
          return Promise.reject(error);
        }
        localStorage.removeItem("token");
        localStorage.removeItem("tokenExpiresAt");
        localStorage.removeItem("activeCompanyId");
        localStorage.removeItem("activeSubjectType");
        if (window.location.pathname !== "/login" && !sessionExpiryRedirectStarted) {
          sessionExpiryRedirectStarted = true;
          const next = `${window.location.pathname}${window.location.search}`;
          window.location.replace(`/login?reason=session_expired&next=${encodeURIComponent(next)}`);
        }
      }
    }
    return Promise.reject(error);
  }
);

const pendingGetRequests = new Map<string, Promise<unknown>>();
const originalGet = client.get.bind(client);

const canCoalesceGet = (config?: AxiosRequestConfig) => {
  const responseType = config?.responseType;
  return !config?.signal
    && !config?.cancelToken
    && !config?.adapter
    && !config?.onDownloadProgress
    && (!responseType || responseType === "json" || responseType === "text");
};

const getRequestKey = (url: string, config?: AxiosRequestConfig) => {
  const companyId = activeCompanyId();
  const params = companyId && isSubjectScopedRequest(url)
    ? { ...(config?.params || {}), companyId }
    : config?.params;
  const uri = client.getUri({ ...config, url, params });
  return JSON.stringify({
    uri,
    token: storedToken(),
    timeout: config?.timeout ?? client.defaults.timeout,
    responseType: config?.responseType ?? "json",
    withCredentials: config?.withCredentials ?? client.defaults.withCredentials,
    headers: config?.headers ?? null,
  });
};

// React strict mode and shared shell widgets can ask for the exact same resource
// concurrently. Coalesce only cancellable-safe JSON/text GETs and never cache the
// response beyond the lifetime of the in-flight request.
client.get = ((url: string, config?: AxiosRequestConfig) => {
  if (!canCoalesceGet(config)) {
    return originalGet(url, config);
  }

  const key = getRequestKey(url, config);
  const pending = pendingGetRequests.get(key);
  if (pending) {
    return pending;
  }

  const request = originalGet(url, config);
  pendingGetRequests.set(key, request);
  const release = () => {
    if (pendingGetRequests.get(key) === request) {
      pendingGetRequests.delete(key);
    }
  };
  void request.then(release, release);
  return request;
}) as typeof client.get;

export default client;
