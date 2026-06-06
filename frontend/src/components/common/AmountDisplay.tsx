"use client";
import { formatAmount } from "@/lib/utils/format";

interface AmountDisplayProps {
  amount: number;
  type?: 1 | 2 | 3;
  showSign?: boolean;
  size?: "small" | "medium" | "large";
  currency?: string;
}

export default function AmountDisplay({
  amount,
  type,
  showSign = false,
  size = "medium",
  currency,
}: AmountDisplayProps) {
  const absAmount = Math.abs(amount);
  const formatted = formatAmount(absAmount, currency);

  let className = "";
  let prefix = "";

  if (type === 1 || type === 3) {
    className = "amount-income";
    if (showSign) prefix = "+";
  } else if (type === 2) {
    className = "amount-expense";
    if (showSign) prefix = "-";
  }

  const sizeClass = {
    small: "text-sm",
    medium: "text-base",
    large: "text-2xl font-bold",
  }[size];

  return (
    <span className={`${className} ${sizeClass}`}>
      {prefix}{formatted}
    </span>
  );
}
