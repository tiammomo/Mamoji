export type BudgetStatus = 0 | 1 | 2 | 3; // 0=disabled, 1=active, 2=completed, 3=overrun
export type RiskLevel = "low" | "medium" | "high" | "critical";

export interface Budget {
  id: number;
  version?: number;
  name: string;
  amount: number;
  startDate: string;
  endDate: string;
  warningThreshold: number;
  status: BudgetStatus;
  spent: number;
  remainingAmount: number;
  usageRate: number;
  warningReached: boolean;
  riskLevel: RiskLevel;
  riskMessage: string;
  userId: number;
  ledgerId: number | null;
  categoryId: number | null;
  categoryName?: string;
  categoryIcon?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateBudgetDTO {
  name: string;
  amount: number;
  startDate: string;
  endDate: string;
  warningThreshold: number;
  categoryId?: number;
}

export interface UpdateBudgetDTO {
  version: number;
  name?: string;
  amount?: number;
  startDate?: string;
  endDate?: string;
  warningThreshold?: number;
  categoryId?: number;
  clearCategory?: boolean;
  status?: BudgetStatus;
}

export interface BudgetQuery {
  status?: BudgetStatus;
  startDate?: string;
  endDate?: string;
  keyword?: string;
  page?: number;
  size?: number;
}
