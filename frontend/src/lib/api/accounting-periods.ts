import client from "./client";
import type { AccountingPeriodControl } from "@/lib/types";

export const accountingPeriodApi = {
  current: (companyId?: number) => client.get<AccountingPeriodControl>(
    "/accounting-periods/control",
    { params: companyId ? { companyId } : undefined },
  ),
  close: (data: { version: number; throughMonth: string; companyId?: number }) =>
    client.post<AccountingPeriodControl>("/accounting-periods/control/close", data),
  reopen: (data: { version: number; reason: string; throughMonth?: string; companyId?: number }) =>
    client.post<AccountingPeriodControl>("/accounting-periods/control/reopen", data),
};
