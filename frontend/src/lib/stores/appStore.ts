import { create } from "zustand";

interface AppState {
  sidebarCollapsed: boolean;
  theme: "light" | "dark";
  locale: "zh" | "en";
  toggleSidebar: () => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setTheme: (theme: "light" | "dark") => void;
  setLocale: (locale: "zh" | "en") => void;
}

export const useAppStore = create<AppState>((set) => ({
  sidebarCollapsed: false,
  theme: "light",
  locale: "zh",

  toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
  setSidebarCollapsed: (collapsed) => set({ sidebarCollapsed: collapsed }),
  setTheme: (theme) => {
    document.documentElement.setAttribute("data-theme", theme);
    localStorage.setItem("theme", theme);
    set({ theme });
  },
  setLocale: (locale) => {
    document.cookie = `NEXT_LOCALE=${locale};path=/;max-age=31536000`;
    set({ locale });
  },
}));
