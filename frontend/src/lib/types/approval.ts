export type ApprovalRequestStatus = "pending" | "approved" | "rejected" | "withdrawn";

export interface ApprovalRequest {
  id: number;
  companyId: number;
  requestType: string;
  entityType: string;
  entityId: number | null;
  title: string;
  amount: number;
  applicantUserId: number;
  assigneeUserId: number | null;
  status: ApprovalRequestStatus | string;
  currentStep: string;
  description: string | null;
  decidedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ApprovalAction {
  id: number;
  requestId: number;
  actorUserId: number;
  action: string;
  comment: string | null;
  createdAt: string;
}

export interface ApprovalDetail {
  request: ApprovalRequest;
  actions: ApprovalAction[];
}

export interface ApprovalSummary {
  total: number;
  pending: number;
  approved: number;
  rejected: number;
  minePending: number;
}

export interface ApprovalPayload {
  companyId?: number;
  requestType: string;
  entityType?: string;
  entityId?: number;
  title: string;
  amount?: number;
  assigneeUserId?: number;
  description?: string;
  comment?: string;
}
