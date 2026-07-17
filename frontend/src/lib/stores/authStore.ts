import { create } from "zustand";
import { authApi } from "@/lib/api/auth";
import type { AccessContext, User, LoginDTO, RegisterDTO } from "@/lib/types";
import { useAppStore } from "./appStore";
import { useCategoryStore } from "./categoryStore";

const TOKEN_KEY = "token";
const TOKEN_EXPIRES_AT_KEY = "tokenExpiresAt";

let authRevision = 0;
let currentUserRequest: { token: string; promise: Promise<void> } | null = null;
let logoutRequest: { token: string | null; promise: Promise<void> } | null = null;

const isBrowser = () => typeof window !== "undefined";

const clearStoredSession = () => {
  if (!isBrowser()) {
    return;
  }
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRES_AT_KEY);
};

const resetUserContext = () => {
  if (isBrowser()) {
    localStorage.removeItem("activeCompanyId");
    localStorage.removeItem("activeSubjectType");
  }
  useAppStore.setState({ activeCompanyId: null, activeSubjectType: "company" });
  useCategoryStore.getState().clearCategories();
};

const isExpired = (expiresAt: string | null) => {
  if (!expiresAt) {
    return false;
  }
  const expiresAtMs = Date.parse(expiresAt);
  return Number.isNaN(expiresAtMs) || expiresAtMs <= Date.now();
};

const readStoredSession = () => {
  if (!isBrowser()) {
    return { token: null, tokenExpiresAt: null };
  }
  const token = localStorage.getItem(TOKEN_KEY);
  const tokenExpiresAt = localStorage.getItem(TOKEN_EXPIRES_AT_KEY);
  if (!token || isExpired(tokenExpiresAt)) {
    clearStoredSession();
    return { token: null, tokenExpiresAt: null };
  }
  return { token, tokenExpiresAt };
};

const storedSession = readStoredSession();

interface AuthState {
  user: User | null;
  accessContext: AccessContext | null;
  token: string | null;
  tokenExpiresAt: string | null;
  isAuthenticated: boolean;
  loading: boolean;
  login: (data: LoginDTO) => Promise<void>;
  register: (data: RegisterDTO) => Promise<void>;
  logout: () => Promise<void>;
  fetchCurrentUser: () => Promise<void>;
  refreshAccessContext: (companyId?: number) => Promise<void>;
  updateUser: (user: User) => void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  accessContext: null,
  token: storedSession.token,
  tokenExpiresAt: storedSession.tokenExpiresAt,
  isAuthenticated: false,
  loading: true,

  login: async (data) => {
    const revision = ++authRevision;
    try {
      const res = await authApi.login(data);
      if (revision !== authRevision) return;
      const { token, tokenExpiresAt, user } = res.data;
      resetUserContext();
      localStorage.setItem(TOKEN_KEY, token);
      localStorage.setItem(TOKEN_EXPIRES_AT_KEY, tokenExpiresAt);
      set({ user, token, tokenExpiresAt, isAuthenticated: true, loading: false });
      try {
        const context = await authApi.accessContext();
        if (revision === authRevision) set({ accessContext: context.data });
      } catch {
        // Authentication remains valid when optional platform context is temporarily unavailable.
      }
    } catch (error) {
      if (revision === authRevision) {
        set({ loading: false });
      }
      throw error;
    }
  },

  register: async (data) => {
    const revision = ++authRevision;
    try {
      const res = await authApi.register(data);
      if (revision !== authRevision) return;
      const { token, tokenExpiresAt, user } = res.data;
      resetUserContext();
      localStorage.setItem(TOKEN_KEY, token);
      localStorage.setItem(TOKEN_EXPIRES_AT_KEY, tokenExpiresAt);
      set({ user, token, tokenExpiresAt, isAuthenticated: true, loading: false });
      try {
        const context = await authApi.accessContext();
        if (revision === authRevision) set({ accessContext: context.data });
      } catch {
        // Authentication remains valid when optional platform context is temporarily unavailable.
      }
    } catch (error) {
      if (revision === authRevision) {
        set({ loading: false });
      }
      throw error;
    }
  },

  logout: () => {
    const token = get().token;
    if (logoutRequest?.token === token) {
      return logoutRequest.promise;
    }

    const revision = ++authRevision;
    const promise = (async () => {
      try {
        if (token) {
          await authApi.logout();
        }
      } catch {
        // Local logout must still succeed when the server is unavailable or the
        // session has already expired.
      } finally {
        if (revision === authRevision) {
          clearStoredSession();
          resetUserContext();
          set({ user: null, accessContext: null, token: null, tokenExpiresAt: null, isAuthenticated: false, loading: false });
        }
      }
    })();
    logoutRequest = { token, promise };
    const release = () => {
      if (logoutRequest?.promise === promise) {
        logoutRequest = null;
      }
    };
    void promise.then(release, release);
    return promise;
  },

  fetchCurrentUser: () => {
    const { token, tokenExpiresAt } = get();
    if (!token || isExpired(tokenExpiresAt)) {
      authRevision += 1;
      clearStoredSession();
      resetUserContext();
      set({ user: null, accessContext: null, token: null, tokenExpiresAt: null, isAuthenticated: false, loading: false });
      return Promise.resolve();
    }

    if (currentUserRequest?.token === token) {
      return currentUserRequest.promise;
    }

    const revision = authRevision;
    const promise = (async () => {
      try {
        const [res, context] = await Promise.all([authApi.me(), authApi.accessContext()]);
        if (revision === authRevision && get().token === token) {
          set({ user: res.data, accessContext: context.data, isAuthenticated: true, loading: false });
        }
      } catch {
        if (revision === authRevision && get().token === token) {
          authRevision += 1;
          clearStoredSession();
          resetUserContext();
          set({ user: null, accessContext: null, token: null, tokenExpiresAt: null, isAuthenticated: false, loading: false });
        }
      }
    })();
    currentUserRequest = { token, promise };
    const release = () => {
      if (currentUserRequest?.promise === promise) {
        currentUserRequest = null;
      }
    };
    void promise.then(release, release);
    return promise;
  },

  refreshAccessContext: async (companyId) => {
    const res = await authApi.accessContext(companyId ? { companyId } : undefined);
    set({ accessContext: res.data });
  },

  updateUser: (user) => set((state) => ({
    user,
    accessContext: state.accessContext ? { ...state.accessContext, actor: user } : null,
  })),
}));
