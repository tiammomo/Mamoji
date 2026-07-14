export type AccountType = "cash" | "bank" | "credit" | "digital" | "investment" | "debt";
export type AccountRiskLevel = "low" | "medium" | "high" | "critical";
export type AccountReconciliationStatus = "reconciled" | "pending" | "exception";

export interface Account {
  id: number;
  name: string;
  type: AccountType;
  subType: string | null;
  bank: string | null;
  accountNo?: string | null;
  openingBank?: string | null;
  currency: string;
  balance: number;
  availableBalance: number;
  creditLimit: number;
  frozenAmount: number;
  includeInNetWorth: boolean;
  userId: number;
  ledgerId: number | null;
  status: number;
  openedAt?: string | null;
  lastReconciledAt?: string | null;
  ownerName?: string | null;
  purpose?: string | null;
  reconciliationStatus: AccountReconciliationStatus | string;
  riskLevel: AccountRiskLevel | string;
  monthlyIncome: number;
  monthlyExpense: number;
  currentMonthNetFlow: number;
  transactionCount: number;
  lastTransactionDate?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateAccountDTO {
  name: string;
  type: AccountType;
  subType?: string;
  bank?: string;
  accountNo?: string;
  openingBank?: string;
  currency?: string;
  balance: number;
  availableBalance?: number;
  creditLimit?: number;
  frozenAmount?: number;
  includeInNetWorth?: boolean;
  status?: number;
  openedAt?: string | null;
  lastReconciledAt?: string | null;
  ownerName?: string | null;
  purpose?: string | null;
  reconciliationStatus?: string;
}

export interface UpdateAccountDTO {
  name?: string;
  type?: AccountType;
  subType?: string;
  bank?: string;
  accountNo?: string | null;
  openingBank?: string | null;
  currency?: string;
  balance?: number;
  availableBalance?: number;
  creditLimit?: number;
  frozenAmount?: number;
  includeInNetWorth?: boolean;
  status?: number;
  openedAt?: string | null;
  lastReconciledAt?: string | null;
  ownerName?: string | null;
  purpose?: string | null;
  reconciliationStatus?: string;
}

export interface AccountSummary {
  totalAssets: number;
  totalLiabilities: number;
  netWorth: number;
  availableBalance: number;
  frozenAmount: number;
  creditLimit: number;
  currentMonthIncome: number;
  currentMonthExpense: number;
  accountCount: number;
  activeAccountCount: number;
  pendingReconciliationCount: number;
  highRiskCount: number;
}

export interface AccountReconciliation {
  id: number;
  companyId: number;
  accountId: number;
  statementDate: string;
  statementBalance: number;
  systemBalance: number;
  difference: number;
  status: AccountReconciliationStatus | string;
  note: string | null;
  createdBy: number;
  createdAt: string;
}
