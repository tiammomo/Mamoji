import client from "./client";
import type { Account, AccountReconciliation, CreateAccountDTO, UpdateAccountDTO, AccountSummary } from "@/lib/types";

export const accountApi = {
  list: () => client.get<Account[]>("/accounts"),
  get: (id: number) => client.get<Account>(`/accounts/${id}`),
  create: (data: CreateAccountDTO) => client.post<Account>("/accounts", data),
  update: (id: number, data: UpdateAccountDTO) => client.put<Account>(`/accounts/${id}`, data),
  delete: (id: number) => client.delete(`/accounts/${id}`),
  summary: () => client.get<AccountSummary>("/accounts/summary"),
  reconciliations: (id: number) => client.get<AccountReconciliation[]>(`/accounts/${id}/reconciliations`),
  reconcile: (id: number, data: { statementDate: string; statementBalance: number; note?: string }) =>
    client.post<AccountReconciliation>(`/accounts/${id}/reconciliations`, data),
};
