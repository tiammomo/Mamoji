"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Plus, Wallet } from "lucide-react";
import { RefundModal, TransactionFilter, TransactionForm, TransactionItem } from "@/components/transactions";
import { useAccounts, useCategories, useCreateTransaction, useTransactions, useUpdateTransaction } from "@/lib/hooks";
import { api, type Transaction } from "@/lib/api";

export default function TransactionsPage() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const [expensePage, setExpensePage] = useState(1);
  const [incomePage, setIncomePage] = useState(1);
  const [typeFilter, setTypeFilter] = useState<number | null>(null);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [dateRange, setDateRange] = useState<{ start: string; end: string }>({ start: "", end: "" });
  const [showDateFilter, setShowDateFilter] = useState(false);

  const [showModal, setShowModal] = useState(false);
  const [editingTransaction, setEditingTransaction] = useState<Transaction | null>(null);

  const [showRefundModal, setShowRefundModal] = useState(false);
  const [refundingTransaction, setRefundingTransaction] = useState<Transaction | null>(null);

  useEffect(() => {
    if (searchParams.get("new") === "1") {
      setShowModal(true);
    }
  }, [searchParams]);

  useEffect(() => {
    const token = localStorage.getItem("token");
    if (!token) {
      router.push("/login");
    }
  }, [router]);

  useEffect(() => {
    setExpensePage(1);
    setIncomePage(1);
  }, [typeFilter, dateRange.start, dateRange.end]);

  const expenseTypes = useMemo(() => {
    if (typeFilter === 2) {
      return [2];
    }
    if (typeFilter === 3) {
      return [3];
    }
    return [2, 3];
  }, [typeFilter]);

  const expenseQueryParams = useMemo(
    () => ({
      page: expensePage,
      pageSize: 20,
      types: expenseTypes,
      startDate: dateRange.start || undefined,
      endDate: dateRange.end || undefined,
    }),
    [expensePage, expenseTypes, dateRange]
  );

  const incomeQueryParams = useMemo(
    () => ({
      page: incomePage,
      pageSize: 20,
      type: 1,
      startDate: dateRange.start || undefined,
      endDate: dateRange.end || undefined,
    }),
    [incomePage, dateRange]
  );

  const {
    data: expenseTransactionsData,
    isLoading: isExpenseLoading,
    refetch: refetchExpense,
  } = useTransactions(expenseQueryParams);
  const {
    data: incomeTransactionsData,
    isLoading: isIncomeLoading,
    refetch: refetchIncome,
  } = useTransactions(incomeQueryParams);
  const { data: categoriesData } = useCategories();
  const { data: accountsData } = useAccounts();

  const createMutation = useCreateTransaction();
  const updateMutation = useUpdateTransaction();

  const isLoading = isExpenseLoading || isIncomeLoading;
  const expenseTransactions = useMemo(() => expenseTransactionsData?.list ?? [], [expenseTransactionsData]);
  const incomeTransactions = useMemo(() => incomeTransactionsData?.list ?? [], [incomeTransactionsData]);
  const expenseTotal = expenseTransactionsData?.total || 0;
  const incomeTotal = incomeTransactionsData?.total || 0;
  const categories = categoriesData || { income: [], expense: [] };
  const accounts = accountsData || [];

  const matchesKeyword = useCallback(
    (tx: Transaction) => {
      if (!searchKeyword) {
        return true;
      }
      const keyword = searchKeyword.toLowerCase();
      return (
        tx.category?.name.toLowerCase().includes(keyword) ||
        tx.account?.name.toLowerCase().includes(keyword) ||
        Boolean(tx.remark && tx.remark.toLowerCase().includes(keyword)) ||
        tx.date.includes(keyword) ||
        tx.user?.nickname.toLowerCase().includes(keyword)
      );
    },
    [searchKeyword]
  );

  const filteredExpenseTransactions = useMemo(
    () => expenseTransactions.filter(matchesKeyword),
    [expenseTransactions, matchesKeyword]
  );
  const filteredIncomeTransactions = useMemo(
    () => incomeTransactions.filter(matchesKeyword),
    [incomeTransactions, matchesKeyword]
  );

  const expenseTotalPages = Math.ceil(expenseTotal / 20);
  const incomeTotalPages = Math.ceil(incomeTotal / 20);
  const showExpenseColumn = typeFilter !== 1;
  const showIncomeColumn = typeFilter !== 2 && typeFilter !== 3;

  const handleSubmit = useCallback(
    async (data: {
      type: 1 | 2;
      amount: string;
      categoryId: number | null;
      accountId: number | null;
      date: string;
      remark: string;
    }) => {
      const payload = {
        type: data.type,
        amount: parseFloat(data.amount),
        categoryId: data.categoryId,
        accountId: data.accountId,
        date: data.date,
        remark: data.remark,
      };

      if (editingTransaction) {
        await updateMutation.mutateAsync({ id: editingTransaction.id, data: payload });
      } else {
        await createMutation.mutateAsync(payload);
      }

      setShowModal(false);
      setEditingTransaction(null);
      await Promise.all([refetchExpense(), refetchIncome()]);
    },
    [editingTransaction, createMutation, updateMutation, refetchExpense, refetchIncome]
  );

  const handleRefund = useCallback(
    async (amount: number, date: string) => {
      if (!refundingTransaction) {
        return;
      }

      await api.post(`/transactions/${refundingTransaction.id}/refund`, {
        amount,
        date,
      });

      setShowRefundModal(false);
      setRefundingTransaction(null);
      await Promise.all([refetchExpense(), refetchIncome()]);
    },
    [refundingTransaction, refetchExpense, refetchIncome]
  );

  const handleEdit = useCallback((tx: Transaction) => {
    setEditingTransaction(tx);
    setShowModal(true);
  }, []);

  const handleRefundClick = useCallback((tx: Transaction) => {
    setRefundingTransaction(tx);
    setShowRefundModal(true);
  }, []);

  const handleCloseModal = useCallback(() => {
    setShowModal(false);
    setEditingTransaction(null);
  }, []);

  const handleCloseRefundModal = useCallback(() => {
    setShowRefundModal(false);
    setRefundingTransaction(null);
  }, []);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="animate-spin rounded-full h-12 w-12 border-t-2 border-b-2 border-indigo-600" />
      </div>
    );
  }

  return (
    <div className="p-6 lg:p-8">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">交易记录</h1>
          <p className="text-gray-500 mt-1">查看所有记账明细</p>
        </div>
        <button
          onClick={() => setShowModal(true)}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors"
        >
          <Plus className="w-5 h-5" />
          记账
        </button>
      </div>

      <TransactionFilter
        searchKeyword={searchKeyword}
        setSearchKeyword={setSearchKeyword}
        typeFilter={typeFilter}
        setTypeFilter={setTypeFilter}
        dateRange={dateRange}
        setDateRange={setDateRange}
        showDateFilter={showDateFilter}
        setShowDateFilter={setShowDateFilter}
      />

      {!showExpenseColumn && !showIncomeColumn ? (
        <div className="bg-white rounded-2xl shadow-sm p-12 text-center">
          <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <Wallet className="w-8 h-8 text-gray-400" />
          </div>
          <p className="text-gray-500 mb-4">暂无交易记录</p>
          <button
            onClick={() => setShowModal(true)}
            className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors"
          >
            <Plus className="w-4 h-4" />
            开始记账
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {showExpenseColumn && (
            <div className="bg-white rounded-2xl shadow-sm">
              <div className="px-4 py-3 border-b border-gray-100">
                <h2 className="text-lg font-semibold text-gray-900">支出（含退款）</h2>
              </div>
              {filteredExpenseTransactions.length === 0 ? (
                <div className="p-8 text-center text-gray-500">暂无支出记录</div>
              ) : (
                <div className="divide-y">
                  {filteredExpenseTransactions.map((tx) => (
                    <TransactionItem key={tx.id} tx={tx} onEdit={handleEdit} onRefund={handleRefundClick} />
                  ))}
                </div>
              )}
              {expenseTotalPages > 1 && (
                <div className="p-4 flex justify-center gap-2">
                  <button
                    onClick={() => setExpensePage((p) => Math.max(1, p - 1))}
                    disabled={expensePage === 1}
                    className="px-4 py-2 bg-gray-100 rounded-lg disabled:opacity-50 hover:bg-gray-200 transition-colors"
                  >
                    上一页
                  </button>
                  <span className="px-4 py-2 text-gray-600">
                    {expensePage} / {expenseTotalPages}
                  </span>
                  <button
                    onClick={() => setExpensePage((p) => Math.min(expenseTotalPages, p + 1))}
                    disabled={expensePage === expenseTotalPages}
                    className="px-4 py-2 bg-gray-100 rounded-lg disabled:opacity-50 hover:bg-gray-200 transition-colors"
                  >
                    下一页
                  </button>
                </div>
              )}
            </div>
          )}

          {showIncomeColumn && (
            <div className="bg-white rounded-2xl shadow-sm">
              <div className="px-4 py-3 border-b border-gray-100">
                <h2 className="text-lg font-semibold text-gray-900">收入</h2>
              </div>
              {filteredIncomeTransactions.length === 0 ? (
                <div className="p-8 text-center text-gray-500">暂无收入记录</div>
              ) : (
                <div className="divide-y">
                  {filteredIncomeTransactions.map((tx) => (
                    <TransactionItem key={tx.id} tx={tx} onEdit={handleEdit} onRefund={handleRefundClick} />
                  ))}
                </div>
              )}
              {incomeTotalPages > 1 && (
                <div className="p-4 flex justify-center gap-2">
                  <button
                    onClick={() => setIncomePage((p) => Math.max(1, p - 1))}
                    disabled={incomePage === 1}
                    className="px-4 py-2 bg-gray-100 rounded-lg disabled:opacity-50 hover:bg-gray-200 transition-colors"
                  >
                    上一页
                  </button>
                  <span className="px-4 py-2 text-gray-600">
                    {incomePage} / {incomeTotalPages}
                  </span>
                  <button
                    onClick={() => setIncomePage((p) => Math.min(incomeTotalPages, p + 1))}
                    disabled={incomePage === incomeTotalPages}
                    className="px-4 py-2 bg-gray-100 rounded-lg disabled:opacity-50 hover:bg-gray-200 transition-colors"
                  >
                    下一页
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      <TransactionForm
        isOpen={showModal}
        onClose={handleCloseModal}
        onSubmit={handleSubmit}
        categories={categories}
        accounts={accounts}
        editingTransaction={editingTransaction}
        loading={createMutation.isPending || updateMutation.isPending}
      />

      <RefundModal
        isOpen={showRefundModal}
        onClose={handleCloseRefundModal}
        onSubmit={handleRefund}
        transaction={refundingTransaction}
        loading={false}
      />
    </div>
  );
}

