"use client";
import type { ReactNode } from "react";
import { Button, Space } from "@arco-design/web-react";
import { IconLeft } from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";

interface PageHeaderProps {
  title: string;
  subtitle?: string;
  extra?: ReactNode;
  back?: boolean;
  icon?: ReactNode;
}

export default function PageHeader({ title, subtitle, extra, back, icon }: PageHeaderProps) {
  const router = useRouter();

  return (
    <div className="flex items-center justify-between mb-6 animate-fade-in">
      <div className="flex items-center gap-3">
        {back && (
          <Button
            type="text"
            icon={<IconLeft />}
            onClick={() => router.back()}
            className="arco-btn-icon-only"
            style={{ fontSize: 20 }}
          />
        )}
        {icon && <span className="text-2xl leading-none inline-flex items-center">{icon}</span>}
        <div>
          <h1 className="text-xl font-semibold m-0" style={{ color: "var(--text-color-1)" }}>
            {title}
          </h1>
          {subtitle && (
            <p className="text-sm mt-1 m-0" style={{ color: "var(--text-color-3)" }}>
              {subtitle}
            </p>
          )}
        </div>
      </div>
      {extra && <Space>{extra}</Space>}
    </div>
  );
}
