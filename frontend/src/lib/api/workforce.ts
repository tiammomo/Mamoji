import client from "./client";
import type { WorkforceCostView } from "@/lib/types";

export const workforceApi = {
  view: (params?: { companyId?: number; period?: string }) =>
    client.get<WorkforceCostView>("/workforce-cost", { params }),
};
