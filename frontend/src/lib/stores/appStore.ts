import { create } from "zustand";

type Theme = "light" | "dark";
type Locale = "zh" | "en";

interface AppState {
  sidebarCollapsed: boolean;
  theme: Theme;
  locale: Locale;
  activeCompanyId: number | null;
  toggleSidebar: () => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setTheme: (theme: Theme) => void;
  setLocale: (locale: Locale) => void;
  setActiveCompanyId: (companyId: number | null) => void;
  hydratePreferences: (serverLocale?: Locale) => void;
}

const isTheme = (value: string | null): value is Theme => value === "light" || value === "dark";
const isLocale = (value: string | null): value is Locale => value === "zh" || value === "en";

const applyTheme = (theme: Theme) => {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  root.setAttribute("data-theme", theme);
  root.classList.toggle("dark", theme === "dark");
  root.style.colorScheme = theme;
};

const readStoredTheme = (): Theme => {
  if (typeof window === "undefined") return "light";
  const value = localStorage.getItem("theme");
  return isTheme(value) ? value : "light";
};

const readStoredLocale = (): Locale => {
  if (typeof document === "undefined") return "zh";
  const match = document.cookie.match(/(?:^|; )NEXT_LOCALE=(zh|en)(?:;|$)/);
  const value = match?.[1] ?? null;
  return isLocale(value) ? value : "zh";
};

const readStoredCompanyId = () => {
  if (typeof window === "undefined") return null;
  const value = Number(localStorage.getItem("activeCompanyId"));
  return Number.isFinite(value) && value > 0 ? value : null;
};

export const useAppStore = create<AppState>((set) => ({
  sidebarCollapsed: false,
  theme: readStoredTheme(),
  locale: readStoredLocale(),
  activeCompanyId: readStoredCompanyId(),

  toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
  setSidebarCollapsed: (collapsed) => set({ sidebarCollapsed: collapsed }),
  setTheme: (theme) => {
    applyTheme(theme);
    if (typeof window !== "undefined") {
      localStorage.setItem("theme", theme);
    }
    set({ theme });
  },
  setLocale: (locale) => {
    if (typeof document !== "undefined") {
      document.cookie = `NEXT_LOCALE=${locale};path=/;max-age=31536000;samesite=lax`;
      document.documentElement.lang = locale;
    }
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
  hydratePreferences: (serverLocale) => {
    const theme = readStoredTheme();
    const locale = serverLocale ?? readStoredLocale();
    applyTheme(theme);
    set({ theme, locale });
  },
}));
