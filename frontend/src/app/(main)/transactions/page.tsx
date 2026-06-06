"use client";
import { useEffect, useState, useCallback, useMemo } from "react";
import { Card, Button, Space, Input, Select, Modal, Message } from "@arco-design/web-react";
import { IconPlus, IconSearch, IconDelete, IconEdit, IconRefresh, IconSafe, IconSwap, IconEmpty } from "@arco-design/web-react/icon";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { transactionApi } from "@/lib/api/transactions";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import EmptyState from "@/components/common/EmptyState";
import AppPagination from "@/components/common/AppPagination";
import TransactionFormModal, { type TransactionFormMode } from "@/components/transactions/TransactionFormModal";
import { formatAmount, formatDate } from "@/lib/utils/format";
import type { Transaction, TransactionQuery } from "@/lib/types";

const normalizeTypeFilter = (value: string | null) => (
  value === "1" || value === "2" || value === "3" ? value : "all"
);

export default function TransactionsPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const t = useTranslations("transaction");
  const { fetchCategories } = useCategoryStore();
  const initialKeyword = searchParams.get("keyword") || "";
  const initialType = normalizeTypeFilter(searchParams.get("type"));
  const appliedKeyword = searchParams.get("keyword") || "";
  const appliedTypeFilter = normalizeTypeFilter(searchParams.get("type"));
  const appliedType = appliedTypeFilter === "all" ? undefined : Number(appliedTypeFilter) as 1 | 2 | 3;
  const [data, setData] = useState<Transaction[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [search, setSearch] = useState(initialKeyword);
  const [typeFilter, setTypeFilter] = useState<string>(initialType);
  const [formVisible, setFormVisible] = useState(false);
  const [formMode, setFormMode] = useState<TransactionFormMode>("create");
  const [activeTransactionId, setActiveTransactionId] = useState<number | null>(null);

  const query = useMemo<TransactionQuery>(() => ({
    page: pageIndex,
    size: pageSize,
    keyword: appliedKeyword || undefined,
    type: appliedType,
  }), [appliedKeyword, appliedType, pageIndex, pageSize]);

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

  useEffect(() => {
    if (searchParams.get("action") !== "new") return;
    const timer = window.setTimeout(() => {
      setFormMode("create");
      setActiveTransactionId(null);
      setFormVisible(true);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [searchParams]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setSearch(appliedKeyword);
      setTypeFilter(appliedTypeFilter);
      setPageIndex(0);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [appliedKeyword, appliedTypeFilter]);

  const openForm = (mode: TransactionFormMode, transactionId?: number) => {
    setFormMode(mode);
    setActiveTransactionId(transactionId || null);
    setFormVisible(true);
  };

  const closeForm = () => {
    setFormVisible(false);
    setActiveTransactionId(null);
    if (searchParams.get("action")) {
      router.replace("/transactions", { scroll: false });
    }
  };

  const refreshData = () => {
    setLoading(true);
    void fetchData();
  };

  const handlePageChange = (page: number, size: number) => {
    setLoading(true);
    setPageSize(size);
    setPageIndex(page - 1);
  };

  const handleSearch = () => {
    setLoading(true);
    const keyword = search.trim();
    const params = new URLSearchParams(searchParams.toString());
    params.delete("action");
    if (keyword) {
      params.set("keyword", keyword);
    } else {
      params.delete("keyword");
    }
    if (typeFilter === "all") {
      params.delete("type");
    } else {
      params.set("type", typeFilter);
    }
    setPageIndex(0);
    router.replace(`/transactions${params.toString() ? `?${params.toString()}` : ""}`, { scroll: false });
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

  const renderTransactionIcon = (type: number) => {
    if (type === 1) return <IconSafe />;
    if (type === 3) return <IconRefresh />;
    return <IconSwap />;
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title={t("title")}
        icon={<IconSwap />}
        extra={
          <Button
            type="primary"
            icon={<IconPlus />}
            onClick={() => openForm("create")}
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
            placeholder={t("searchPlaceholder")}
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
            <Select.Option value="all">{t("allTypes")}</Select.Option>
            <Select.Option value="1">{t("income")}</Select.Option>
            <Select.Option value="2">{t("expense")}</Select.Option>
            <Select.Option value="3">{t("refund")}</Select.Option>
          </Select>
          <Button type="primary" onClick={handleSearch}>
            {t("search")}
          </Button>
        </Space>
      </Card>

      {/* Transaction list */}
      {data.length === 0 && !loading ? (
        <Card style={{ borderRadius: 16 }}>
          <EmptyState
            icon={<IconEmpty style={{ fontSize: 56, color: "var(--text-color-4)" }} />}
            title="暂无经营流水"
            description="点击上方按钮录入第一笔收入或成本"
            actionText="录入流水"
            onAction={() => openForm("create")}
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
                      {renderTransactionIcon(tx.type)}
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
                          onClick={() => openForm("edit", tx.id)}
                          style={{ color: "var(--text-color-3)" }}
                        />
                      )}
                      {tx.type === 2 && tx.isRefundable && (
                        <Button
                          type="text"
                          size="mini"
                          icon={<IconRefresh />}
                          onClick={() => openForm("refund", tx.id)}
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

          <AppPagination
            current={pageIndex + 1}
            pageSize={pageSize}
            total={total}
            pageSizeOptions={[10, 20, 50, 100]}
            onChange={handlePageChange}
          />
        </Card>
      )}

      <TransactionFormModal
        visible={formVisible}
        mode={formMode}
        transactionId={activeTransactionId}
        onClose={closeForm}
        onSuccess={refreshData}
      />
    </div>
  );
}
