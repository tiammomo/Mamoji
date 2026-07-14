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
export type InvoiceCheckStatus = "not_required" | "pending" | "verified" | "failed";
export type DeductionStatus = "not_applicable" | "pending" | "deductible" | "deducted" | "transferred_out";
export type ReimbursementStatus = "not_applicable" | "submitted" | "approved" | "paid" | "archived" | "rejected";
export type ApprovalStatus = "not_required" | "not_submitted" | "pending" | "approved" | "rejected";
export type AccountingStatus = "not_started" | "draft" | "posted" | "reversed";

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
  taxRate: number;
  taxPeriod?: string | null;
  invoiceCheckStatus: InvoiceCheckStatus | string;
  deductionStatus: DeductionStatus | string;
  reimbursementStatus: ReimbursementStatus | string;
  approvalStatus: ApprovalStatus | string;
  accountingStatus: AccountingStatus | string;
  accountingVoucherNo?: string | null;
  accountingEntry?: string | null;
  approvedByUserId?: number | null;
  approvedAt?: string | null;
  accountedAt?: string | null;
  businessPurpose?: string | null;
  expenseOwner?: string | null;
  issueDate: string;
  dueDate?: string | null;
  status: ReceiptStatus | string;
  fileName?: string | null;
  fileSize: number;
  fileType?: string | null;
  fileStorageProvider?: string | null;
  fileBucket?: string | null;
  fileObjectKey?: string | null;
  fileUrl?: string | null;
  riskLevel: ReceiptRiskLevel | string;
  note?: string | null;
  operatorUserId: number;
  createdAt: string;
  updatedAt: string;
}

export interface ReceiptSummary {
  totalCount: number;
  totalAmount: number;
  salesInvoiceAmount: number;
  purchaseInvoiceAmount: number;
  outputTaxAmount: number;
  deductibleTaxAmount: number;
  reimbursementAmount: number;
  reimbursementPendingAmount: number;
  pendingAmount: number;
  pendingReviewCount: number;
  missingAttachmentCount: number;
  missingTransactionCount: number;
  highRiskCount: number;
  uncheckedInvoiceCount: number;
  pendingDeductionCount: number;
  pendingReimbursementCount: number;
  missingTaxPeriodCount: number;
  pendingApprovalCount: number;
  pendingAccountingCount: number;
  postedAccountingCount: number;
}

export interface ReceiptQuery {
  companyId?: number;
  keyword?: string;
  voucherType?: string;
  direction?: string;
  status?: string;
  invoiceCheckStatus?: string;
  deductionStatus?: string;
  reimbursementStatus?: string;
  taxPeriod?: string;
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
  taxRate?: number;
  taxPeriod?: string | null;
  invoiceCheckStatus?: string;
  deductionStatus?: string;
  reimbursementStatus?: string;
  approvalStatus?: string;
  accountingStatus?: string;
  accountingVoucherNo?: string | null;
  accountingEntry?: string | null;
  businessPurpose?: string | null;
  expenseOwner?: string | null;
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

export interface ReceiptAuditLog {
  id: number;
  companyId: number;
  entityType: string;
  entityId: number;
  action: string;
  summary: string;
  actorUserId: number;
  actorName: string;
  createdAt: string;
}

export interface ReceiptFileLink {
  url: string;
  provider?: string | null;
  objectKey?: string | null;
  expiresInSeconds: number;
}
