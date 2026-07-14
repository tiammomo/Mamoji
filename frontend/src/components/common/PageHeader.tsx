"use client";
import type { ReactNode } from "react";
import { Button } from "@arco-design/web-react";
import { IconLeft } from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";

interface PageHeaderProps {
  title: string;
  subtitle?: string;
  extra?: ReactNode;
  back?: boolean;
  icon?: ReactNode;
  eyebrow?: string;
  meta?: ReactNode;
}

export default function PageHeader({ title, subtitle, extra, back, icon, eyebrow, meta }: PageHeaderProps) {
  const router = useRouter();

  return (
    <header className="page-header animate-fade-in">
      <div className="page-header-main">
        {back && (
          <Button
            type="text"
            icon={<IconLeft />}
            onClick={() => router.back()}
            aria-label="返回上一页"
            className="arco-btn-icon-only"
            style={{ fontSize: 20 }}
          />
        )}
        {icon && <span className="page-header-icon" aria-hidden="true">{icon}</span>}
        <div className="page-header-copy">
          {eyebrow && <div className="page-header-eyebrow">{eyebrow}</div>}
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="page-header-title" style={{ color: "var(--text-color-1)" }}>
            {title}
            </h1>
            {meta}
          </div>
          {subtitle && (
            <p className="page-header-subtitle" style={{ color: "var(--text-color-3)" }}>
              {subtitle}
            </p>
          )}
        </div>
      </div>
      {extra && <div className="page-header-actions">{extra}</div>}
    </header>
  );
}
