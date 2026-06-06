"use client";
import { useEffect, useState, useCallback } from "react";
import { Card, Button, Space, Input, Select, Modal, Message } from "@arco-design/web-react";
import { IconPlus, IconSearch, IconDelete, IconEdit, IconRefresh } from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { transactionApi } from "@/lib/api/transactions";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import EmptyState from "@/components/common/EmptyState";
import { formatAmount, formatDate } from "@/lib/utils/format";
import type { Transaction, TransactionQuery } from "@/lib/types";

export default function TransactionsPage() {
  const router = useRouter();
  const t = useTranslations("transaction");
  const { fetchCategories } = useCategoryStore();
  const [data, setData] = useState<Transaction[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState<TransactionQuery>({ page: 0, size: 20 });
  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState<string>("all");

  const fetchData = useCallback(async () => {
    try {
      const res = await transactionApi.list(query);
      setData(res.data.content);
      setTotal(res.data.totalElements);
    } catch {
      // silent
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    let cancelled = false;

    const loadTransactions = async () => {
      try {
        const res = await transactionApi.list(query);
        if (cancelled) return;
        setData(res.data.content);
        setTotal(res.data.totalElements);
      } catch {
        // silent
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchCategories();
    void loadTransactions();

    return () => {
      cancelled = true;
    };
  }, [fetchCategories, query]);

  const handleSearch = () => {
    setLoading(true);
    setQuery((prev) => ({
      ...prev,
      keyword: search || undefined,
      type: typeFilter === "all" ? undefined : Number(typeFilter) as 1 | 2 | 3,
      page: 0,
    }));
  };

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: "确认删除",
      content: "确定要删除这笔交易吗？",
      onOk: async () => {
        try {
          await transactionApi.delete(id);
          Message.success("删除成功");
          fetchData();
        } catch {
          Message.error("删除失败");
        }
      },
    });
  };

  const typeColors: Record<number, { color: string; bg: string; label: string }> = {
    1: { color: "#10b981", bg: "#10b98120", label: "收入" },
    2: { color: "#ef4444", bg: "#ef444420", label: "成本支出" },
    3: { color: "#f59e0b", bg: "#f59e0b20", label: "退款" },
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title={t("title")}
        icon="💸"
        extra={
          <Button
            type="primary"
            icon={<IconPlus />}
            onClick={() => router.push("/transactions/new")}
          >
            {t("new")}
          </Button>
        }
      />

      {/* Filters */}
      <Card className="mb-6" style={{ borderRadius: 16 }}>
        <Space wrap>
          <Input
            prefix={<IconSearch style={{ color: "var(--text-color-4)" }} />}
            placeholder="搜索备注..."
            value={search}
            onChange={setSearch}
            onPressEnter={handleSearch}
            style={{ width: 240, borderRadius: 12 }}
          />
          <Select
            value={typeFilter}
            onChange={setTypeFilter}
            style={{ width: 140, borderRadius: 12 }}
          >
            <Select.Option value="all">全部类型</Select.Option>
            <Select.Option value="1">💰 收入</Select.Option>
            <Select.Option value="2">💸 成本支出</Select.Option>
            <Select.Option value="3">↩️ 退款</Select.Option>
          </Select>
          <Button type="primary" onClick={handleSearch}>
            搜索
          </Button>
        </Space>
      </Card>

      {/* Transaction list */}
      {data.length === 0 && !loading ? (
        <Card style={{ borderRadius: 16 }}>
          <EmptyState
            icon="📭"
            title="暂无经营流水"
            description="点击上方按钮录入第一笔收入或成本"
            actionText="录入流水"
            onAction={() => router.push("/transactions/new")}
          />
        </Card>
      ) : (
        <Card style={{ borderRadius: 16 }}>
          <div className="space-y-3">
            {data.map((tx, index) => {
              const typeConfig = typeColors[tx.type] || typeColors[2];
              return (
                <div
                  key={tx.id}
                  className="transaction-item animate-fade-in"
                  style={{
                    animationDelay: `${index * 50}ms`,
                    backgroundColor: "var(--bg-color-card)",
                    border: "1px solid var(--border-color-light)",
                    borderRadius: 12,
                  }}
                >
                  <div className="flex items-center gap-4">
                    <div
                      className="w-12 h-12 rounded-xl flex items-center justify-center text-xl"
                      style={{ backgroundColor: typeConfig.bg }}
                    >
                      {tx.categoryIcon || (tx.type === 1 ? "💰" : tx.type === 3 ? "↩️" : "💸")}
                    </div>
                    <div>
                      <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                        {tx.note || tx.categoryName || "未命名"}
                      </div>
                      <div className="flex items-center gap-2 mt-1">
                        <span
                          className="text-xs px-2 py-0.5 rounded-full"
                          style={{ backgroundColor: typeConfig.bg, color: typeConfig.color }}
                        >
                          {typeConfig.label}
                        </span>
                        <span className="text-xs" style={{ color: "var(--text-color-4)" }}>
                          {tx.categoryName}
                        </span>
                        <span className="text-xs" style={{ color: "var(--text-color-4)" }}>
                          ·
                        </span>
                        <span className="text-xs" style={{ color: "var(--text-color-4)" }}>
                          {formatDate(tx.date)}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-3">
                    <div className="text-right">
                      <AmountDisplay amount={tx.amount} type={tx.type} showSign size="medium" />
                      {tx.type === 2 && tx.isRefundable && tx.refundedAmount > 0 && (
                        <div className="text-xs mt-1" style={{ color: "var(--color-warning)" }}>
                          已退 {formatAmount(tx.refundedAmount)}
                        </div>
                      )}
                    </div>

                    <div className="flex gap-1">
                      {tx.type !== 3 && (
                        <Button
                          type="text"
                          size="mini"
                          icon={<IconEdit />}
                          onClick={() => router.push(`/transactions/new?edit=${tx.id}`)}
                          style={{ color: "var(--text-color-3)" }}
                        />
                      )}
                      {tx.type === 2 && tx.isRefundable && (
                        <Button
                          type="text"
                          size="mini"
                          icon={<IconRefresh />}
                          onClick={() => router.push(`/transactions/new?refund=${tx.id}`)}
                          style={{ color: "var(--color-warning)" }}
                        />
                      )}
                      {tx.type !== 3 && (
                        <Button
                          type="text"
                          size="mini"
                          status="danger"
                          icon={<IconDelete />}
                          onClick={() => handleDelete(tx.id)}
                        />
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Pagination */}
          {total > 20 && (
            <div className="flex justify-center mt-6">
              <Space>
                <Button
                  disabled={query.page === 0}
                  onClick={() => setQuery((prev) => ({ ...prev, page: (prev.page || 0) - 1 }))}
                >
                  上一页
                </Button>
                <span className="px-4 py-2 text-sm" style={{ color: "var(--text-color-3)" }}>
                  第 {(query.page || 0) + 1} 页 / 共 {Math.ceil(total / (query.size || 20))} 页
                </span>
                <Button
                  disabled={(query.page || 0) + 1 >= Math.ceil(total / (query.size || 20))}
                  onClick={() => setQuery((prev) => ({ ...prev, page: (prev.page || 0) + 1 }))}
                >
                  下一页
                </Button>
              </Space>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
