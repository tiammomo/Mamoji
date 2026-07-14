"use client";
import type { ReactNode } from "react";
import { Button } from "@arco-design/web-react";

interface EmptyStateProps {
  icon?: ReactNode;
  title?: string;
  description?: string;
  actionText?: string;
  onAction?: () => void;
}

export default function EmptyState({
  icon = "📭",
  title = "暂无数据",
  description,
  actionText,
  onAction,
}: EmptyStateProps) {
  return (
    <div className="empty-state animate-fade-in" role="status">
      <div className="empty-state-icon" aria-hidden="true">{icon}</div>
      <h3 className="mb-2 text-base font-semibold" style={{ color: "var(--text-color-1)" }}>
        {title}
      </h3>
      {description && (
        <p className={`${actionText && onAction ? "mb-5" : "mb-0"} max-w-md text-center text-sm leading-6`} style={{ color: "var(--text-color-3)" }}>
          {description}
        </p>
      )}
      {actionText && onAction && (
        <Button type="primary" onClick={onAction}>
          {actionText}
        </Button>
      )}
    </div>
  );
}
