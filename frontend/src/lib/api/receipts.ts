import client from "./client";
import type {
  PaginatedResponse,
  ReceiptPayload,
  ReceiptQuery,
  ReceiptAuditLog,
  ReceiptFileLink,
  ReceiptSummary,
  ReceiptVoucher,
} from "@/lib/types";

const getActiveCompanyId = () => {
  if (typeof window === "undefined") return undefined;
  const value = Number(localStorage.getItem("activeCompanyId"));
  return Number.isFinite(value) && value > 0 ? value : undefined;
};

const withCompany = <T extends { companyId?: number }>(params?: T): T => {
  const activeCompanyId = getActiveCompanyId();
  if (!activeCompanyId || params?.companyId) {
    return cleanParams(params || {}) as T;
  }
  return cleanParams({ ...params, companyId: activeCompanyId }) as T;
};

const cleanParams = (params: Record<string, unknown>) =>
  Object.fromEntries(Object.entries(params).filter(([, value]) => value !== "" && value !== undefined && value !== null));

export const receiptApi = {
  list: (params: ReceiptQuery) =>
    client.get<PaginatedResponse<ReceiptVoucher>>("/receipts", { params: withCompany(params) }),
  summary: (params?: { companyId?: number }) =>
    client.get<ReceiptSummary>("/receipts/summary", { params: withCompany(params) }),
  create: (data: ReceiptPayload) =>
    client.post<ReceiptVoucher>("/receipts", withCompany(data)),
  update: (id: number, data: Partial<ReceiptPayload>) =>
    client.put<ReceiptVoucher>(`/receipts/${id}`, data),
  auditLogs: (id: number) =>
    client.get<ReceiptAuditLog[]>(`/receipts/${id}/audit-logs`),
  fileLink: (id: number) =>
    client.get<ReceiptFileLink>(`/receipts/${id}/file-link`),
  upload: (file: File, data: Partial<ReceiptPayload>) => {
    const form = new FormData();
    form.append("file", file);
    Object.entries(withCompany(data)).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        form.append(key, String(value));
      }
    });
    return client.post<{ success: boolean; voucher: ReceiptVoucher; message: string }>("/receipts/upload", form);
  },
};
