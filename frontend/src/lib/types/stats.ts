export interface OverviewStats {
  monthlyIncome: number;
  monthlyExpense: number;
  monthlyBalance: number;
  budgetUsageRate: number;
}

export interface TrendPoint {
  month: string;
  period?: "month" | "quarter" | "year";
  income: number;
  expense: number;
  balance: number;
  hasData?: boolean;
}

export interface CategoryStat {
  categoryId: number;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  amount: number;
  percentage: number;
  count: number;
}

export interface YearlyReport {
  year: number;
  months: {
    month: number;
    income: number;
    expense: number;
    balance: number;
  }[];
  totalIncome: number;
  totalExpense: number;
  totalBalance: number;
}

export interface AssetLiability {
  totalAssets: number;
  totalLiabilities: number;
  netWorth: number;
  accounts: {
    type: string;
    name: string;
    balance: number;
  }[];
}

export interface ComparisonData {
  current: number;
  previous: number;
  change: number;
  changePercent: number;
}

export interface AdvancedInsight {
  largeTransactions: { id: number; amount: number; category: string; date: string }[];
  categorySpikes: { category: string; current: number; previous: number; change: number }[];
  budgetAlerts: { name: string; usageRate: number; riskLevel: string }[];
}
