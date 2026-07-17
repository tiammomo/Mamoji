export type WorkspaceSeverity = "success" | "notice" | "warning" | "danger";

export interface WorkspaceMetrics {
  monthlyIncome: number | null;
  monthlyExpense: number | null;
  monthlyProfit: number | null;
  availableCash: number | null;
  budgetAmount: number | null;
  budgetSpent: number | null;
  budgetUsageRate: number | null;
  pendingApprovalCount: number;
  accountIssueCount: number;
  evidenceIssueCount: number;
  overdueRecurringCount: number;
  reviewTransactionCount: number;
}

export interface WorkspaceModuleHealth {
  key: string;
  title: string;
  score: number;
  severity: WorkspaceSeverity;
  detail: string;
  path: string;
}

export interface WorkspaceActionItem {
  code: string;
  title: string;
  detail: string;
  severity: WorkspaceSeverity;
  path: string;
}

export interface WorkspaceDailyCheck {
  key: string;
  label: string;
  done: boolean;
  detail: string;
  path: string;
}

export interface WorkspaceBudgetRisk {
  id: number;
  name: string;
  amount: number;
  spent: number;
  usageRate: number;
  riskLevel: "low" | "medium" | "high" | "critical";
}

export interface WorkspaceRecentTransaction {
  id: number;
  type: 1 | 2 | 3;
  amount: number;
  date: string;
  note: string;
  categoryName: string;
  accountName: string;
}

export interface WorkspaceUpcomingItem {
  id: string;
  title: string;
  dueDate: string;
  overdue: boolean;
  path: string;
}

export interface WorkspaceView {
  companyId: number;
  companyName: string;
  period: string;
  score: number;
  severity: WorkspaceSeverity;
  capabilities: string[];
  metrics: WorkspaceMetrics;
  modules: WorkspaceModuleHealth[];
  priorityActions: WorkspaceActionItem[];
  dailyChecks: WorkspaceDailyCheck[];
  budgetRisks: WorkspaceBudgetRisk[];
  recentTransactions: WorkspaceRecentTransaction[];
  upcomingItems: WorkspaceUpcomingItem[];
}
