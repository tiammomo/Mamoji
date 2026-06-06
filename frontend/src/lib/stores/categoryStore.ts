import { create } from "zustand";
import { categoryApi } from "@/lib/api/categories";
import type { Category } from "@/lib/types";

interface CategoryState {
  categories: Category[];
  loaded: boolean;
  fetchCategories: () => Promise<void>;
  getByType: (type: "income" | "expense") => Category[];
}

export const useCategoryStore = create<CategoryState>((set, get) => ({
  categories: [],
  loaded: false,

  fetchCategories: async () => {
    if (get().loaded) return;
    try {
      const res = await categoryApi.list();
      set({ categories: res.data, loaded: true });
    } catch {
      // silent fail
    }
  },

  getByType: (type) => get().categories.filter((c) => c.type === type),
}));
