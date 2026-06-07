import { create } from "zustand";
import { authApi } from "@/lib/api/auth";
import type { User, LoginDTO, RegisterDTO } from "@/lib/types";

const TOKEN_KEY = "token";
const TOKEN_EXPIRES_AT_KEY = "tokenExpiresAt";

const isBrowser = () => typeof window !== "undefined";

const clearStoredSession = () => {
  if (!isBrowser()) {
    return;
  }
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRES_AT_KEY);
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
  token: string | null;
  tokenExpiresAt: string | null;
  isAuthenticated: boolean;
  loading: boolean;
  login: (data: LoginDTO) => Promise<void>;
  register: (data: RegisterDTO) => Promise<void>;
  logout: () => Promise<void>;
  fetchCurrentUser: () => Promise<void>;
  updateUser: (user: User) => void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  token: storedSession.token,
  tokenExpiresAt: storedSession.tokenExpiresAt,
  isAuthenticated: false,
  loading: true,

  login: async (data) => {
    const res = await authApi.login(data);
    const { token, tokenExpiresAt, user } = res.data;
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(TOKEN_EXPIRES_AT_KEY, tokenExpiresAt);
    set({ user, token, tokenExpiresAt, isAuthenticated: true, loading: false });
  },

  register: async (data) => {
    const res = await authApi.register(data);
    const { token, tokenExpiresAt, user } = res.data;
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(TOKEN_EXPIRES_AT_KEY, tokenExpiresAt);
    set({ user, token, tokenExpiresAt, isAuthenticated: true, loading: false });
  },

  logout: async () => {
    const token = get().token;
    try {
      if (token) {
        await authApi.logout();
      }
    } finally {
      clearStoredSession();
      set({ user: null, token: null, tokenExpiresAt: null, isAuthenticated: false, loading: false });
    }
  },

  fetchCurrentUser: async () => {
    const { token, tokenExpiresAt } = get();
    if (!token || isExpired(tokenExpiresAt)) {
      clearStoredSession();
      set({ loading: false });
      return;
    }
    try {
      const res = await authApi.me();
      set({ user: res.data, isAuthenticated: true, loading: false });
    } catch {
      clearStoredSession();
      set({ user: null, token: null, tokenExpiresAt: null, isAuthenticated: false, loading: false });
    }
  },

  updateUser: (user) => set({ user }),
}));
