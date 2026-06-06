import { create } from "zustand";
import { authApi } from "@/lib/api/auth";
import type { User, LoginDTO, RegisterDTO } from "@/lib/types";

interface AuthState {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  loading: boolean;
  login: (data: LoginDTO) => Promise<void>;
  register: (data: RegisterDTO) => Promise<void>;
  logout: () => void;
  fetchCurrentUser: () => Promise<void>;
  updateUser: (user: User) => void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  token: typeof window !== "undefined" ? localStorage.getItem("token") : null,
  isAuthenticated: false,
  loading: true,

  login: async (data) => {
    const res = await authApi.login(data);
    const { token, user } = res.data;
    localStorage.setItem("token", token);
    set({ user, token, isAuthenticated: true, loading: false });
  },

  register: async (data) => {
    const res = await authApi.register(data);
    const { token, user } = res.data;
    localStorage.setItem("token", token);
    set({ user, token, isAuthenticated: true, loading: false });
  },

  logout: () => {
    localStorage.removeItem("token");
    set({ user: null, token: null, isAuthenticated: false, loading: false });
  },

  fetchCurrentUser: async () => {
    const token = get().token;
    if (!token) {
      set({ loading: false });
      return;
    }
    try {
      const res = await authApi.me();
      set({ user: res.data, isAuthenticated: true, loading: false });
    } catch {
      localStorage.removeItem("token");
      set({ user: null, token: null, isAuthenticated: false, loading: false });
    }
  },

  updateUser: (user) => set({ user }),
}));
