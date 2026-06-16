export type NotificationSeverity = "info" | "success" | "warning" | "critical" | string;

export interface NotificationItem {
  id: number;
  userId: number;
  companyId: number;
  type: string;
  severity: NotificationSeverity;
  title: string;
  content: string;
  targetUrl?: string | null;
  sourceType?: string | null;
  sourceId?: number | null;
  readAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface NotificationSummary {
  unreadCount: number;
  pendingDeliveryCount?: number;
  failedDeliveryCount?: number;
}

export interface NotificationPreference {
  userId: number;
  enabled: boolean;
  webhookEnabled: boolean;
  webhookProvider: "generic" | "feishu" | "wecom" | string;
  webhookUrl?: string | null;
  minSeverity: "info" | "warning" | "critical" | string;
  mutedTypes: string[];
  createdAt: string;
  updatedAt: string;
}
