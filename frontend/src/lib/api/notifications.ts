import client from "./client";
import type { NotificationItem, NotificationPreference, NotificationSummary, PaginatedResponse } from "@/lib/types";

export const notificationApi = {
  list: (params?: { page?: number; size?: number; unreadOnly?: boolean }) =>
    client.get<PaginatedResponse<NotificationItem>>("/notifications", { params }),
  summary: () => client.get<NotificationSummary>("/notifications/summary"),
  preference: () => client.get<NotificationPreference>("/notifications/preferences"),
  updatePreference: (data: Partial<NotificationPreference>) =>
    client.put<NotificationPreference>("/notifications/preferences", data),
  testWebhook: () => client.post<{ success: boolean }>("/notifications/preferences/test-webhook"),
  markRead: (id: number) => client.put<NotificationItem>(`/notifications/${id}/read`),
  markAllRead: () => client.put<{ updated: number }>("/notifications/read-all"),
};
