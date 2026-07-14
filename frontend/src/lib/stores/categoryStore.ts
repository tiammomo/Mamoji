import { create } from "zustand";
import { categoryApi } from "@/lib/api/categories";
import type { Category } from "@/lib/types";

interface CategoryState {
  categories: Category[];
  loaded: boolean;
  scopeId: number | null;
  fetchCategories: () => Promise<void>;
  clearCategories: () => void;
  getByType: (type: "income" | "expense") => Category[];
}

const activeScopeId = () => {
  if (typeof window === "undefined") return null;
  const value = Number(localStorage.getItem("activeCompanyId"));
  return Number.isFinite(value) && value > 0 ? value : null;
};

export const useCategoryStore = create<CategoryState>((set, get) => ({
  categories: [],
  loaded: false,
  scopeId: null,

  fetchCategories: async () => {
    const scopeId = activeScopeId();
    if (get().loaded && get().scopeId === scopeId) return;
    set({ categories: [], loaded: false, scopeId });
    try {
      const res = await categoryApi.list();
      if (activeScopeId() === scopeId) {
        set({ categories: res.data, loaded: true, scopeId });
      }
    } catch {
      if (activeScopeId() === scopeId) {
        set({ categories: [], loaded: false, scopeId });
      }
    }
  },

  clearCategories: () => set({ categories: [], loaded: false, scopeId: null }),

  getByType: (type) => get().categories.filter((c) => c.type === type),
}));
