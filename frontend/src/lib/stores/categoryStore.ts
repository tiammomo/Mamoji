import { create } from "zustand";
import { categoryApi } from "@/lib/api/categories";
import type { Category } from "@/lib/types";

let categoryRevision = 0;
const categoryRequests = new Map<number | null, Promise<void>>();

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

  fetchCategories: () => {
    const scopeId = activeScopeId();
    if (get().loaded && get().scopeId === scopeId) return Promise.resolve();

    const pending = categoryRequests.get(scopeId);
    if (pending) return pending;

    const revision = categoryRevision;
    if (get().scopeId !== scopeId) {
      set({ categories: [], loaded: false, scopeId });
    }

    const request = (async () => {
      try {
        const res = await categoryApi.list();
        if (revision === categoryRevision && activeScopeId() === scopeId) {
          set({ categories: res.data, loaded: true, scopeId });
        }
      } catch {
        if (revision === categoryRevision && activeScopeId() === scopeId) {
          set({ categories: [], loaded: false, scopeId });
        }
      }
    })();
    categoryRequests.set(scopeId, request);
    const release = () => {
      if (categoryRequests.get(scopeId) === request) {
        categoryRequests.delete(scopeId);
      }
    };
    void request.then(release, release);
    return request;
  },

  clearCategories: () => {
    categoryRevision += 1;
    categoryRequests.clear();
    set({ categories: [], loaded: false, scopeId: null });
  },

  getByType: (type) => get().categories.filter((c) => c.type === type),
}));
