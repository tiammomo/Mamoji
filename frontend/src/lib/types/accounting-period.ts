export type AccountingPeriodAction = "INITIAL" | "CLOSE" | "REOPEN";

export interface AccountingPeriodControl {
  companyId: number;
  version: number;
  closedThrough: string | null;
  lastAction: AccountingPeriodAction;
  lastActionAt: string;
  lastActionBy: number | null;
  lastActionReason: string | null;
  createdAt: string;
  updatedAt: string;
}
