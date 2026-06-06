export type ReceiptVoucherType =
  | "sales_invoice"
  | "purchase_invoice"
  | "receipt"
  | "bank_slip"
  | "contract"
  | "reimbursement"
  | "tax_receipt";

export type ReceiptDirection = "income" | "expense";
export type ReceiptStatus = "pending_review" | "verified" | "linked" | "archived" | "rejected";
export type ReceiptRiskLevel = "low" | "medium" | "high" | "critical";

export interface ReceiptVoucher {
  id: number;
  companyId: number;
  transactionId: number | null;
  voucherNo: string;
  title: string;
  voucherType: ReceiptVoucherType | string;
  direction: ReceiptDirection | string;
  counterparty: string;
  amount: number;
  taxAmount: number;
  issueDate: string;
  dueDate?: string | null;
  status: ReceiptStatus | string;
  fileName?: string | null;
  fileSize: number;
  fileType?: string | null;
  riskLevel: ReceiptRiskLevel | string;
  note?: string | null;
  operatorUserId: number;
  createdAt: string;
  updatedAt: string;
}

export interface ReceiptSummary {
  totalCount: number;
  totalAmount: number;
  deductibleTaxAmount: number;
  pendingAmount: number;
  pendingReviewCount: number;
  missingAttachmentCount: number;
  missingTransactionCount: number;
  highRiskCount: number;
}

export interface ReceiptQuery {
  companyId?: number;
  keyword?: string;
  voucherType?: string;
  direction?: string;
  status?: string;
  linkState?: "linked" | "missing" | "";
  startDate?: string;
  endDate?: string;
  minAmount?: number;
  maxAmount?: number;
  page?: number;
  size?: number;
}

export interface ReceiptPayload {
  companyId?: number;
  transactionId?: number | null;
  voucherNo?: string;
  title: string;
  voucherType: string;
  direction: string;
  counterparty: string;
  amount: number;
  taxAmount?: number;
  issueDate: string;
  dueDate?: string | null;
  status?: string;
  fileName?: string | null;
  fileSize?: number;
  fileType?: string | null;
  note?: string | null;
}

export interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}
