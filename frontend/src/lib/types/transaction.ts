export type TransactionType = 1 | 2 | 3; // 1=income, 2=expense, 3=refund

export interface Transaction {
  id: number;
  userId: number;
  familyId: number | null;
  type: TransactionType;
  amount: number;
  categoryId: number;
  categoryName?: string;
  categoryIcon?: string;
  categoryColor?: string;
  accountId: number;
  accountName?: string;
  date: string;
  note: string;
  originalTransactionId: number | null;
  refundedAmount: number;
  isRefundable: boolean;
  budgetId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateTransactionDTO {
  type: TransactionType;
  amount: number;
  categoryId: number;
  accountId: number;
  date: string;
  note?: string;
}

export interface UpdateTransactionDTO {
  amount?: number;
  categoryId?: number;
  accountId?: number;
  date?: string;
  note?: string;
}

export interface RefundDTO {
  amount: number;
  date: string;
  note?: string;
}

export interface TransactionQuery {
  type?: TransactionType;
  categoryId?: number;
  startDate?: string;
  endDate?: string;
  keyword?: string;
  minAmount?: number;
  maxAmount?: number;
  page?: number;
  size?: number;
}

export interface RiskAssessment {
  level: "low" | "medium" | "high" | "critical";
  flags: string[];
  message: string;
  monthlyIncome: number;
  monthlyExpense: number;
  expenseIncomeRatio: number;
  dailyExpenseCount: number;
  duplicateCount: number;
  categoryCurrentMonth: number;
  categoryLastMonth: number;
}
