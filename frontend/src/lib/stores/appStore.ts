import { create } from "zustand";

interface AppState {
  sidebarCollapsed: boolean;
  theme: "light" | "dark";
  locale: "zh" | "en";
  activeCompanyId: number | null;
  toggleSidebar: () => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setTheme: (theme: "light" | "dark") => void;
  setLocale: (locale: "zh" | "en") => void;
  setActiveCompanyId: (companyId: number | null) => void;
}

const readStoredCompanyId = () => {
  if (typeof window === "undefined") return null;
  const value = Number(localStorage.getItem("activeCompanyId"));
  return Number.isFinite(value) && value > 0 ? value : null;
};

export const useAppStore = create<AppState>((set) => ({
  sidebarCollapsed: false,
  theme: "light",
  locale: "zh",
  activeCompanyId: readStoredCompanyId(),

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
  setActiveCompanyId: (companyId) => {
    if (typeof window !== "undefined") {
      if (companyId) {
        localStorage.setItem("activeCompanyId", String(companyId));
      } else {
        localStorage.removeItem("activeCompanyId");
      }
    }
    set({ activeCompanyId: companyId });
  },
}));
