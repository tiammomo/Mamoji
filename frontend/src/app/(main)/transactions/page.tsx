"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, Card, DatePicker, Drawer, Input, Message, Modal, Select, Tag } from "@arco-design/web-react";
import {
  IconDelete,
  IconEdit,
  IconEmpty,
  IconEye,
  IconFile,
  IconPlus,
  IconRefresh,
  IconSafe,
  IconSearch,
  IconSwap,
} from "@arco-design/web-react/icon";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { accountApi } from "@/lib/api/accounts";
import { transactionApi } from "@/lib/api/transactions";
import { useAppStore } from "@/lib/stores/appStore";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import EmptyState from "@/components/common/EmptyState";
import AppPagination from "@/components/common/AppPagination";
import TransactionFormModal, { type TransactionFormMode } from "@/components/transactions/TransactionFormModal";
import { formatAmount, formatDate, formatDateTime } from "@/lib/utils/format";
import type { Account, Transaction, TransactionQuery, TransactionType } from "@/lib/types";

type LedgerView = "all" | "income" | "expense" | "large";

const largeTransactionThreshold = 10000;

const normalizeTypeFilter = (value: string | null) => (
  value === "1" || value === "2" || value === "3" ? value : "all"
);

const normalizeLedgerView = (value: string | null): LedgerView => {
  if (value === "income" || value === "expense" || value === "large") return value;
  return "all";
};

const normalizeIdFilter = (value: string | null) => (
  value && Number.isFinite(Number(value)) && Number(value) > 0 ? value : "all"
);

const normalizeAmountFilter = (value: string | null) => (
  value && Number.isFinite(Number(value)) && Number(value) >= 0 ? value : ""
);

const toNumber = (value: string) => (value ? Number(value) : undefined);

export default function TransactionsPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const t = useTranslations("transaction");
  const activeSubjectType = useAppStore((state) => state.activeSubjectType);
  const isHousehold = activeSubjectType === "household";
  const { categories, fetchCategories } = useCategoryStore();
  const initialKeyword = searchParams.get("keyword") || "";
  const initialType = normalizeTypeFilter(searchParams.get("type"));
  const initialStartDate = searchParams.get("startDate") || "";
  const initialEndDate = searchParams.get("endDate") || "";
  const initialCategory = normalizeIdFilter(searchParams.get("categoryId"));
  const initialAccount = normalizeIdFilter(searchParams.get("accountId"));
  const initialMinAmount = normalizeAmountFilter(searchParams.get("minAmount"));
  const initialMaxAmount = normalizeAmountFilter(searchParams.get("maxAmount"));
  const initialView = normalizeLedgerView(searchParams.get("view"));
  const appliedKeyword = searchParams.get("keyword") || "";
  const appliedTypeFilter = normalizeTypeFilter(searchParams.get("type"));
  const appliedStartDate = searchParams.get("startDate") || "";
  const appliedEndDate = searchParams.get("endDate") || "";
  const appliedCategory = normalizeIdFilter(searchParams.get("categoryId"));
  const appliedAccount = normalizeIdFilter(searchParams.get("accountId"));
  const appliedMinAmount = normalizeAmountFilter(searchParams.get("minAmount"));
  const appliedMaxAmount = normalizeAmountFilter(searchParams.get("maxAmount"));
  const appliedView = normalizeLedgerView(searchParams.get("view"));
  const appliedType = appliedTypeFilter === "all" ? undefined : Number(appliedTypeFilter) as TransactionType;
  const viewType = appliedView === "income" ? 1 : appliedView === "expense" ? 2 : undefined;
  const effectiveType = appliedType || viewType as TransactionType | undefined;
  const effectiveMinAmount = appliedView === "large"
    ? Math.max(toNumber(appliedMinAmount) || 0, largeTransactionThreshold)
    : toNumber(appliedMinAmount);

  const [data, setData] = useState<Transaction[]>([]);
  const [summaryData, setSummaryData] = useState<Transaction[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [total, setTotal] = useState(0);
  const [initialLoading, setInitialLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [search, setSearch] = useState(initialKeyword);
  const [typeFilter, setTypeFilter] = useState<string>(initialType);
  const [categoryFilter, setCategoryFilter] = useState<string>(initialCategory);
  const [accountFilter, setAccountFilter] = useState<string>(initialAccount);
  const [startDate, setStartDate] = useState(initialStartDate);
  const [endDate, setEndDate] = useState(initialEndDate);
  const [minAmount, setMinAmount] = useState(initialMinAmount);
  const [maxAmount, setMaxAmount] = useState(initialMaxAmount);
  const [viewFilter, setViewFilter] = useState<LedgerView>(initialView);
  const [formVisible, setFormVisible] = useState(false);
  const [formMode, setFormMode] = useState<TransactionFormMode>("create");
  const [activeTransactionId, setActiveTransactionId] = useState<number | null>(null);
  const [selectedTransaction, setSelectedTransaction] = useState<Transaction | null>(null);
  const hasLoadedRef = useRef(false);

  const query = useMemo<TransactionQuery>(() => ({
    page: pageIndex,
    size: pageSize,
    keyword: appliedKeyword || undefined,
    type: effectiveType,
    categoryId: appliedCategory === "all" ? undefined : Number(appliedCategory),
    accountId: appliedAccount === "all" ? undefined : Number(appliedAccount),
    startDate: appliedStartDate || undefined,
    endDate: appliedEndDate || undefined,
    minAmount: effectiveMinAmount,
    maxAmount: toNumber(appliedMaxAmount),
  }), [
    appliedAccount,
    appliedCategory,
    appliedEndDate,
    appliedKeyword,
    appliedMaxAmount,
    appliedStartDate,
    effectiveMinAmount,
    effectiveType,
    pageIndex,
    pageSize,
  ]);

  const summaryQuery = useMemo<TransactionQuery>(() => ({
    ...query,
    page: 0,
    size: 1000,
  }), [query]);

  const fetchData = useCallback(async () => {
    const isInitial = !hasLoadedRef.current;
    if (isInitial) {
      setInitialLoading(true);
    } else {
      setRefreshing(true);
    }
    try {
      const [listRes, summaryRes] = await Promise.all([
        transactionApi.list(query),
        transactionApi.list(summaryQuery),
      ]);
      setData(listRes.data.content);
      setTotal(listRes.data.totalElements);
      setSummaryData(summaryRes.data.content);
      hasLoadedRef.current = true;
    } catch {
      Message.error("经营流水加载失败");
    } finally {
      setInitialLoading(false);
      setRefreshing(false);
    }
  }, [query, summaryQuery]);

  useEffect(() => {
    let cancelled = false;

    const loadTransactions = async () => {
      const isInitial = !hasLoadedRef.current;
      if (isInitial) {
        setInitialLoading(true);
      } else {
        setRefreshing(true);
      }
      try {
        const [listRes, summaryRes] = await Promise.all([
          transactionApi.list(query),
          transactionApi.list(summaryQuery),
        ]);
        if (cancelled) return;
        setData(listRes.data.content);
        setTotal(listRes.data.totalElements);
        setSummaryData(summaryRes.data.content);
        hasLoadedRef.current = true;
      } catch {
        // silent
      } finally {
        if (!cancelled) {
          setInitialLoading(false);
          setRefreshing(false);
        }
      }
    };

    fetchCategories();
    void loadTransactions();

    return () => {
      cancelled = true;
    };
  }, [fetchCategories, query, summaryQuery]);

  useEffect(() => {
    let cancelled = false;

    const loadAccounts = async () => {
      try {
        const res = await accountApi.list();
        if (!cancelled) {
          setAccounts(res.data);
        }
      } catch {
        // silent
      }
    };

    void loadAccounts();

    return () => {
      cancelled = true;
    };
  }, []);

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
      setCategoryFilter(appliedCategory);
      setAccountFilter(appliedAccount);
      setStartDate(appliedStartDate);
      setEndDate(appliedEndDate);
      setMinAmount(appliedMinAmount);
      setMaxAmount(appliedMaxAmount);
      setViewFilter(appliedView);
      setPageIndex(0);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [
    appliedAccount,
    appliedCategory,
    appliedEndDate,
    appliedKeyword,
    appliedMaxAmount,
    appliedMinAmount,
    appliedStartDate,
    appliedTypeFilter,
    appliedView,
  ]);

  const summary = useMemo(() => {
    const income = summaryData.filter((tx) => tx.type === 1).reduce((sum, tx) => sum + Number(tx.amount || 0), 0);
    const expense = summaryData.filter((tx) => tx.type === 2).reduce((sum, tx) => sum + Number(tx.amount || 0), 0);
    const refund = summaryData.filter((tx) => tx.type === 3).reduce((sum, tx) => sum + Number(tx.amount || 0), 0);
    const largeCount = summaryData.filter((tx) => Number(tx.amount || 0) >= largeTransactionThreshold).length;
    const reviewIds = new Set<number>();
    summaryData.forEach((tx) => {
      if (Number(tx.amount || 0) >= largeTransactionThreshold || (tx.type === 2 && tx.isRefundable) || !tx.note?.trim()) {
        reviewIds.add(tx.id);
      }
    });

    return {
      income,
      expense,
      refund,
      net: income + refund - expense,
      rows: summaryData.length,
      largeCount,
      reviewCount: reviewIds.size,
    };
  }, [summaryData]);

  const typeColors = useMemo<Record<number, { color: string; bg: string; label: string }>>(() => ({
    1: { color: "#10b981", bg: "#10b98120", label: t("income") },
    2: { color: "#ef4444", bg: "#ef444420", label: t("expense") },
    3: { color: "#f59e0b", bg: "#f59e0b20", label: t("refund") },
  }), [t]);

  const viewOptions: Array<{ key: LedgerView; label: string }> = [
    { key: "all", label: t("viewAll") },
    { key: "income", label: t("viewIncome") },
    { key: "expense", label: t("viewExpense") },
    { key: "large", label: t("viewLarge") },
  ];

  const validateFilters = () => {
    if (startDate && endDate && startDate > endDate) {
      Message.warning("起始日期不能晚于结束日期");
      return false;
    }
    if (minAmount && maxAmount && Number(minAmount) > Number(maxAmount)) {
      Message.warning("最小金额不能大于最大金额");
      return false;
    }
    return true;
  };

  const applyFilters = (nextView = viewFilter) => {
    if (!validateFilters()) return;

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
    if (categoryFilter === "all") {
      params.delete("categoryId");
    } else {
      params.set("categoryId", categoryFilter);
    }
    if (accountFilter === "all") {
      params.delete("accountId");
    } else {
      params.set("accountId", accountFilter);
    }
    if (startDate) {
      params.set("startDate", startDate);
    } else {
      params.delete("startDate");
    }
    if (endDate) {
      params.set("endDate", endDate);
    } else {
      params.delete("endDate");
    }
    if (minAmount) {
      params.set("minAmount", minAmount);
    } else {
      params.delete("minAmount");
    }
    if (maxAmount) {
      params.set("maxAmount", maxAmount);
    } else {
      params.delete("maxAmount");
    }
    if (nextView === "all") {
      params.delete("view");
    } else {
      params.set("view", nextView);
    }
    setPageIndex(0);
    router.replace(`/transactions${params.toString() ? `?${params.toString()}` : ""}`, { scroll: false });
  };

  const handleQuickView = (nextView: LedgerView) => {
    setViewFilter(nextView);
    applyFilters(nextView);
  };

  const handleReset = () => {
    setSearch("");
    setTypeFilter("all");
    setCategoryFilter("all");
    setAccountFilter("all");
    setStartDate("");
    setEndDate("");
    setMinAmount("");
    setMaxAmount("");
    setViewFilter("all");
    setPageIndex(0);
    router.replace("/transactions", { scroll: false });
  };

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
    void fetchData();
  };

  const handlePageChange = (page: number, size: number) => {
    setPageSize(size);
    setPageIndex(page - 1);
  };

  const handleDelete = (id: number, closeDrawer = false) => {
    Modal.confirm({
      title: "确认删除",
      content: "确定要删除这笔交易吗？",
      onOk: async () => {
        try {
          await transactionApi.delete(id);
          Message.success("删除成功");
          if (closeDrawer) {
            setSelectedTransaction(null);
          }
          await fetchData();
        } catch {
          Message.error("删除失败");
        }
      },
    });
  };

  const renderTransactionIcon = (type: number) => {
    if (type === 1) return <IconSafe />;
    if (type === 3) return <IconRefresh />;
    return <IconSwap />;
  };

  const transactionFlags = (tx: Transaction) => {
    const flags: Array<{ key: string; label: string; color: string }> = [];
    if (Number(tx.amount || 0) >= largeTransactionThreshold) {
      flags.push({ key: "large", label: t("largeTransaction"), color: "red" });
    }
    if (tx.type === 2 && tx.isRefundable) {
      flags.push({ key: "refundable", label: t("refundableTag"), color: "orange" });
    }
    if (tx.refundedAmount > 0) {
      flags.push({ key: "refunded", label: t("refundedTag"), color: "gold" });
    }
    if (!tx.note?.trim()) {
      flags.push({ key: "missing-note", label: t("missingNote"), color: "gray" });
    }
    return flags;
  };

  const receiptStatusFor = (tx: Transaction) => {
    if (tx.type === 2) return { label: t("receiptPending"), color: "orange" };
    if (tx.type === 3) return { label: t("receiptRefund"), color: "purple" };
    return { label: t("receiptIncome"), color: "arcoblue" };
  };

  const taxStatusFor = (tx: Transaction) => {
    if (tx.type === 2) return { label: t("taxExpense"), color: "gold" };
    if (tx.type === 3) return { label: t("taxRefund"), color: "purple" };
    return { label: t("taxIncome"), color: "green" };
  };

  const renderActions = (tx: Transaction) => (
    <div className="flex items-center justify-end gap-1">
      <Button
        type="text"
        size="mini"
        icon={<IconEye />}
        onClick={(event) => {
          event.stopPropagation();
          setSelectedTransaction(tx);
        }}
        style={{ color: "var(--text-color-3)" }}
      />
      {tx.type !== 3 && (
        <Button
          type="text"
          size="mini"
          icon={<IconEdit />}
          onClick={(event) => {
            event.stopPropagation();
            openForm("edit", tx.id);
          }}
          style={{ color: "var(--text-color-3)" }}
        />
      )}
      {tx.type === 2 && tx.isRefundable && (
        <Button
          type="text"
          size="mini"
          icon={<IconRefresh />}
          onClick={(event) => {
            event.stopPropagation();
            openForm("refund", tx.id);
          }}
          style={{ color: "var(--color-warning)" }}
        />
      )}
      {tx.type !== 3 && (
        <Button
          type="text"
          size="mini"
          status="danger"
          icon={<IconDelete />}
          onClick={(event) => {
            event.stopPropagation();
            handleDelete(tx.id);
          }}
        />
      )}
    </div>
  );

  const summaryCards = [
    { label: t("summaryIncome"), value: <AmountDisplay amount={summary.income} type={1} showSign size="medium" /> },
    { label: t("summaryExpense"), value: <AmountDisplay amount={summary.expense} type={2} showSign size="medium" /> },
    {
      label: t("summaryNet"),
      value: <AmountDisplay amount={Math.abs(summary.net)} type={summary.net >= 0 ? 1 : 2} showSign size="medium" />,
    },
    { label: t("summaryRows"), value: <span className="text-lg font-semibold">{summary.rows}</span> },
    { label: t("summaryLarge"), value: <span className="text-lg font-semibold text-red-500">{summary.largeCount}</span> },
    { label: t("summaryReview"), value: <span className="text-lg font-semibold" style={{ color: "var(--color-warning)" }}>{summary.reviewCount}</span> },
  ];

  const renderFlagTags = (tx: Transaction) => {
    const flags = transactionFlags(tx);
    if (flags.length === 0) {
      return <Tag color="gray">{t("normalTag")}</Tag>;
    }
    return flags.map((flag) => (
      <Tag key={flag.key} color={flag.color} size="small">
        {flag.label}
      </Tag>
    ));
  };

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title={isHousehold ? "家庭收支" : t("title")}
        subtitle={isHousehold ? "家庭收入、支出、退款、账户与预算的生活台账" : t("ledgerSubtitle")}
        icon={<IconSwap />}
        extra={
          <div className="flex items-center gap-2">
            {refreshing && <Tag color="arcoblue">刷新中</Tag>}
            <Button
              type="primary"
              icon={<IconPlus />}
              onClick={() => openForm("create")}
            >
              {isHousehold ? "记一笔" : t("new")}
            </Button>
          </div>
        }
      />

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-6">
        {summaryCards.map((item) => (
          <Card key={item.label} loading={initialLoading} style={{ borderRadius: 12 }}>
            <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{item.label}</div>
            <div className="mt-2">{item.value}</div>
          </Card>
        ))}
      </div>

      <Card className="mb-4" style={{ borderRadius: 12 }}>
        <div className="mb-3 flex flex-wrap gap-2">
          {viewOptions.map((item) => {
            const active = viewFilter === item.key;
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => handleQuickView(item.key)}
                className="h-8 cursor-pointer rounded-lg border px-3 text-sm transition-colors"
                style={{
                  borderColor: active ? "rgba(99, 102, 241, 0.45)" : "var(--border-color)",
                  backgroundColor: active ? "rgba(99, 102, 241, 0.12)" : "var(--bg-color-card)",
                  color: active ? "var(--color-primary-dark)" : "var(--text-color-2)",
                }}
              >
                {item.label}
              </button>
            );
          })}
        </div>

        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[minmax(280px,1fr)_150px_170px_180px]">
          <div className="w-full">
            <Input
              prefix={<IconSearch style={{ color: "var(--text-color-4)" }} />}
              placeholder={t("searchPlaceholder")}
              value={search}
              onChange={setSearch}
              onPressEnter={() => applyFilters()}
              className="w-full"
              style={{ borderRadius: 12 }}
            />
          </div>
          <div className="w-full">
            <Select value={typeFilter} onChange={setTypeFilter} style={{ width: "100%", borderRadius: 12 }}>
              <Select.Option value="all">{t("allTypes")}</Select.Option>
              <Select.Option value="1">{t("income")}</Select.Option>
              <Select.Option value="2">{t("expense")}</Select.Option>
              <Select.Option value="3">{t("refund")}</Select.Option>
            </Select>
          </div>
          <div className="w-full">
            <Select value={categoryFilter} onChange={setCategoryFilter} style={{ width: "100%", borderRadius: 12 }}>
              <Select.Option value="all">{t("allCategories")}</Select.Option>
              {categories.map((category) => (
                <Select.Option key={category.id} value={String(category.id)}>
                  {category.name}
                </Select.Option>
              ))}
            </Select>
          </div>
          <div className="w-full">
            <Select value={accountFilter} onChange={setAccountFilter} style={{ width: "100%", borderRadius: 12 }}>
              <Select.Option value="all">{t("allAccounts")}</Select.Option>
              {accounts.map((account) => (
                <Select.Option key={account.id} value={String(account.id)}>
                  {account.name}
                </Select.Option>
              ))}
            </Select>
          </div>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-3">
          <div className="grid w-full grid-cols-[minmax(0,1fr)_20px_minmax(0,1fr)] items-center md:w-[356px]">
            <DatePicker
              format="YYYY-MM-DD"
              value={startDate}
              onChange={(value) => setStartDate(value || "")}
              placeholder={t("startDate")}
              className="w-full"
              style={{ borderRadius: 12 }}
            />
            <span className="text-center text-sm" style={{ color: "var(--text-color-4)" }}>-</span>
            <DatePicker
              format="YYYY-MM-DD"
              value={endDate}
              onChange={(value) => setEndDate(value || "")}
              placeholder={t("endDate")}
              className="w-full"
              style={{ borderRadius: 12 }}
            />
          </div>
          <div className="grid w-full grid-cols-[minmax(0,1fr)_20px_minmax(0,1fr)] items-center md:w-[300px]">
            <Input
              type="number"
              value={minAmount}
              onChange={setMinAmount}
              placeholder={t("minAmount")}
              style={{ borderRadius: 12 }}
            />
            <span className="text-center text-sm" style={{ color: "var(--text-color-4)" }}>-</span>
            <Input
              type="number"
              value={maxAmount}
              onChange={setMaxAmount}
              placeholder={t("maxAmount")}
              style={{ borderRadius: 12 }}
            />
          </div>
          <Button type="primary" loading={refreshing} className="w-full md:w-[110px]" onClick={() => applyFilters()}>
            {t("search")}
          </Button>
          <Button className="w-full md:w-[110px]" onClick={handleReset}>
            {t("reset")}
          </Button>
        </div>
      </Card>

      {data.length === 0 && !initialLoading ? (
        <Card style={{ borderRadius: 12 }}>
          <EmptyState
            icon={<IconEmpty style={{ fontSize: 56, color: "var(--text-color-4)" }} />}
            title={isHousehold ? "暂无家庭收支" : "暂无经营流水"}
            description={isHousehold ? "点击上方按钮记录第一笔家庭收入或支出" : "点击上方按钮录入第一笔收入或成本"}
            actionText={isHousehold ? "记一笔" : "录入流水"}
            onAction={() => openForm("create")}
          />
        </Card>
      ) : (
        <Card
          title={
            <div className="flex items-center gap-2">
              <IconFile />
              <span>{t("ledgerTable")}</span>
              <Tag color="arcoblue">{total}</Tag>
              {refreshing && <Tag color="gray">刷新中</Tag>}
            </div>
          }
          loading={initialLoading}
          style={{ borderRadius: 12 }}
        >
          <div className="hidden overflow-x-auto md:block">
            <table className="w-full min-w-[1040px] table-fixed border-collapse text-sm">
              <colgroup>
                <col style={{ width: 104 }} />
                <col style={{ width: 230 }} />
                <col style={{ width: 118 }} />
                <col style={{ width: 116 }} />
                <col style={{ width: 140 }} />
                <col style={{ width: 96 }} />
                <col style={{ width: 96 }} />
                <col style={{ width: 104 }} />
                <col style={{ width: 92 }} />
              </colgroup>
              <thead>
                <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                  {[
                    t("date"),
                    t("descriptionColumn"),
                    t("category"),
                    t("account"),
                    t("amount"),
                    t("receipt"),
                    t("tax"),
                    t("tags"),
                    t("actions"),
                  ].map((column) => (
                    <th key={column} className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>
                      {column}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.map((tx) => {
                  const typeConfig = typeColors[tx.type] || typeColors[2];
                  const receipt = receiptStatusFor(tx);
                  const tax = taxStatusFor(tx);
                  return (
                    <tr
                      key={tx.id}
                      onClick={() => setSelectedTransaction(tx)}
                      className="cursor-pointer border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                      style={{ borderColor: "var(--border-color-light)" }}
                    >
                      <td className="px-4 py-4 align-middle">
                        <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{formatDate(tx.date)}</div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>#{tx.id}</div>
                      </td>
                      <td className="px-4 py-4 align-middle">
                        <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{tx.note || tx.categoryName || "未命名"}</div>
                        <div className="mt-2 flex flex-wrap items-center gap-2">
                          <Tag color={tx.type === 1 ? "green" : tx.type === 2 ? "red" : "orange"}>{typeConfig.label}</Tag>
                          {Number(tx.amount || 0) >= largeTransactionThreshold && (
                            <Tag color="red" title={t("largeTransactionHint", { amount: formatAmount(largeTransactionThreshold) })}>
                              {t("largeTransaction")}
                            </Tag>
                          )}
                        </div>
                      </td>
                      <td className="px-4 py-4 align-middle" style={{ color: "var(--text-color-2)" }}>{tx.categoryName || "--"}</td>
                      <td className="px-4 py-4 align-middle" style={{ color: "var(--text-color-2)" }}>{tx.accountName || "--"}</td>
                      <td className="whitespace-nowrap px-4 py-4 align-middle text-right">
                        <AmountDisplay amount={tx.amount} type={tx.type} showSign size="medium" />
                        {tx.type === 2 && tx.refundedAmount > 0 && (
                          <div className="mt-1 text-xs" style={{ color: "var(--color-warning)" }}>
                            已退 {formatAmount(tx.refundedAmount)}
                          </div>
                        )}
                      </td>
                      <td className="px-4 py-4 align-middle"><Tag color={receipt.color}>{receipt.label}</Tag></td>
                      <td className="px-4 py-4 align-middle"><Tag color={tax.color}>{tax.label}</Tag></td>
                      <td className="px-4 py-4 align-middle">
                        <div className="flex flex-wrap gap-1">{renderFlagTags(tx)}</div>
                      </td>
                      <td className="px-4 py-4 align-middle">{renderActions(tx)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div className="space-y-3 md:hidden">
            {data.map((tx) => {
              const typeConfig = typeColors[tx.type] || typeColors[2];
              return (
                <button
                  key={tx.id}
                  type="button"
                  onClick={() => setSelectedTransaction(tx)}
                  className="transaction-item w-full cursor-pointer text-left"
                  style={{
                    backgroundColor: "var(--bg-color-card)",
                    border: "1px solid var(--border-color-light)",
                    borderRadius: 12,
                  }}
                >
                  <div className="flex items-center gap-4">
                    <div
                      className="flex h-12 w-12 items-center justify-center rounded-xl text-xl"
                      style={{ backgroundColor: typeConfig.bg }}
                    >
                      {renderTransactionIcon(tx.type)}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-medium" style={{ color: "var(--text-color-1)" }}>
                        {tx.note || tx.categoryName || "未命名"}
                      </div>
                      <div className="mt-1 flex flex-wrap items-center gap-2">
                        <Tag color={tx.type === 1 ? "green" : tx.type === 2 ? "red" : "orange"}>{typeConfig.label}</Tag>
                        {renderFlagTags(tx)}
                        <span className="text-xs" style={{ color: "var(--text-color-4)" }}>
                          {formatDate(tx.date)}
                        </span>
                      </div>
                    </div>
                    <AmountDisplay amount={tx.amount} type={tx.type} showSign size="medium" />
                  </div>
                </button>
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

      <Drawer
        title={t("detailTitle")}
        visible={!!selectedTransaction}
        width={440}
        footer={null}
        onCancel={() => setSelectedTransaction(null)}
      >
        {selectedTransaction && (
          <div className="space-y-5">
            <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color)", backgroundColor: "var(--bg-color-page)" }}>
              <div className="text-sm" style={{ color: "var(--text-color-3)" }}>{selectedTransaction.note || selectedTransaction.categoryName || "未命名"}</div>
              <div className="mt-2">
                <AmountDisplay amount={selectedTransaction.amount} type={selectedTransaction.type} showSign size="large" />
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <Tag color={selectedTransaction.type === 1 ? "green" : selectedTransaction.type === 2 ? "red" : "orange"}>
                  {(typeColors[selectedTransaction.type] || typeColors[2]).label}
                </Tag>
                {renderFlagTags(selectedTransaction)}
              </div>
            </div>

            <div>
              <div className="mb-3 text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{t("basicInfo")}</div>
              <div className="space-y-3 text-sm">
                {[
                  [t("transactionId"), `#${selectedTransaction.id}`],
                  [t("date"), formatDate(selectedTransaction.date)],
                  [t("category"), selectedTransaction.categoryName || "--"],
                  [t("account"), selectedTransaction.accountName || "--"],
                ].map(([label, value]) => (
                  <div key={label} className="flex items-center justify-between gap-4">
                    <span style={{ color: "var(--text-color-3)" }}>{label}</span>
                    <span className="text-right" style={{ color: "var(--text-color-1)" }}>{value}</span>
                  </div>
                ))}
              </div>
            </div>

            <div>
              <div className="mb-3 text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{t("complianceInfo")}</div>
              <div className="grid grid-cols-2 gap-3">
                <div className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{t("receipt")}</div>
                  <Tag className="mt-2" color={receiptStatusFor(selectedTransaction).color}>{receiptStatusFor(selectedTransaction).label}</Tag>
                </div>
                <div className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{t("tax")}</div>
                  <Tag className="mt-2" color={taxStatusFor(selectedTransaction).color}>{taxStatusFor(selectedTransaction).label}</Tag>
                </div>
              </div>
            </div>

            <div>
              <div className="mb-3 text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{t("linkedInfo")}</div>
              <div className="space-y-3 text-sm">
                <div className="flex items-center justify-between gap-4">
                  <span style={{ color: "var(--text-color-3)" }}>{t("budget")}</span>
                  <span style={{ color: "var(--text-color-1)" }}>{selectedTransaction.budgetId ? t("linked") : t("unlinked")}</span>
                </div>
                <div className="flex items-center justify-between gap-4">
                  <span style={{ color: "var(--text-color-3)" }}>{t("originalTransaction")}</span>
                  <span style={{ color: "var(--text-color-1)" }}>{selectedTransaction.originalTransactionId ? `#${selectedTransaction.originalTransactionId}` : "--"}</span>
                </div>
                <div className="flex items-center justify-between gap-4">
                  <span style={{ color: "var(--text-color-3)" }}>{t("createdAt")}</span>
                  <span className="text-right" style={{ color: "var(--text-color-1)" }}>{formatDateTime(selectedTransaction.createdAt)}</span>
                </div>
                <div className="flex items-center justify-between gap-4">
                  <span style={{ color: "var(--text-color-3)" }}>{t("updatedAt")}</span>
                  <span className="text-right" style={{ color: "var(--text-color-1)" }}>{formatDateTime(selectedTransaction.updatedAt)}</span>
                </div>
              </div>
            </div>

            <div className="flex gap-2">
              {selectedTransaction.type !== 3 && (
                <Button
                  type="primary"
                  icon={<IconEdit />}
                  onClick={() => openForm("edit", selectedTransaction.id)}
                >
                  {t("editAction")}
                </Button>
              )}
              {selectedTransaction.type === 2 && selectedTransaction.isRefundable && (
                <Button
                  icon={<IconRefresh />}
                  onClick={() => openForm("refund", selectedTransaction.id)}
                >
                  {t("refundAction")}
                </Button>
              )}
              {selectedTransaction.type !== 3 && (
                <Button
                  status="danger"
                  icon={<IconDelete />}
                  onClick={() => handleDelete(selectedTransaction.id, true)}
                >
                  {t("deleteAction")}
                </Button>
              )}
            </div>
          </div>
        )}
      </Drawer>

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
