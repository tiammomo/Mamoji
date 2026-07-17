import client from "./client";
import type { ApprovalDetail, ApprovalPayload, ApprovalRequest, ApprovalSummary } from "@/lib/types";
import type { PaginatedResponse } from "./transactions";

const requestKey = () => {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return `web-${Date.now()}-${Math.random().toString(36).slice(2)}`;
};

export const approvalApi = {
  list: (params: { page?: number; size?: number; status?: string; requestType?: string; keyword?: string } = {}) =>
    client.get<PaginatedResponse<ApprovalRequest>>("/approvals", { params }),
  summary: () => client.get<ApprovalSummary>("/approvals/summary"),
  get: (id: number) => client.get<ApprovalDetail>(`/approvals/${id}`),
  create: (data: ApprovalPayload) => client.post<ApprovalDetail>("/approvals", data, {
    headers: { "Idempotency-Key": requestKey() },
  }),
  approve: (id: number, comment?: string) => client.post<ApprovalDetail>(`/approvals/${id}/approve`, { comment }),
  reject: (id: number, comment: string) => client.post<ApprovalDetail>(`/approvals/${id}/reject`, { comment }),
  withdraw: (id: number, comment?: string) => client.post<ApprovalDetail>(`/approvals/${id}/withdraw`, { comment }),
};
