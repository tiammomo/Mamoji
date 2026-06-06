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

export const transactionApi = {
  list: (params: TransactionQuery) =>
    client.get<PaginatedResponse<Transaction>>("/transactions", { params }),
  get: (id: number) => client.get<Transaction>(`/transactions/${id}`),
  create: (data: CreateTransactionDTO) =>
    client.post<{ transaction: Transaction; risk: RiskAssessment }>("/transactions", data),
  update: (id: number, data: UpdateTransactionDTO) =>
    client.put<Transaction>(`/transactions/${id}`, data),
  delete: (id: number) => client.delete(`/transactions/${id}`),
  refund: (id: number, data: RefundDTO) =>
    client.post<{ transaction: Transaction; risk: RiskAssessment }>(`/transactions/${id}/refund`, data),
  refundable: () => client.get<Transaction[]>("/transactions/refundable"),
};
