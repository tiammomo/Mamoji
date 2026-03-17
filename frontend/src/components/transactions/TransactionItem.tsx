"use client";

import { ArrowDownCircle, ArrowUpCircle, Pencil, RefreshCcw } from "lucide-react";
import { type Transaction } from "@/lib/api";

/**
 * One row in the transaction list, with edit/refund actions.
 */
interface TransactionItemProps {
  tx: Transaction;
  onEdit: (tx: Transaction) => void;
  onRefund?: (tx: Transaction) => void;
}

/**
 * Renders transaction type icon, amount and quick actions.
 */
export function TransactionItem({ tx, onEdit, onRefund }: TransactionItemProps) {
  // Maps transaction type to color tokens and amount prefix.
  function getTypeColor(type: number) {
    switch (type) {
      case 1:
        return { bg: "bg-green-100", icon: "text-green-600", text: "text-green-600", prefix: "+" };
      case 2:
        return { bg: "bg-red-100", icon: "text-red-600", text: "text-red-600", prefix: "-" };
      case 3:
        return { bg: "bg-blue-100", icon: "text-blue-600", text: "text-blue-600", prefix: "+" };
      default:
        return { bg: "bg-gray-100", icon: "text-gray-600", text: "text-gray-600", prefix: "" };
    }
  }

  const colors = getTypeColor(tx.type);

  return (
    <div className="p-4 flex items-center justify-between hover:bg-gray-50 transition-colors">
      <div className="flex items-center gap-4">
        <div className={`w-12 h-12 rounded-full flex items-center justify-center ${colors.bg}`}>
          {tx.type === 1 ? (
            <ArrowUpCircle className={`w-6 h-6 ${colors.icon}`} />
          ) : tx.type === 2 ? (
            <ArrowDownCircle className={`w-6 h-6 ${colors.icon}`} />
          ) : (
            <RefreshCcw className={`w-6 h-6 ${colors.icon}`} />
          )}
        </div>
        <div>
          <p className="font-medium text-gray-900">{tx.category?.name}</p>
          <p className="text-sm text-gray-500">
            {tx.account?.name} | {tx.user?.nickname}
          </p>
          {tx.remark && <p className="text-sm text-gray-400">{tx.remark}</p>}
        </div>
      </div>

      <div className="flex items-center gap-3">
        <div className="text-right min-w-[90px]">
          <p className={`text-lg font-semibold ${colors.text}`}>
            {colors.prefix}¥{tx.amount.toFixed(2)}
          </p>
          <p className="text-sm text-gray-500">{tx.date}</p>
        </div>
        <div className="flex items-center gap-1">
          {tx.type !== 3 && (
            <button
              onClick={() => onEdit(tx)}
              className="p-2 text-gray-400 hover:text-indigo-600 hover:bg-indigo-50 rounded-lg transition-colors"
            >
              <Pencil className="w-4 h-4" />
            </button>
          )}
          {tx.type === 2 && tx.canRefund && onRefund && (
            <button
              onClick={() => onRefund(tx)}
              className="p-2 text-gray-400 hover:text-green-600 hover:bg-green-50 rounded-lg transition-colors"
              title="退款"
            >
              <RefreshCcw className="w-4 h-4" />
            </button>
          )}
          {!tx.canRefund && tx.type !== 3 && <div className="w-9" />}
        </div>
      </div>
    </div>
  );
}
