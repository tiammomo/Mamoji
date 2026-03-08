"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  accountApi,
  transactionApi,
  categoryApi,
  budgetApi,
  ledgerApi,
  statsApi,
  Account,
  Transaction,
  TransactionListResponse,
  CategoryListResponse,
  Category,
  Budget,
  Ledger,
  LedgerMember,
  StatsOverview,
  StatsTrend,
  StatsCategory,
} from "./api";

// ============ Account Hooks ============
export function useAccounts() {
  return useQuery({
    queryKey: ["accounts"],
    queryFn: () => accountApi.getAccounts(),
  });
}

export function useAccount(id: number) {
  return useQuery<Account>({
    queryKey: ["accounts", id],
    queryFn: () => accountApi.getAccount(id),
    enabled: !!id,
  });
}

export function useCreateAccount() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Partial<Account>) => accountApi.createAccount(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["accounts"] });
    },
  });
}

export function useUpdateAccount() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: Partial<Account> }) =>
      accountApi.updateAccount(id, data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ["accounts"] });
      queryClient.invalidateQueries({ queryKey: ["accounts", id] });
    },
  });
}

export function useDeleteAccount() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => accountApi.deleteAccount(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["accounts"] });
    },
  });
}

// ============ Transaction Hooks ============
export function useTransactions(params?: {
  page?: number;
  pageSize?: number;
  type?: number;
  types?: number[];
  accountId?: number;
  categoryId?: number;
  startDate?: string;
  endDate?: string;
  keyword?: string;
  enabled?: boolean;
}) {
  return useQuery({
    queryKey: ["transactions", params],
    queryFn: () => transactionApi.getTransactions(params),
    enabled: params?.enabled ?? true,
  });
}

export function useCreateTransaction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Partial<Transaction>) => transactionApi.createTransaction(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["transactions"] });
    },
  });
}

export function useUpdateTransaction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: Partial<Transaction> }) =>
      transactionApi.updateTransaction(id, data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ["transactions"] });
      queryClient.invalidateQueries({ queryKey: ["transactions", id] });
    },
  });
}

export function useDeleteTransaction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => transactionApi.deleteTransaction(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["transactions"] });
    },
  });
}

// ============ Category Hooks ============
export function useCategories() {
  return useQuery({
    queryKey: ["categories"],
    queryFn: () => categoryApi.getCategories(),
    staleTime: 1000 * 60 * 5, // 5 minutes
  });
}

export function useCreateCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Partial<Category>) => categoryApi.createCategory(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["categories"] });
    },
  });
}

export function useUpdateCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: Partial<Category> }) =>
      categoryApi.updateCategory(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["categories"] });
    },
  });
}

export function useDeleteCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => categoryApi.deleteCategory(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["categories"] });
    },
  });
}

// ============ Budget Hooks ============
export function useBudgets() {
  return useQuery<Budget[]>({
    queryKey: ["budgets"],
    queryFn: () => budgetApi.getBudgets(),
  });
}

export function useBudget(id: number) {
  return useQuery<Budget>({
    queryKey: ["budgets", id],
    queryFn: () => budgetApi.getBudget(id),
    enabled: !!id,
  });
}

export function useCreateBudget() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Partial<Budget>) => budgetApi.createBudget(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["budgets"] });
    },
  });
}

export function useUpdateBudget() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: Partial<Budget> }) =>
      budgetApi.updateBudget(id, data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ["budgets"] });
      queryClient.invalidateQueries({ queryKey: ["budgets", id] });
    },
  });
}

export function useDeleteBudget() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => budgetApi.deleteBudget(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["budgets"] });
    },
  });
}

// ============ Ledger Hooks ============
export function useLedgers() {
  return useQuery<Ledger[]>({
    queryKey: ["ledgers"],
    queryFn: () => ledgerApi.getLedgers(),
  });
}

export function useLedger(id: number) {
  return useQuery<Ledger>({
    queryKey: ["ledgers", id],
    queryFn: () => ledgerApi.getLedger(id),
    enabled: !!id,
  });
}

export function useLedgerMembers(id: number) {
  return useQuery<LedgerMember[]>({
    queryKey: ["ledgers", id, "members"],
    queryFn: () => ledgerApi.getMembers(id),
    enabled: !!id,
  });
}

export function useCreateLedger() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Partial<Ledger>) => ledgerApi.createLedger(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["ledgers"] });
    },
  });
}

export function useUpdateLedger() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: Partial<Ledger> }) =>
      ledgerApi.updateLedger(id, data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ["ledgers"] });
      queryClient.invalidateQueries({ queryKey: ["ledgers", id] });
    },
  });
}

export function useDeleteLedger() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => ledgerApi.deleteLedger(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["ledgers"] });
    },
  });
}

export function useAddLedgerMember() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ ledgerId, email, role }: { ledgerId: number; email: string; role: string }) =>
      ledgerApi.addMember(ledgerId, email, role),
    onSuccess: (_, { ledgerId }) => {
      queryClient.invalidateQueries({ queryKey: ["ledgers", ledgerId, "members"] });
    },
  });
}

// ============ Stats Hooks ============
export function useStatsOverview(month?: string) {
  return useQuery<StatsOverview>({
    queryKey: ["stats", "overview", month],
    queryFn: () => statsApi.getOverview(month),
  });
}

export function useStatsTrend(startDate?: string, endDate?: string) {
  return useQuery<StatsTrend[]>({
    queryKey: ["stats", "trend", startDate, endDate],
    queryFn: () => statsApi.getTrend(startDate, endDate),
  });
}

export function useStatsCategories(type: number, startDate?: string, endDate?: string) {
  return useQuery<StatsCategory[]>({
    queryKey: ["stats", "categories", type, startDate, endDate],
    queryFn: () => statsApi.getCategories(type, startDate, endDate),
    enabled: !!type,
  });
}
