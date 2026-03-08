import { api, buildQueryString } from "../api.client";
import type {
  Account,
  Budget,
  Category,
  CategoryListResponse,
  Ledger,
  LedgerMember,
  Transaction,
  TransactionListResponse,
} from "../api.types";

export const accountApi = {
  getAccounts: () => api.get<Account[]>("/accounts"),
  getAccount: (id: number) => api.get<Account>(`/accounts/${id}`),
  createAccount: (data: Partial<Account>) => api.post<Account>("/accounts", data),
  updateAccount: (id: number, data: Partial<Account>) => api.put<Account>(`/accounts/${id}`, data),
  deleteAccount: (id: number) => api.delete<void>(`/accounts/${id}`),
};

export const transactionApi = {
  getTransactions: (params?: {
    page?: number;
    pageSize?: number;
    type?: number;
    types?: number[];
    accountId?: number;
    categoryId?: number;
    startDate?: string;
    endDate?: string;
    keyword?: string;
  }) => {
    const queryString = buildQueryString({
      page: params?.page,
      pageSize: params?.pageSize,
      type: params?.type,
      types: params?.types?.join(","),
      accountId: params?.accountId,
      categoryId: params?.categoryId,
      startDate: params?.startDate,
      endDate: params?.endDate,
      keyword: params?.keyword,
    });
    return api.get<TransactionListResponse>(`/transactions${queryString}`);
  },
  getTransaction: (id: number) => api.get<Transaction>(`/transactions/${id}`),
  createTransaction: (data: Partial<Transaction>) => api.post<Transaction>("/transactions", data),
  updateTransaction: (id: number, data: Partial<Transaction>) => api.put<Transaction>(`/transactions/${id}`, data),
  deleteTransaction: (id: number) => api.delete<void>(`/transactions/${id}`),
};

export const categoryApi = {
  getCategories: () => api.get<CategoryListResponse>("/categories"),
  createCategory: (data: Partial<Category>) => api.post<Category>("/categories", data),
  updateCategory: (id: number, data: Partial<Category>) => api.put<Category>(`/categories/${id}`, data),
  deleteCategory: (id: number) => api.delete<void>(`/categories/${id}`),
};

export const budgetApi = {
  getBudgets: () => api.get<Budget[]>("/budgets"),
  getBudget: (id: number) => api.get<Budget>(`/budgets/${id}`),
  createBudget: (data: Partial<Budget>) => api.post<Budget>("/budgets", data),
  updateBudget: (id: number, data: Partial<Budget>) => api.put<Budget>(`/budgets/${id}`, data),
  deleteBudget: (id: number) => api.delete<void>(`/budgets/${id}`),
};

export const ledgerApi = {
  getLedgers: () => api.get<Ledger[]>("/ledgers"),
  getLedger: (id: number) => api.get<Ledger>(`/ledgers/${id}`),
  createLedger: (data: Partial<Ledger>) => api.post<Ledger>("/ledgers", data),
  updateLedger: (id: number, data: Partial<Ledger>) => api.put<Ledger>(`/ledgers/${id}`, data),
  deleteLedger: (id: number) => api.delete<void>(`/ledgers/${id}`),
  getMembers: (id: number) => api.get<LedgerMember[]>(`/ledgers/${id}/members`),
  addMember: (id: number, email: string, role: string) =>
    api.post<LedgerMember[]>(`/ledgers/${id}/members`, { email, role }),
  removeMember: (id: number, userId: number) => api.delete<void>(`/ledgers/${id}/members/${userId}`),
};
