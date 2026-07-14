"use client";
import { Progress } from "@arco-design/web-react";
import { formatAmount, formatPercent } from "@/lib/utils/format";
import type { RiskLevel } from "@/lib/types";

interface BudgetProgressProps {
  spent: number;
  amount: number;
  usageRate: number;
  warningThreshold: number;
  riskLevel: RiskLevel;
  showLabel?: boolean;
}

const riskColors: Record<string, string> = {
  low: "#10b981",
  medium: "#a85a42",
  high: "#ef4444",
  critical: "#dc2626",
};

const riskGradients: Record<string, string> = {
  low: "linear-gradient(90deg, #10b981, #059669)",
  medium: "linear-gradient(90deg, #b86a52, #8f4558)",
  high: "linear-gradient(90deg, #ef4444, #dc2626)",
  critical: "linear-gradient(90deg, #dc2626, #b91c1c)",
};

export default function BudgetProgress({
  spent,
  amount,
  usageRate,
  warningThreshold,
  riskLevel,
  showLabel = true,
}: BudgetProgressProps) {
  const percent = Math.min(usageRate * 100, 100);
  const color = riskColors[riskLevel] || "#6366f1";
  const gradient = riskGradients[riskLevel] || "linear-gradient(90deg, #6366f1, #8b5cf6)";

  return (
    <div>
      {showLabel && (
        <div className="flex justify-between text-sm mb-2">
          <span style={{ color: "var(--text-color-2)" }}>
            {formatAmount(spent)} / {formatAmount(amount)}
          </span>
          <span className="font-semibold" style={{ color }}>
            {formatPercent(usageRate)}
          </span>
        </div>
      )}
      <div className="relative">
        <Progress
          percent={percent}
          color={gradient}
          trailColor="var(--border-color-light)"
          showText={false}
          style={{ height: 8, borderRadius: 4 }}
        />
        {/* Warning threshold marker */}
        <div
          className="absolute top-0 h-2 w-0.5"
          style={{
            left: `${warningThreshold}%`,
            backgroundColor: "var(--text-color-4)",
            transform: "translateX(-50%)",
          }}
        />
      </div>
      <div className="flex justify-between text-xs mt-1">
        <span style={{ color: "var(--text-color-4)" }}>
          已用 {formatPercent(usageRate)}
        </span>
        <span style={{ color: "var(--text-color-4)" }}>
          预警 {warningThreshold}%
        </span>
      </div>
    </div>
  );
}
