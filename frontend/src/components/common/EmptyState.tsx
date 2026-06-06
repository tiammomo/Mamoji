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
    <div className="flex flex-col items-center justify-center py-16 animate-fade-in">
      <div className="mb-4 text-6xl">{icon}</div>
      <h3 className="text-lg font-medium mb-2" style={{ color: "var(--text-color-2)" }}>
        {title}
      </h3>
      {description && (
        <p className="text-sm mb-6" style={{ color: "var(--text-color-3)" }}>
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
