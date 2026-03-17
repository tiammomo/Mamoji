"use client";

import { useEffect, useState } from "react";
import { X } from "lucide-react";
import { type Transaction } from "@/lib/api";

/**
 * Refund modal for creating a refund transaction from one expense record.
 */
interface RefundModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (amount: number, date: string) => Promise<void>;
  transaction: Transaction | null;
  loading?: boolean;
}

/**
 * Guides user through refund amount/date input and submits validated payload.
 */
export function RefundModal({ isOpen, onClose, onSubmit, transaction, loading }: RefundModalProps) {
  const [amount, setAmount] = useState("");
  const [date, setDate] = useState(new Date().toISOString().split("T")[0]);

  // Sync modal defaults whenever selected transaction changes.
  useEffect(() => {
    if (!transaction) {
      return;
    }
    setAmount(transaction.refundableAmount?.toString() || transaction.amount.toString());
    setDate(new Date().toISOString().split("T")[0]);
  }, [transaction]);

  // Submit only when amount is a valid positive number.
  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!amount || parseFloat(amount) <= 0) {
      return;
    }
    await onSubmit(parseFloat(amount), date);
  }

  if (!isOpen || !transaction) {
    return null;
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-2xl w-full max-w-sm mx-4">
        <div className="flex items-center justify-between p-4 border-b">
          <h2 className="text-lg font-bold">退款</h2>
          <button onClick={onClose} className="p-1.5 hover:bg-gray-100 rounded-lg transition-colors">
            <X className="w-4 h-4 text-gray-500" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-4 space-y-4">
          <div className="bg-gray-50 rounded-xl p-4">
            <p className="text-sm text-gray-500 mb-1">原交易</p>
            <p className="font-medium text-gray-900">{transaction.category?.name}</p>
            <p className="text-lg font-bold text-red-600">-¥{transaction.amount.toFixed(2)}</p>
            <p className="text-sm text-gray-500">{transaction.date}</p>
          </div>

          <div className="flex justify-between items-center text-sm">
            <span className="text-gray-500">已退款</span>
            <span className="text-gray-700">¥{(transaction.refundedAmount || 0).toFixed(2)}</span>
          </div>
          <div className="flex justify-between items-center text-sm">
            <span className="text-gray-500">可退款</span>
            <span className="text-green-600 font-medium">¥{transaction.refundableAmount?.toFixed(2) || "0.00"}</span>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">退款金额</label>
            <div className="relative">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-lg text-gray-400">¥</span>
              <input
                type="number"
                step="0.01"
                min="0.01"
                max={transaction.refundableAmount}
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                required
                className="w-full pl-9 pr-3 py-2.5 text-lg border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-green-500 focus:border-transparent"
                placeholder="0.00"
              />
            </div>
          </div>

          <div className="flex gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 px-4 py-2.5 bg-gray-100 text-gray-700 rounded-lg font-medium hover:bg-gray-200 transition-colors text-sm"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={loading || !amount || parseFloat(amount) <= 0}
              className="flex-1 px-4 py-2.5 bg-green-600 text-white rounded-lg font-medium hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors text-sm"
            >
              {loading ? "处理中..." : "确认退款"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
