"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, Card, DatePicker, Drawer, Input, Message, Modal, Select, Tag } from "@arco-design/web-react";
import {
  IconDelete,
  IconEdit,
  IconEmpty,
  IconExclamationCircle,
  IconEye,
  IconFile,
  IconPlus,
  IconRefresh,
  IconSafe,
  IconSearch,
  IconSwap,
  IconUpload,
} from "@arco-design/web-react/icon";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { accountApi } from "@/lib/api/accounts";
import { transactionApi } from "@/lib/api/transactions";
import type { TransactionSummary } from "@/lib/api/transactions";
import { useAppStore } from "@/lib/stores/appStore";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import EmptyState from "@/components/common/EmptyState";
import AppPagination from "@/components/common/AppPagination";
import TransactionFormModal, { type TransactionFormMode } from "@/components/transactions/TransactionFormModal";
import TransactionImportModal from "@/components/transactions/TransactionImportModal";
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

const transactionText = (tx: Transaction) => `${tx.note || ""} ${tx.categoryName || ""}`.toLowerCase();

const requestErrorMessage = (error: unknown, fallback: string) => {
  if (!error || typeof error !== "object") return fallback;
  const response = "response" in error ? (error as { response?: { data?: unknown } }).response : undefined;
  const data = response?.data;
  if (data && typeof data === "object") {
    const problem = data as Record<string, unknown>;
    for (const key of ["message", "detail", "error"]) {
      if (typeof problem[key] === "string" && problem[key]) return problem[key] as string;
    }
  }
  return fallback;
};

const isPendingCollection = (tx: Transaction) =>
  tx.type === 1 && /待回款|应收|未回款|尾款|分期|验收后|交付后|回款中/.test(transactionText(tx));

const isCustomerRefund = (tx: Transaction) =>
  tx.type === 2 && /客户退款|退款给客户|收入退款|订单退款|项目退款|退货退款|服务退款/.test(transactionText(tx));

const isSeverancePayment = (tx: Transaction) =>
  tx.type === 2 && /裁员|离职补偿|经济补偿|遣散|n\+1|n\+ 1|补偿金|解除劳动/.test(transactionText(tx));

export default function TransactionsPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const t = useTranslations("transaction");
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
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
  const [summaryData, setSummaryData] = useState<TransactionSummary | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [total, setTotal] = useState(0);
  const [initialLoading, setInitialLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
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
  const [importVisible, setImportVisible] = useState(false);
  const hasLoadedRef = useRef(false);
  const loadedSubjectRef = useRef<number | null>(activeCompanyId);
  const handledActionRef = useRef("");

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

  const summaryQuery = useMemo(() => {
    const filters = { ...query };
    delete filters.page;
    delete filters.size;
    return filters;
  }, [query]);

  const fetchData = useCallback(async () => {
    const isInitial = !hasLoadedRef.current;
    if (isInitial) {
      setInitialLoading(true);
    } else {
      setRefreshing(true);
    }
    setLoadError(null);
    try {
      const [listRes, summaryRes] = await Promise.all([
        transactionApi.list(query),
        transactionApi.summary(summaryQuery),
      ]);
      setData(listRes.data.content);
      setTotal(listRes.data.totalElements);
      setSummaryData(summaryRes.data);
      hasLoadedRef.current = true;
    } catch {
      setLoadError(t("loadFailed"));
      Message.error("经营流水加载失败");
    } finally {
      setInitialLoading(false);
      setRefreshing(false);
    }
  }, [query, summaryQuery, t]);

  useEffect(() => {
    let cancelled = false;

    const loadTransactions = async () => {
      const subjectChanged = loadedSubjectRef.current !== activeCompanyId;
      if (subjectChanged) {
        loadedSubjectRef.current = activeCompanyId;
        hasLoadedRef.current = false;
        setData([]);
        setSummaryData(null);
        setTotal(0);
        setSelectedTransaction(null);
      }
      const isInitial = !hasLoadedRef.current;
      if (isInitial) {
        setInitialLoading(true);
      } else {
        setRefreshing(true);
      }
      setLoadError(null);
      try {
        const [listRes, summaryRes] = await Promise.all([
          transactionApi.list(query),
          transactionApi.summary(summaryQuery),
        ]);
        if (cancelled) return;
        setData(listRes.data.content);
        setTotal(listRes.data.totalElements);
        setSummaryData(summaryRes.data);
        hasLoadedRef.current = true;
      } catch {
        if (!cancelled) setLoadError(t("loadFailed"));
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
  }, [activeCompanyId, fetchCategories, query, summaryQuery, t]);

  useEffect(() => {
    let cancelled = false;

    const loadAccounts = async () => {
      setAccounts([]);
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
  }, [activeCompanyId]);

  useEffect(() => {
    const action = searchParams.get("action");
    const rawTransactionId = searchParams.get("transactionId");
    const parsedTransactionId = rawTransactionId ? Number(rawTransactionId) : null;
    const validTransactionId = parsedTransactionId && Number.isInteger(parsedTransactionId) && parsedTransactionId > 0
      ? parsedTransactionId
      : null;
    const mode: TransactionFormMode | null = action === "new"
      ? "create"
      : action === "edit" && validTransactionId
        ? "edit"
        : action === "refund" && validTransactionId
          ? "refund"
          : null;

    if (!mode) {
      handledActionRef.current = "";
      return;
    }

    const actionKey = `${mode}:${validTransactionId || "new"}`;
    if (handledActionRef.current === actionKey) return;
    handledActionRef.current = actionKey;
    const timer = window.setTimeout(() => {
      setFormMode(mode);
      setActiveTransactionId(validTransactionId);
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

  const summary = useMemo<TransactionSummary>(() => summaryData || ({
    income: 0,
    expense: 0,
    refund: 0,
    pendingCollection: 0,
    customerRefund: 0,
    severance: 0,
    netCollectedIncome: 0,
    net: 0,
    rows: total,
    largeCount: 0,
    reviewCount: 0,
  }), [summaryData, total]);

  const typeColors = useMemo<Record<number, { color: string; bg: string; label: string }>>(() => ({
    1: { color: "#10b981", bg: "#10b98120", label: t("income") },
    2: { color: "#ef4444", bg: "#ef444420", label: t("expense") },
    3: { color: "#7c5cc4", bg: "#7c5cc41a", label: t("refund") },
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

  const applyFilters = (nextView = viewFilter, nextTypeFilter = typeFilter) => {
    if (!validateFilters()) return;

    const keyword = search.trim();
    const params = new URLSearchParams(searchParams.toString());
    params.delete("action");
    if (keyword) {
      params.set("keyword", keyword);
    } else {
      params.delete("keyword");
    }
    if (nextTypeFilter === "all") {
      params.delete("type");
    } else {
      params.set("type", nextTypeFilter);
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
    const nextTypeFilter = nextView === "income" || nextView === "expense" ? "all" : typeFilter;
    setViewFilter(nextView);
    setTypeFilter(nextTypeFilter);
    applyFilters(nextView, nextTypeFilter);
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
    if (mode !== "create") setSelectedTransaction(null);
    setFormMode(mode);
    setActiveTransactionId(transactionId || null);
    setFormVisible(true);
  };

  const closeForm = () => {
    setFormVisible(false);
    setActiveTransactionId(null);
    if (searchParams.get("action")) {
      const params = new URLSearchParams(searchParams.toString());
      params.delete("action");
      params.delete("transactionId");
      router.replace(`/transactions${params.toString() ? `?${params.toString()}` : ""}`, { scroll: false });
    }
  };

  const refreshData = () => {
    void fetchData();
  };

  const handleFormSuccess = () => {
    setSelectedTransaction(null);
    if (hasAppliedFilters) Message.info(t("savedMayBeFiltered"));
    void fetchData();
  };

  const handlePageChange = (page: number, size: number) => {
    setPageSize(size);
    setPageIndex(page - 1);
  };

  const handleDelete = (transaction: Transaction, closeDrawer = false) => {
    Modal.confirm({
      title: transaction.type === 3 ? "确认撤销退款" : "确认删除流水",
      content: transaction.type === 3
        ? "撤销后会回滚原流水的已退金额，并同步恢复账户余额。确定继续吗？"
        : "删除后会同步回滚账户余额，且无法撤销。确定继续吗？",
      onOk: async () => {
        try {
          await transactionApi.delete(transaction.id);
          Message.success(transaction.type === 3 ? "退款已撤销" : "流水已删除");
          if (closeDrawer) {
            setSelectedTransaction(null);
          }
          await fetchData();
        } catch (error) {
          Message.error(requestErrorMessage(error, transaction.type === 3 ? "退款撤销失败" : "流水删除失败"));
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
      flags.push({ key: "refundable", label: t("refundableTag"), color: "purple" });
    }
    if (isPendingCollection(tx)) {
      flags.push({ key: "pending-collection", label: "待回款", color: "arcoblue" });
    }
    if (isCustomerRefund(tx)) {
      flags.push({ key: "customer-refund", label: "冲减收入", color: "red" });
    }
    if (isSeverancePayment(tx)) {
      flags.push({ key: "severance", label: "离职补偿", color: "purple" });
    }
    if (tx.refundedAmount > 0) {
      flags.push({ key: "refunded", label: t("refundedTag"), color: "cyan" });
    }
    if (!tx.note?.trim()) {
      flags.push({ key: "missing-note", label: t("missingNote"), color: "gray" });
    }
    return flags;
  };

  const renderActions = (tx: Transaction) => (
    <div className="flex items-center justify-end gap-1">
      <Button
        aria-label={`${t("viewAction")} #${tx.id}`}
        title={t("viewAction")}
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
          aria-label={`${t("editAction")} #${tx.id}`}
          title={t("editAction")}
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
          aria-label={`${t("refundAction")} #${tx.id}`}
          title={t("refundAction")}
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
      <Button
        aria-label={`${tx.type === 3 ? "撤销退款" : t("deleteAction")} #${tx.id}`}
        title={tx.type !== 3 && tx.refundedAmount > 0 ? "请先撤销关联退款" : tx.type === 3 ? "撤销退款" : t("deleteAction")}
        type="text"
        size="mini"
        status="danger"
        disabled={tx.type !== 3 && tx.refundedAmount > 0}
        icon={<IconDelete />}
        onClick={(event) => {
          event.stopPropagation();
          handleDelete(tx);
        }}
      />
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

  const operatingCards = [
    {
      label: "备注辅助：收入净额",
      value: <AmountDisplay amount={summary.netCollectedIncome} type={summary.netCollectedIncome >= 0 ? 1 : 2} showSign size="medium" />,
      hint: `收入 ${formatAmount(summary.income)} - 客户退款 ${formatAmount(summary.customerRefund)}`,
    },
    {
      label: "备注命中：待回款",
      value: <AmountDisplay amount={summary.pendingCollection} type={1} size="medium" />,
      hint: "按备注中的待回款、应收、尾款、分期识别",
    },
    {
      label: "备注命中：客户退款",
      value: <AmountDisplay amount={summary.customerRefund} type={2} size="medium" />,
      hint: "客户退款、订单退款、项目退款需要扣减收入",
    },
    {
      label: "备注命中：离职补偿",
      value: <AmountDisplay amount={summary.severance} type={2} size="medium" />,
      hint: "计入人力成本复盘，不并入固定薪酬",
    },
  ];

  const hasAppliedFilters = Boolean(
    appliedKeyword
    || appliedTypeFilter !== "all"
    || appliedCategory !== "all"
    || appliedAccount !== "all"
    || appliedStartDate
    || appliedEndDate
    || appliedMinAmount
    || appliedMaxAmount
    || appliedView !== "all"
  );

  const filtersDirty = search.trim() !== appliedKeyword
    || typeFilter !== appliedTypeFilter
    || categoryFilter !== appliedCategory
    || accountFilter !== appliedAccount
    || startDate !== appliedStartDate
    || endDate !== appliedEndDate
    || minAmount !== appliedMinAmount
    || maxAmount !== appliedMaxAmount
    || viewFilter !== appliedView;

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
          <div className="flex flex-wrap items-center justify-end gap-2">
            {refreshing && <Tag color="arcoblue" role="status" aria-live="polite">{t("refreshing")}</Tag>}
            <Button icon={<IconUpload />} onClick={() => setImportVisible(true)}>批量导入</Button>
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

      <div className="metric-grid grid grid-cols-2 lg:grid-cols-6">
        {summaryCards.map((item) => (
          <Card className="metric-card" key={item.label} loading={initialLoading} style={{ borderRadius: 12 }}>
            <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{item.label}</div>
            <div className="mt-2">{item.value}</div>
          </Card>
        ))}
      </div>

      {!isHousehold && (
        <div className="mb-4">
          <div className="mb-2 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
            {t("noteInferenceDisclaimer")}
          </div>
          <div className="context-strip grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4">
            {operatingCards.map((item) => (
              <Card key={item.label} loading={initialLoading} style={{ borderRadius: 12 }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{item.label}</div>
                <div className="mt-2">{item.value}</div>
                <div className="mt-2 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{item.hint}</div>
              </Card>
            ))}
          </div>
        </div>
      )}

      <Card className="filter-card mb-4" style={{ borderRadius: 12 }}>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap gap-2" role="group" aria-label={t("quickViews")}>
            {viewOptions.map((item) => {
              const active = viewFilter === item.key;
              return (
                <button
                  key={item.key}
                  type="button"
                  aria-pressed={active}
                  onClick={() => handleQuickView(item.key)}
                  className="ledger-view-chip h-9 cursor-pointer border px-4 text-sm transition-colors"
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
          <div aria-live="polite">
            {filtersDirty ? <Tag color="purple">{t("filtersChanged")}</Tag> : hasAppliedFilters ? <Tag color="arcoblue">{t("filtersApplied")}</Tag> : null}
          </div>
        </div>

        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[minmax(280px,1fr)_150px_170px_180px]">
          <div className="w-full">
            <Input
              aria-label={t("searchPlaceholder")}
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
            <Select
              aria-label={t("allTypes")}
              value={typeFilter}
              onChange={(value) => {
                setTypeFilter(value);
                if (viewFilter === "income" || viewFilter === "expense") setViewFilter("all");
              }}
              style={{ width: "100%", borderRadius: 12 }}
            >
              <Select.Option value="all">{t("allTypes")}</Select.Option>
              <Select.Option value="1">{t("income")}</Select.Option>
              <Select.Option value="2">{t("expense")}</Select.Option>
              <Select.Option value="3">{t("refund")}</Select.Option>
            </Select>
          </div>
          <div className="w-full">
            <Select aria-label={t("allCategories")} value={categoryFilter} onChange={setCategoryFilter} style={{ width: "100%", borderRadius: 12 }}>
              <Select.Option value="all">{t("allCategories")}</Select.Option>
              {categories.map((category) => (
                <Select.Option key={category.id} value={String(category.id)}>
                  {category.name}
                </Select.Option>
              ))}
            </Select>
          </div>
          <div className="w-full">
            <Select aria-label={t("allAccounts")} value={accountFilter} onChange={setAccountFilter} style={{ width: "100%", borderRadius: 12 }}>
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
              aria-label={t("startDate")}
              format="YYYY-MM-DD"
              value={startDate}
              onChange={(value) => setStartDate(value || "")}
              placeholder={t("startDate")}
              className="w-full"
              style={{ borderRadius: 12 }}
            />
            <span aria-hidden="true" className="text-center text-sm" style={{ color: "var(--text-color-4)" }}>-</span>
            <DatePicker
              aria-label={t("endDate")}
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
              aria-label={t("minAmount")}
              type="number"
              value={minAmount}
              onChange={setMinAmount}
              placeholder={t("minAmount")}
              style={{ borderRadius: 12 }}
            />
            <span aria-hidden="true" className="text-center text-sm" style={{ color: "var(--text-color-4)" }}>-</span>
            <Input
              aria-label={t("maxAmount")}
              type="number"
              value={maxAmount}
              onChange={setMaxAmount}
              placeholder={t("maxAmount")}
              style={{ borderRadius: 12 }}
            />
          </div>
          <Button type="primary" loading={refreshing} className="w-full md:w-[110px]" onClick={() => applyFilters()}>
            {t("applyFilters")}
          </Button>
          <Button className="w-full md:w-[110px]" onClick={handleReset}>
            {t("reset")}
          </Button>
        </div>
      </Card>

      {loadError ? (
        <Card className="mb-4" style={{ borderRadius: 12 }}>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between" role="alert">
            <div className="flex items-start gap-2 text-sm" style={{ color: "rgb(var(--red-6))" }}>
              <IconExclamationCircle className="mt-0.5 shrink-0" />
              <span>{loadError}</span>
            </div>
            <Button size="small" icon={<IconRefresh />} loading={refreshing} onClick={refreshData}>{t("retry")}</Button>
          </div>
        </Card>
      ) : null}

      {loadError && data.length === 0 ? null : data.length === 0 && !initialLoading ? (
        <Card style={{ borderRadius: 12 }}>
          <EmptyState
            icon={<IconEmpty style={{ fontSize: 56, color: "var(--text-color-4)" }} />}
            title={hasAppliedFilters ? t("filteredEmptyTitle") : isHousehold ? "暂无家庭收支" : "暂无经营流水"}
            description={hasAppliedFilters
              ? t("filteredEmptyDescription")
              : isHousehold
                ? "点击上方按钮记录第一笔家庭收入或支出"
                : "点击上方按钮录入第一笔收入或成本"}
            actionText={hasAppliedFilters ? t("clearFilters") : isHousehold ? "记一笔" : "录入流水"}
            onAction={hasAppliedFilters ? handleReset : () => openForm("create")}
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
              <caption className="sr-only">{t("ledgerTable")}</caption>
              <colgroup>
                <col style={{ width: 104 }} />
                <col style={{ width: 250 }} />
                <col style={{ width: 118 }} />
                <col style={{ width: 116 }} />
                <col style={{ width: 140 }} />
                <col style={{ width: 140 }} />
                <col style={{ width: 120 }} />
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
                    t("linkageStatus"),
                    t("tags"),
                    t("actions"),
                  ].map((column) => (
                    <th scope="col" key={column} className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>
                      {column}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.map((tx) => {
                  const typeConfig = typeColors[tx.type] || typeColors[2];
                  return (
                    <tr
                      key={tx.id}
                      className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                      style={{ borderColor: "var(--border-color-light)" }}
                    >
                      <td className="px-4 py-4 align-middle">
                        <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{formatDate(tx.date)}</div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>#{tx.id}</div>
                      </td>
                      <td className="px-4 py-4 align-middle">
                        <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{tx.note || tx.categoryName || "未命名"}</div>
                        <div className="mt-2 flex flex-wrap items-center gap-2">
                          <Tag color={tx.type === 1 ? "green" : tx.type === 2 ? "red" : "purple"}>{typeConfig.label}</Tag>
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
                      <td className="px-4 py-4 align-middle">
                        <Tag color="gray" title={t("linkageUnavailable")}>{t("manualReview")}</Tag>
                      </td>
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
                  aria-label={`${t("viewAction")} #${tx.id}，${tx.note || tx.categoryName || t("unnamed")}`}
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
                        <Tag color={tx.type === 1 ? "green" : tx.type === 2 ? "red" : "purple"}>{typeConfig.label}</Tag>
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
        width="min(440px, 100vw)"
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
                <Tag color={selectedTransaction.type === 1 ? "green" : selectedTransaction.type === 2 ? "red" : "purple"}>
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
              <div
                className="flex items-start gap-2 rounded-xl border p-3 text-xs leading-5"
                style={{ borderColor: "var(--color-warning-border)", backgroundColor: "var(--color-warning-soft)", color: "var(--color-warning)" }}
              >
                <IconExclamationCircle className="mt-0.5 shrink-0" />
                <span>{t("linkageUnavailable")}</span>
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

            <div className="flex flex-wrap gap-2">
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
              <Button
                status="danger"
                icon={<IconDelete />}
                disabled={selectedTransaction.type !== 3 && selectedTransaction.refundedAmount > 0}
                title={selectedTransaction.type !== 3 && selectedTransaction.refundedAmount > 0 ? "请先撤销关联退款" : undefined}
                onClick={() => handleDelete(selectedTransaction, true)}
              >
                {selectedTransaction.type === 3 ? "撤销退款" : t("deleteAction")}
              </Button>
            </div>
          </div>
        )}
      </Drawer>

      <TransactionFormModal
        visible={formVisible}
        mode={formMode}
        transactionId={activeTransactionId}
        onClose={closeForm}
        onSuccess={handleFormSuccess}
      />
      <TransactionImportModal
        visible={importVisible}
        onClose={() => setImportVisible(false)}
        onSuccess={() => {
          setPageIndex(0);
          void fetchData();
        }}
      />
    </div>
  );
}
