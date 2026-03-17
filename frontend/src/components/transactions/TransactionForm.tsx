"use client";

import { useEffect, useState } from "react";
import { ArrowDownCircle, ArrowUpCircle, X } from "lucide-react";
import { type Account, type Category, type Transaction } from "@/lib/api";

/**
 * Modal form props for creating or editing a transaction.
 */
interface TransactionFormProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (data: TransactionFormData) => Promise<void>;
  categories: { income: Category[]; expense: Category[] };
  accounts: Account[];
  editingTransaction?: Transaction | null;
  loading?: boolean;
}

export interface TransactionFormData {
  type: 1 | 2;
  amount: string;
  categoryId: number | null;
  accountId: number | null;
  date: string;
  remark: string;
}

/**
 * Transaction editing dialog with type/category/account/date controls.
 */
export function TransactionForm({
  isOpen,
  onClose,
  onSubmit,
  categories,
  accounts,
  editingTransaction,
  loading,
}: TransactionFormProps) {
  const [formData, setFormData] = useState<TransactionFormData>({
    type: 2,
    amount: "",
    categoryId: null,
    accountId: null,
    date: new Date().toISOString().split("T")[0],
    remark: "",
  });

  // Rehydrate form state from editing record, otherwise reset to defaults.
  useEffect(() => {
    if (editingTransaction) {
      setFormData({
        type: editingTransaction.type as 1 | 2,
        amount: editingTransaction.amount.toString(),
        categoryId: editingTransaction.category?.id ?? null,
        accountId: editingTransaction.account?.id ?? null,
        date: editingTransaction.date,
        remark: editingTransaction.remark || "",
      });
      return;
    }

    setFormData({
      type: 2,
      amount: "",
      categoryId: categories.expense[0]?.id || null,
      accountId: accounts.length > 0 ? accounts[0].id : null,
      date: new Date().toISOString().split("T")[0],
      remark: "",
    });
  }, [editingTransaction, isOpen, categories, accounts]);

  // Prevent invalid submissions when required fields are missing.
  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!formData.categoryId || !formData.amount || !formData.accountId) {
      return;
    }
    await onSubmit(formData);
  }

  if (!isOpen) {
    return null;
  }

  const currentCategories = formData.type === 1 ? categories.income : categories.expense;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-2xl w-full max-w-md mx-4 max-h-[85vh] overflow-y-auto">
        <div className="flex items-center justify-between p-4 border-b">
          <h2 className="text-lg font-bold">{editingTransaction ? "编辑记账" : "新增记账"}</h2>
          <button onClick={onClose} className="p-1.5 hover:bg-gray-100 rounded-lg transition-colors">
            <X className="w-4 h-4 text-gray-500" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">类型</label>
            <div className="flex gap-3">
              <button
                type="button"
                onClick={() => setFormData((prev) => ({ ...prev, type: 1, categoryId: categories.income[0]?.id || null }))}
                className={`flex-1 py-2 rounded-lg font-medium transition-all ${
                  formData.type === 1
                    ? "bg-green-100 text-green-700 border-2 border-green-500"
                    : "bg-gray-50 text-gray-600 border-2 border-transparent hover:bg-gray-100"
                }`}
              >
                <ArrowUpCircle className="w-5 h-5 mx-auto mb-1" />
                收入
              </button>
              <button
                type="button"
                onClick={() => setFormData((prev) => ({ ...prev, type: 2, categoryId: categories.expense[0]?.id || null }))}
                className={`flex-1 py-2 rounded-lg font-medium transition-all ${
                  formData.type === 2
                    ? "bg-red-100 text-red-700 border-2 border-red-500"
                    : "bg-gray-50 text-gray-600 border-2 border-transparent hover:bg-gray-100"
                }`}
              >
                <ArrowDownCircle className="w-5 h-5 mx-auto mb-1" />
                支出
              </button>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">金额</label>
            <div className="relative">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-lg text-gray-400">¥</span>
              <input
                type="number"
                step="0.01"
                min="0"
                value={formData.amount}
                onChange={(e) => setFormData((prev) => ({ ...prev, amount: e.target.value }))}
                required
                className="w-full pl-9 pr-3 py-2.5 text-lg border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
                placeholder="0.00"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">分类</label>
            <div className="grid grid-cols-5 gap-2">
              {currentCategories.map((category) => (
                <button
                  key={category.id}
                  type="button"
                  onClick={() => setFormData((prev) => ({ ...prev, categoryId: category.id }))}
                  className={`px-2 py-1.5 rounded-lg text-xs font-medium transition-all ${
                    formData.categoryId === category.id
                      ? "bg-indigo-100 text-indigo-700 ring-2 ring-indigo-500"
                      : "bg-gray-100 text-gray-700 hover:bg-gray-200"
                  }`}
                >
                  {category.name}
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">账户</label>
            <select
              value={formData.accountId || ""}
              onChange={(e) => setFormData((prev) => ({ ...prev, accountId: parseInt(e.target.value, 10) }))}
              required
              className="w-full px-3 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent text-sm"
            >
              <option value="">选择账户</option>
              {accounts.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">日期</label>
            <div className="relative">
              <svg
                className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
                />
              </svg>
              <input
                type="date"
                value={formData.date}
                onChange={(e) => setFormData((prev) => ({ ...prev, date: e.target.value }))}
                required
                className="w-full pl-10 pr-3 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent text-sm"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">备注</label>
            <textarea
              value={formData.remark}
              onChange={(e) => setFormData((prev) => ({ ...prev, remark: e.target.value }))}
              rows={2}
              className="w-full px-3 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent resize-none text-sm"
              placeholder="可选备注"
            />
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
              disabled={loading || !formData.amount || !formData.categoryId || !formData.accountId}
              className="flex-1 px-4 py-2.5 bg-indigo-600 text-white rounded-lg font-medium hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors text-sm"
            >
              {loading ? "保存中..." : "保存"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
