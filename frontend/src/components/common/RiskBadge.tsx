"use client";

interface RiskBadgeProps {
  level: "low" | "medium" | "high" | "critical";
  text?: string;
}

const riskConfig: Record<string, { color: string; bg: string; label: string }> = {
  low: { color: "#10b981", bg: "#10b98120", label: "低风险" },
  medium: { color: "#f59e0b", bg: "#f59e0b20", label: "中风险" },
  high: { color: "#ef4444", bg: "#ef444420", label: "高风险" },
  critical: { color: "#dc2626", bg: "#dc262620", label: "严重" },
};

export default function RiskBadge({ level, text }: RiskBadgeProps) {
  const config = riskConfig[level] || riskConfig.low;

  return (
    <span
      className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium"
      style={{
        backgroundColor: config.bg,
        color: config.color,
      }}
    >
      <span
        className="w-1.5 h-1.5 rounded-full"
        style={{ backgroundColor: config.color }}
      />
      {text || config.label}
    </span>
  );
}
