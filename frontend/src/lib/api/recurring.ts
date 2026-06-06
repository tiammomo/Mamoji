import client from "./client";

export interface RecurringItem {
  id: string;
  name: string;
  type: 1 | 2; // 1=income, 2=expense
  amount: number;
  frequency: "daily" | "weekly" | "monthly" | "yearly";
  interval: number;
  dayOfWeek?: number;
  dayOfMonth?: number;
  monthOfYear?: number;
  startDate: string;
  endDate?: string;
  lastExecuted?: string;
  nextExecution: string;
  status: number; // 0=disabled, 1=active
  executionCount: number;
  note?: string;
}

export interface CreateRecurringDTO {
  name: string;
  type: 1 | 2;
  amount: number;
  frequency: "daily" | "weekly" | "monthly" | "yearly";
  interval: number;
  dayOfWeek?: number;
  dayOfMonth?: number;
  monthOfYear?: number;
  startDate: string;
  endDate?: string;
  note?: string;
}

export const recurringApi = {
  list: () => client.get<RecurringItem[]>("/recurring"),
  create: (data: CreateRecurringDTO) => client.post<RecurringItem>("/recurring", data),
  update: (id: string, data: Partial<CreateRecurringDTO>) =>
    client.put<RecurringItem>(`/recurring/${id}`, data),
  delete: (id: string) => client.delete(`/recurring/${id}`),
  toggle: (id: string) => client.post(`/recurring/${id}/toggle`),
  execute: (id: string) => client.post(`/recurring/${id}/execute`),
};
