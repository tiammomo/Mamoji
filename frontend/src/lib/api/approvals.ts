import client from "./client";
import type { ApprovalDetail, ApprovalPayload, ApprovalRequest, ApprovalSummary } from "@/lib/types";
import type { PaginatedResponse } from "./transactions";

export const approvalApi = {
  list: (params: { page?: number; size?: number; status?: string; requestType?: string; keyword?: string } = {}) =>
    client.get<PaginatedResponse<ApprovalRequest>>("/approvals", { params }),
  summary: () => client.get<ApprovalSummary>("/approvals/summary"),
  get: (id: number) => client.get<ApprovalDetail>(`/approvals/${id}`),
  create: (data: ApprovalPayload) => client.post<ApprovalDetail>("/approvals", data),
  approve: (id: number, comment?: string) => client.post<ApprovalDetail>(`/approvals/${id}/approve`, { comment }),
  reject: (id: number, comment: string) => client.post<ApprovalDetail>(`/approvals/${id}/reject`, { comment }),
  withdraw: (id: number, comment?: string) => client.post<ApprovalDetail>(`/approvals/${id}/withdraw`, { comment }),
};
