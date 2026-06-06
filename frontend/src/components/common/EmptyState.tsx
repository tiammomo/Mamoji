"use client";
import { Button } from "@arco-design/web-react";

interface EmptyStateProps {
  icon?: string;
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
      <div className="text-6xl mb-4">{icon}</div>
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
