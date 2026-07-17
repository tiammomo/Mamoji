export type WorkforceCostSource = "payroll_run" | "employee_estimate";
export type WorkforceCostSeverity = "info" | "warning" | "critical";

export interface WorkforceHeadcount {
  active: number;
  probation: number;
  onboarding: number;
  departedThisMonth: number;
  costed: number;
}

export interface WorkforceCostSummary {
  salary: number;
  overtime: number;
  employerSocial: number;
  employerHousing: number;
  other: number;
  total: number;
  average: number;
  operatingExpense: number;
  operatingExpenseShare: number;
}

export interface WorkforceDepartmentCost {
  departmentId: number | null;
  departmentName: string;
  headcount: number;
  salary: number;
  overtime: number;
  employerSocial: number;
  employerHousing: number;
  other: number;
  total: number;
  average: number;
  share: number;
  budget: number;
  budgetVariance: number;
  budgetUsageRate: number;
}

export interface WorkforceTrendPoint {
  period: string;
  total: number;
  headcount: number;
  average: number;
  status: "draft" | "closed" | "estimate" | string;
}

export interface WorkforceAttentionItem {
  code: string;
  title: string;
  detail: string;
  severity: WorkforceCostSeverity;
  path: string;
}

export interface WorkforceCostView {
  companyId: number;
  companyName: string;
  period: string;
  source: WorkforceCostSource;
  payrollRunId: number | null;
  payrollRunStatus: string | null;
  headcount: WorkforceHeadcount;
  costs: WorkforceCostSummary;
  departments: WorkforceDepartmentCost[];
  trend: WorkforceTrendPoint[];
  attentionItems: WorkforceAttentionItem[];
}
