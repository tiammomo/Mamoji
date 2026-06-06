import client from "./client";
import type { Budget, CreateBudgetDTO, UpdateBudgetDTO, BudgetQuery } from "@/lib/types";
import type { PaginatedResponse } from "./transactions";

export const budgetApi = {
  list: (params?: BudgetQuery) =>
    client.get<PaginatedResponse<Budget>>("/budgets", { params }),
  active: () => client.get<Budget[]>("/budgets/active"),
  get: (id: number) => client.get<Budget>(`/budgets/${id}`),
  create: (data: CreateBudgetDTO) => client.post<Budget>("/budgets", data),
  update: (id: number, data: UpdateBudgetDTO) => client.put<Budget>(`/budgets/${id}`, data),
  delete: (id: number) => client.delete(`/budgets/${id}`),
};
