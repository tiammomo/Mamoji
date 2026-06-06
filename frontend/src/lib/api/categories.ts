import client from "./client";
import type { Category, CreateCategoryDTO, UpdateCategoryDTO } from "@/lib/types";

export const categoryApi = {
  list: (type?: "income" | "expense") =>
    client.get<Category[]>("/categories", { params: type ? { type } : {} }),
  create: (data: CreateCategoryDTO) => client.post<Category>("/categories", data),
  update: (id: number, data: UpdateCategoryDTO) => client.put<Category>(`/categories/${id}`, data),
  delete: (id: number) => client.delete(`/categories/${id}`),
};
