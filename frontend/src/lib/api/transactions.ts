import client from "./client";
import type {
  Transaction,
  CreateTransactionDTO,
  UpdateTransactionDTO,
  RefundDTO,
  TransactionQuery,
  RiskAssessment,
} from "@/lib/types";

export interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface TransactionSummary {
  income: number;
  expense: number;
  refund: number;
  pendingCollection: number;
  customerRefund: number;
  severance: number;
  netCollectedIncome: number;
  net: number;
  rows: number;
  largeCount: number;
  reviewCount: number;
}

export interface TransactionImportRow {
  rowNumber: number;
  date: string;
  type: number;
  amount: number;
  categoryId: number | null;
  categoryName: string;
  accountId: number | null;
  accountName: string;
  note: string;
  duplicate: boolean;
  errors: string[];
}

export interface TransactionImportResult {
  committed: boolean;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  duplicateRows: number;
  importedRows: number;
  skippedRows: number;
  transactionIds: number[];
  rows: TransactionImportRow[];
}

const requestKey = () => {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return `web-${Date.now()}-${Math.random().toString(36).slice(2)}`;
};

export const transactionApi = {
  list: (params: TransactionQuery) =>
    client.get<PaginatedResponse<Transaction>>("/transactions", { params }),
  summary: (params: Omit<TransactionQuery, "page" | "size">) =>
    client.get<TransactionSummary>("/transactions/summary", { params }),
  importTemplate: () => client.get<Blob>("/transactions/import/template", { responseType: "blob" }),
  previewImport: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return client.post<TransactionImportResult>("/transactions/import/preview", form);
  },
  importCsv: (file: File, skipDuplicates = true) => {
    const form = new FormData();
    form.append("file", file);
    return client.post<TransactionImportResult>("/transactions/import", form, { params: { skipDuplicates } });
  },
  get: (id: number) => client.get<Transaction>(`/transactions/${id}`),
  create: (data: CreateTransactionDTO) =>
    client.post<{ transaction: Transaction; risk: RiskAssessment; replayed?: boolean }>("/transactions", data, {
      headers: { "Idempotency-Key": requestKey() },
    }),
  update: (id: number, data: UpdateTransactionDTO) =>
    client.put<Transaction>(`/transactions/${id}`, data),
  delete: (id: number) => client.delete(`/transactions/${id}`),
  refund: (id: number, data: RefundDTO) =>
    client.post<{ transaction: Transaction; risk: RiskAssessment }>(`/transactions/${id}/refund`, data),
  refundable: () => client.get<Transaction[]>("/transactions/refundable"),
};
