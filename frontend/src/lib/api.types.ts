export interface ApiError extends Error {
  code?: number;
  status?: number;
}

export interface User {
  id: number;
  email: string;
  nickname: string;
  role: number;
  roleName: string;
  permissions: number;
  permissionsName: string;
  familyId: number;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface UserProfile {
  id: number;
  email: string;
  nickname: string;
  avatarUrl: string;
}

export interface AIChatResponse {
  reply: string;
}

export type AIChatMode = "auto" | "llm" | "agent";

export interface AIStreamDonePayload {
  done: boolean;
  warnings: string[];
  sources: string[];
  actions: string[];
  usage: Record<string, unknown>;
  modeUsed?: AIChatMode;
  traceId?: string;
}

export interface Account {
  id: number;
  name: string;
  type: string;
  subType?: string;
  bank?: string;
  balance: number;
  includeInNetWorth: boolean;
  userId: number;
  ledgerId?: number;
  status: number;
}

export interface Transaction {
  id: number;
  amount: number;
  type: number;
  categoryId?: number | null;
  categoryName?: string;
  category?: {
    id: number;
    name: string;
    icon: string;
  } | null;
  accountId?: number | null;
  accountName?: string;
  account?: {
    id: number;
    name: string;
  } | null;
  userId?: number;
  user?: {
    id: number;
    nickname: string;
  } | null;
  date: string;
  remark?: string;
  ledgerId?: number;
  refundedAmount?: number;
  refundableAmount?: number;
  canRefund?: boolean;
}

export interface TransactionListResponse {
  total: number;
  pageSize: number;
  page: number;
  list: Transaction[];
}

export interface Category {
  id: number;
  name: string;
  icon: string;
  color: string;
  type: number;
  isSystem: number;
}

export interface CategoryListResponse {
  income: Category[];
  expense: Category[];
}

export interface Budget {
  id: number;
  name: string;
  amount: number;
  startDate: string;
  endDate: string;
  warningThreshold: number;
  status: number;
  spent: number;
  usageRate?: number;
  userId: number;
  ledgerId?: number;
  categoryId?: number;
}

export interface Ledger {
  id: number;
  name: string;
  description?: string;
  currency: string;
  ownerId: number;
  isDefault: boolean;
  status: number;
}

export interface LedgerMember {
  id: number;
  ledgerId: number;
  userId: number;
  nickname: string;
  email: string;
  role: string;
  status: number;
}

export interface StatsOverview {
  income: number;
  expense: number;
  balance: number;
  incomeCount: number;
  expenseCount: number;
}

export interface StatsTrend {
  month: string;
  income: number;
  expense: number;
}

export interface StatsCategory {
  categoryId: number;
  categoryName: string;
  categoryIcon: string;
  amount: number;
  percentage: number;
}

export interface Receipt {
  id: number;
  fileName: string;
  originalName: string;
  fileType: string;
  fileSize: number;
  description?: string;
  amount?: number;
  merchant?: string;
  date?: string;
  transactionId?: number;
  createdAt: string;
  updatedAt: string;
}

export interface ReceiptListResponse {
  total: number;
  pageSize: number;
  page: number;
  list: Receipt[];
}

export interface RecurringTransaction {
  id: number;
  name: string;
  type: number;
  amount: number;
  categoryId?: number;
  accountId?: number;
  remark?: string;
  recurrenceType: "DAILY" | "WEEKLY" | "MONTHLY" | "YEARLY";
  intervalCount?: number;
  dayOfWeek?: number;
  dayOfMonth?: number;
  monthOfYear?: number;
  startDate: string;
  endDate?: string;
  nextExecutionDate?: string;
  lastExecutionDate?: string;
  status: number;
  executionCount: number;
}

export interface RecurringListResponse {
  total: number;
  pageSize: number;
  page: number;
  list: RecurringTransaction[];
}

export interface BackupStatus {
  users: number;
  accounts: number;
  categories: number;
  transactions: number;
  budgets: number;
  ledgers: number;
}

export interface BackupImportResponse {
  code: number;
  message?: string;
  data?: {
    importedCount?: number;
  };
}
