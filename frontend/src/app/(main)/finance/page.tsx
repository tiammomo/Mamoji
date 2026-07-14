"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Alert, Button, Card, Empty, Message, Progress, Skeleton, Tag } from "@arco-design/web-react";
import {
  IconCalendar,
  IconCheckCircle,
  IconDashboard,
  IconExclamationCircle,
  IconFile,
  IconRefresh,
  IconRight,
  IconSafe,
  IconStorage,
  IconSwap,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import { accountApi } from "@/lib/api/accounts";
import { budgetApi } from "@/lib/api/budgets";
import { receiptApi } from "@/lib/api/receipts";
import { statsApi } from "@/lib/api/stats";
import { transactionApi } from "@/lib/api/transactions";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount, formatDate } from "@/lib/utils/format";
import type {
  Account,
  AccountSummary,
  Budget,
  OverviewStats,
  ReceiptSummary,
  ReceiptVoucher,
  Transaction,
} from "@/lib/types";

type FinanceView = {
  accountSummary: AccountSummary | null;
  accounts: Account[];
  receiptSummary: ReceiptSummary | null;
  vouchers: ReceiptVoucher[];
  budgets: Budget[];
  transactions: Transaction[];
  stats: OverviewStats | null;
};

type FinanceAction = {
  title: string;
  detail: string;
  path: string;
  severity: "success" | "notice" | "warning" | "danger";
  icon: ReactNode;
};

const emptyView: FinanceView = {
  accountSummary: null,
  accounts: [],
  receiptSummary: null,
  vouchers: [],
  budgets: [],
  transactions: [],
  stats: null,
};

const accountTypeMeta: Record<string, { label: string; color: string; icon: ReactNode }> = {
  cash: { label: "现金", color: "green", icon: <IconStorage /> },
  bank: { label: "银行账户", color: "arcoblue", icon: <IconSafe /> },
  credit: { label: "信用账户", color: "orange", icon: <IconSwap /> },
  digital: { label: "数字钱包", color: "cyan", icon: <IconStorage /> },
  investment: { label: "理财账户", color: "purple", icon: <IconSafe /> },
  debt: { label: "负债账户", color: "red", icon: <IconExclamationCircle /> },
};

const voucherTypeLabels: Record<string, string> = {
  sales_invoice: "销项发票",
  purchase_invoice: "进项发票",
  receipt: "收据",
  bank_slip: "银行回单",
  contract: "合同付款",
  reimbursement: "报销凭证",
  tax_receipt: "税务回执",
};

const statusMeta: Record<FinanceAction["severity"], { color: string; label: string }> = {
  success: { color: "green", label: "正常" },
  notice: { color: "arcoblue", label: "关注" },
  warning: { color: "orange", label: "预警" },
  danger: { color: "red", label: "风险" },
};

const currency = "CNY";

const clamp = (value: number, min = 0, max = 100) => Math.max(min, Math.min(value, max));

const daysUntil = (date?: string | null) => {
  if (!date) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const target = new Date(date);
  target.setHours(0, 0, 0, 0);
  return Math.ceil((target.getTime() - today.getTime()) / 86400000);
};

const isOpenVoucher = (voucher: ReceiptVoucher) =>
  voucher.status !== "archived" && voucher.status !== "rejected";

const isReceivable = (voucher: ReceiptVoucher) =>
  voucher.direction === "income"
  && isOpenVoucher(voucher)
  && (voucher.status === "pending_review" || !voucher.transactionId);

const isPayable = (voucher: ReceiptVoucher) =>
  voucher.direction === "expense"
  && isOpenVoucher(voucher)
  && (voucher.status === "pending_review" || !voucher.transactionId);

const textOfTransaction = (transaction: Transaction) =>
  `${transaction.note || ""} ${transaction.categoryName || ""}`.toLowerCase();

const isCustomerRefund = (transaction: Transaction) =>
  transaction.type === 2 && /客户退款|退款给客户|收入退款|订单退款|项目退款|退货退款|服务退款/.test(textOfTransaction(transaction));

const isSeverancePayment = (transaction: Transaction) =>
  transaction.type === 2 && /裁员|离职补偿|经济补偿|遣散|n\+1|n\+ 1|补偿金|解除劳动/.test(textOfTransaction(transaction));

const riskColor = (riskLevel: string) => {
  if (riskLevel === "critical" || riskLevel === "high") return "red";
  if (riskLevel === "medium") return "orange";
  return "green";
};

type FinanceLoadResult = {
  view: FinanceView;
  failedSections: string[];
};

const loadFinanceView = async (): Promise<FinanceLoadResult> => {
  const [
    accountSummaryResult,
    accountsResult,
    receiptSummaryResult,
    vouchersResult,
    budgetsResult,
    transactionsResult,
    statsResult,
  ] = await Promise.allSettled([
    accountApi.summary(),
    accountApi.list(),
    receiptApi.summary(),
    receiptApi.list({ page: 0, size: 50 }),
    budgetApi.active(),
    transactionApi.list({ page: 0, size: 1000 }),
    statsApi.overview(),
  ]);

  const namedResults = [
    ["账户汇总", accountSummaryResult],
    ["账户列表", accountsResult],
    ["票据汇总", receiptSummaryResult],
    ["票据列表", vouchersResult],
    ["预算", budgetsResult],
    ["经营流水", transactionsResult],
    ["月度经营数据", statsResult],
  ] as const;

  return {
    view: {
      accountSummary: accountSummaryResult.status === "fulfilled" ? accountSummaryResult.value.data : null,
      accounts: accountsResult.status === "fulfilled" ? accountsResult.value.data : [],
      receiptSummary: receiptSummaryResult.status === "fulfilled" ? receiptSummaryResult.value.data : null,
      vouchers: vouchersResult.status === "fulfilled" ? vouchersResult.value.data.content : [],
      budgets: budgetsResult.status === "fulfilled" ? budgetsResult.value.data : [],
      transactions: transactionsResult.status === "fulfilled" ? transactionsResult.value.data.content : [],
      stats: statsResult.status === "fulfilled" ? statsResult.value.data : null,
    },
    failedSections: namedResults
      .filter(([, result]) => result.status === "rejected")
      .map(([name]) => name),
  };
};

export default function FinancePage() {
  const router = useRouter();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [view, setView] = useState<FinanceView>(emptyView);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [failedSections, setFailedSections] = useState<string[]>([]);

  const hasFailedSections = failedSections.length > 0;
  const accountSummaryUnavailable = failedSections.includes("账户汇总");
  const receiptSummaryUnavailable = failedSections.includes("票据汇总");
  const vouchersUnavailable = failedSections.includes("票据列表");
  const budgetsUnavailable = failedSections.includes("预算");
  const transactionsUnavailable = failedSections.includes("经营流水");
  const monthlyStatsUnavailable = failedSections.includes("月度经营数据");
  const monthlyFlowUnavailable = accountSummaryUnavailable && monthlyStatsUnavailable;
  const healthUnavailable = accountSummaryUnavailable || receiptSummaryUnavailable || budgetsUnavailable;

  const loadData = async (quiet = false) => {
    if (quiet) setRefreshing(true);
    try {
      const result = await loadFinanceView();
      setView(result.view);
      setFailedSections(result.failedSections);
      if (quiet) {
        if (result.failedSections.length > 0) Message.warning("部分财务数据未能刷新");
        else Message.success("财务总览已刷新");
      }
    } catch {
      Message.error("财务总览加载失败");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      setLoading(true);
      const result = await loadFinanceView();
      if (!cancelled) {
        setView(result.view);
        setFailedSections(result.failedSections);
        setLoading(false);
      }
    };

    void load().catch(() => {
      if (!cancelled) {
        Message.error("财务总览加载失败");
        setLoading(false);
      }
    });

    return () => {
      cancelled = true;
    };
  }, [activeCompanyId]);

  const financeModel = useMemo(() => {
    const accountSummary = view.accountSummary;
    const receiptSummary = view.receiptSummary;
    const monthlyIncome = accountSummary?.currentMonthIncome ?? view.stats?.monthlyIncome ?? 0;
    const monthlyExpense = accountSummary?.currentMonthExpense ?? view.stats?.monthlyExpense ?? 0;
    const monthlyNetFlow = monthlyIncome - monthlyExpense;
    const availableBalance = accountSummary?.availableBalance ?? 0;
    const liquidityMonths = monthlyExpense > 0 ? availableBalance / monthlyExpense : null;
    const receivableVouchers = view.vouchers.filter(isReceivable);
    const payableVouchers = view.vouchers.filter(isPayable);
    const receivableAmount = receivableVouchers.reduce((sum, voucher) => sum + (voucher.amount || 0), 0);
    const payableAmount = payableVouchers.reduce((sum, voucher) => sum + (voucher.amount || 0), 0);
    const overdueReceivables = receivableVouchers.filter((voucher) => {
      const days = daysUntil(voucher.dueDate);
      return days !== null && days < 0;
    });
    const dueSoonReceivables = receivableVouchers.filter((voucher) => {
      const days = daysUntil(voucher.dueDate);
      return days !== null && days >= 0 && days <= 7;
    });
    const customerRefunds = view.transactions.filter(isCustomerRefund);
    const customerRefundAmount = customerRefunds.reduce((sum, transaction) => sum + (transaction.amount || 0), 0);
    const supplierRefundAmount = view.transactions
      .filter((transaction) => transaction.type === 3)
      .reduce((sum, transaction) => sum + (transaction.amount || 0), 0);
    const severancePayments = view.transactions.filter(isSeverancePayment);
    const severanceAmount = severancePayments.reduce((sum, transaction) => sum + (transaction.amount || 0), 0);
    const grossIncome = view.transactions
      .filter((transaction) => transaction.type === 1)
      .reduce((sum, transaction) => sum + (transaction.amount || 0), 0);
    const netCollectedIncome = grossIncome - customerRefundAmount;
    const overduePayables = payableVouchers.filter((voucher) => {
      const days = daysUntil(voucher.dueDate);
      return days !== null && days < 0;
    });
    const budgetRisks = view.budgets.filter((budget) =>
      budget.warningReached || budget.riskLevel === "high" || budget.riskLevel === "critical"
    );
    const accountRisks = view.accounts.filter((account) =>
      account.riskLevel === "high" || account.riskLevel === "critical" || account.reconciliationStatus === "exception"
    );
    const accountRiskCount = Math.max(accountSummary?.highRiskCount ?? 0, accountRisks.length);
    const pendingReceiptCount = receiptSummary?.pendingReviewCount ?? 0;
    const missingAttachmentCount = receiptSummary?.missingAttachmentCount ?? 0;
    const missingTransactionCount = receiptSummary?.missingTransactionCount ?? 0;

    const deductions = [
      Math.min((accountSummary?.pendingReconciliationCount ?? 0) * 6, 18),
      Math.min((accountSummary?.highRiskCount ?? 0) * 10, 20),
      Math.min(pendingReceiptCount * 4, 16),
      Math.min((missingAttachmentCount + missingTransactionCount) * 5, 20),
      Math.min(budgetRisks.length * 8, 16),
      monthlyNetFlow < 0 ? 10 : 0,
    ];
    const healthScore = clamp(100 - deductions.reduce((sum, item) => sum + item, 0));
    const healthSeverity: FinanceAction["severity"] =
      healthScore >= 85 ? "success" : healthScore >= 70 ? "notice" : healthScore >= 55 ? "warning" : "danger";

    const accountStructure = Object.entries(
      view.accounts.reduce<Record<string, { type: string; count: number; balance: number; available: number }>>((map, account) => {
        const current = map[account.type] || { type: account.type, count: 0, balance: 0, available: 0 };
        current.count += 1;
        current.balance += account.balance || 0;
        current.available += account.availableBalance || 0;
        map[account.type] = current;
        return map;
      }, {})
    )
      .map(([, item]) => item)
      .sort((a, b) => Math.abs(b.balance) - Math.abs(a.balance));

    const actions: FinanceAction[] = [];
    if ((accountSummary?.pendingReconciliationCount ?? 0) > 0) {
      actions.push({
        title: "资金账户需要对账",
        detail: `${accountSummary?.pendingReconciliationCount ?? 0} 个账户处于待对账状态`,
        path: "/accounts",
        severity: "warning",
        icon: <IconSafe />,
      });
    }
    if (accountRiskCount > 0) {
      actions.push({
        title: "账户风险需要复核",
        detail: `${accountRiskCount} 个账户存在高风险或异常对账状态`,
        path: "/accounts",
        severity: "danger",
        icon: <IconExclamationCircle />,
      });
    }
    if (pendingReceiptCount > 0 || missingAttachmentCount > 0) {
      actions.push({
        title: "票据核验未完成",
        detail: `${pendingReceiptCount} 张待核验，${missingAttachmentCount} 张缺附件`,
        path: "/receipts",
        severity: "warning",
        icon: <IconFile />,
      });
    }
    if (missingTransactionCount > 0) {
      actions.push({
        title: "凭证流水未匹配",
        detail: `${missingTransactionCount} 张凭证尚未关联经营流水`,
        path: "/receipts",
        severity: "notice",
        icon: <IconSwap />,
      });
    }
    if (receivableAmount > 0) {
      const overdueText = overdueReceivables.length > 0 ? `，${overdueReceivables.length} 笔已逾期` : "";
      actions.push({
        title: "项目交付后待回款",
        detail: `应收 ${formatAmount(receivableAmount, currency)}${overdueText}`,
        path: "/receipts",
        severity: overdueReceivables.length > 0 ? "danger" : "warning",
        icon: <IconCalendar />,
      });
    }
    if (customerRefundAmount > 0) {
      actions.push({
        title: "收入退款需要冲减",
        detail: `客户退款 ${formatAmount(customerRefundAmount, currency)}，经营收入需按净额复盘`,
        path: "/transactions",
        severity: "warning",
        icon: <IconRefresh />,
      });
    }
    if (severanceAmount > 0) {
      actions.push({
        title: "离职补偿已发生",
        detail: `裁员/离职补偿 ${formatAmount(severanceAmount, currency)}，应计入人力成本复盘`,
        path: "/transactions",
        severity: "notice",
        icon: <IconExclamationCircle />,
      });
    }
    if (budgetRisks.length > 0) {
      actions.push({
        title: "预算执行接近上限",
        detail: `${budgetRisks.length} 个预算口径触发预警或高风险`,
        path: "/budgets",
        severity: "warning",
        icon: <IconCalendar />,
      });
    }
    if (monthlyNetFlow < 0) {
      actions.push({
        title: "本月现金净流出",
        detail: `本月净流出 ${formatAmount(Math.abs(monthlyNetFlow), currency)}，建议复盘成本项`,
        path: "/transactions",
        severity: "danger",
        icon: <IconExclamationCircle />,
      });
    }
    if (actions.length === 0) {
      actions.push({
        title: hasFailedSections ? "部分财务数据暂不可用" : "财务闭环状态良好",
        detail: hasFailedSections
          ? "无法在数据不完整时判断财务状态，请先重新加载"
          : "当前资金、票据、预算没有明显待处理风险",
        path: "/finance",
        severity: hasFailedSections ? "warning" : "success",
        icon: hasFailedSections ? <IconExclamationCircle /> : <IconCheckCircle />,
      });
    }

    const closingTasks = [
      {
        label: "资金账户对账",
        unavailable: accountSummaryUnavailable,
        done: !accountSummaryUnavailable && (accountSummary?.pendingReconciliationCount ?? 0) === 0,
        detail: accountSummaryUnavailable
          ? "账户汇总数据暂不可用"
          : (accountSummary?.pendingReconciliationCount ?? 0) === 0
          ? "账户余额已完成核对"
          : `${accountSummary?.pendingReconciliationCount ?? 0} 个账户待对账`,
      },
      {
        label: "票据核验归档",
        unavailable: receiptSummaryUnavailable,
        done: !receiptSummaryUnavailable && pendingReceiptCount === 0 && missingAttachmentCount === 0,
        detail: receiptSummaryUnavailable
          ? "票据汇总数据暂不可用"
          : `${pendingReceiptCount} 张待核验，${missingAttachmentCount} 张缺附件`,
      },
      {
        label: "凭证流水匹配",
        unavailable: receiptSummaryUnavailable,
        done: !receiptSummaryUnavailable && missingTransactionCount === 0,
        detail: receiptSummaryUnavailable
          ? "票据汇总数据暂不可用"
          : missingTransactionCount === 0 ? "凭证与流水匹配完成" : `${missingTransactionCount} 张凭证未匹配`,
      },
      {
        label: "预算偏差复盘",
        unavailable: budgetsUnavailable,
        done: !budgetsUnavailable && budgetRisks.length === 0,
        detail: budgetsUnavailable
          ? "预算数据暂不可用"
          : budgetRisks.length === 0 ? "暂无预算预警" : `${budgetRisks.length} 个预算需复盘`,
      },
      {
        label: "月度利润检查",
        unavailable: monthlyFlowUnavailable,
        done: !monthlyFlowUnavailable && monthlyNetFlow >= 0,
        detail: monthlyFlowUnavailable
          ? "月度经营数据暂不可用"
          : monthlyNetFlow >= 0 ? "本月经营净额为正" : "本月经营净额为负",
      },
    ];

    return {
      monthlyIncome,
      monthlyExpense,
      monthlyNetFlow,
      availableBalance,
      liquidityMonths,
      receivableAmount,
      payableAmount,
      receivableVouchers,
      payableVouchers,
      overdueReceivables,
      dueSoonReceivables,
      customerRefundAmount,
      supplierRefundAmount,
      severanceAmount,
      grossIncome,
      netCollectedIncome,
      overduePayables,
      budgetRisks,
      accountRiskCount,
      healthScore,
      healthSeverity,
      accountStructure,
      actions,
      closingTasks,
    };
  }, [
    accountSummaryUnavailable,
    budgetsUnavailable,
    hasFailedSections,
    monthlyFlowUnavailable,
    receiptSummaryUnavailable,
    view,
  ]);

  const metricCards = [
    {
      label: "可用资金",
      value: formatAmount(financeModel.availableBalance, currency),
      hint: `${view.accountSummary?.activeAccountCount || 0} 个启用账户`,
      icon: <IconSafe />,
      color: "var(--color-primary)",
      unavailable: accountSummaryUnavailable,
    },
    {
      label: "本月净现金流",
      value: formatAmount(financeModel.monthlyNetFlow, currency),
      hint: `流入 ${formatAmount(financeModel.monthlyIncome, currency)}`,
      icon: <IconSwap />,
      color: financeModel.monthlyNetFlow >= 0 ? "var(--color-success)" : "var(--color-danger)",
      unavailable: monthlyFlowUnavailable,
    },
    {
      label: "票据缺口",
      value: String((view.receiptSummary?.pendingReviewCount || 0) + (view.receiptSummary?.missingTransactionCount || 0)),
      hint: `${view.receiptSummary?.missingAttachmentCount || 0} 张缺附件`,
      icon: <IconFile />,
      color: "var(--color-warning)",
      unavailable: receiptSummaryUnavailable,
    },
    {
      label: "财务健康度",
      value: `${financeModel.healthScore.toFixed(0)}分`,
      hint: statusMeta[financeModel.healthSeverity].label,
      icon: <IconDashboard />,
      color: healthUnavailable
        ? "var(--text-color-3)"
        : financeModel.healthSeverity === "danger" ? "var(--color-danger)" : "var(--color-success)",
      unavailable: healthUnavailable,
    },
    {
      label: "待回款",
      value: formatAmount(financeModel.receivableAmount, currency),
      hint: `${financeModel.overdueReceivables.length} 笔逾期 · ${financeModel.dueSoonReceivables.length} 笔 7 天内`,
      icon: <IconCalendar />,
      color: financeModel.overdueReceivables.length > 0 ? "var(--color-danger)" : "var(--color-warning)",
      unavailable: vouchersUnavailable,
    },
    {
      label: "退款冲减",
      value: formatAmount(financeModel.customerRefundAmount, currency),
      hint: `供应商退款返还 ${formatAmount(financeModel.supplierRefundAmount, currency)}`,
      icon: <IconRefresh />,
      color: financeModel.customerRefundAmount > 0 ? "var(--color-warning)" : "var(--color-success)",
      unavailable: transactionsUnavailable,
    },
  ];

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="财务总览"
        subtitle="资金安全、票据闭环、应收应付、月结动作集中管理"
        icon={<IconDashboard />}
        extra={
          <div className="flex items-center gap-2">
            {refreshing && <Tag color="arcoblue">刷新中</Tag>}
            <Button icon={<IconRefresh />} onClick={() => loadData(true)}>
              刷新
            </Button>
            <Button type="primary" icon={<IconFile />} onClick={() => router.push("/receipts")}>
              处理凭证
            </Button>
          </div>
        }
      />

      {failedSections.length > 0 && (
        <Alert
          className="mb-6"
          type="warning"
          title="部分财务数据未能加载"
          content={`${failedSections.join("、")} 暂不可用；缺失内容不会被当作 0 或“无风险”。`}
          action={<Button size="small" icon={<IconRefresh />} loading={refreshing} onClick={() => void loadData(true)}>重新加载</Button>}
        />
      )}

      <div className="metric-grid grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-6">
        {metricCards.map((card) => (
          <Card className="metric-card" key={card.label} style={{ borderRadius: 12, minHeight: 136 }}>
            <div className="flex h-full min-h-[96px] flex-col justify-between">
              <div className="flex items-start justify-between">
                <div className="text-sm" style={{ color: "var(--text-color-3)" }}>{card.label}</div>
                <span className="inline-flex text-xl" style={{ color: card.color }}>{card.icon}</span>
              </div>
              {loading ? (
                <Skeleton />
              ) : (
                <div>
                  <div className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{card.unavailable ? "--" : card.value}</div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{card.unavailable ? "数据暂不可用" : card.hint}</div>
                </div>
              )}
            </div>
          </Card>
        ))}
      </div>

      <div className="mb-6 grid grid-cols-1 gap-4 xl:grid-cols-[1.12fr_0.88fr]">
        <Card style={{ borderRadius: 12 }} title="财务健康雷达">
          {loading ? (
            <Skeleton />
          ) : healthUnavailable ? (
            <div className="flex min-h-[220px] flex-col items-center justify-center px-6 text-center">
              <IconExclamationCircle style={{ color: "var(--color-warning)", fontSize: 34 }} />
              <div className="mt-4 font-semibold">暂不计算财务健康度</div>
              <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>数据不完整，避免输出误导性结论</div>
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-[240px_1fr]">
              <div
                className="flex min-h-[220px] flex-col items-center justify-center rounded-xl border px-5 py-6"
                style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
              >
                <Progress
                  type="circle"
                  percent={financeModel.healthScore}
                  width={132}
                  color={financeModel.healthSeverity === "danger" ? "var(--color-danger)" : "var(--color-primary)"}
                  formatText={() => `${financeModel.healthScore.toFixed(0)}分`}
                />
                <Tag className="mt-4" color={statusMeta[financeModel.healthSeverity].color}>
                  {statusMeta[financeModel.healthSeverity].label}
                </Tag>
                <div className="mt-3 text-center text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
                  由对账、票据、预算、现金流和账户风险综合计算
                </div>
              </div>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>现金可覆盖</div>
                  <div className="mt-2 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
                    {financeModel.liquidityMonths === null ? "--" : `${financeModel.liquidityMonths.toFixed(1)} 个月`}
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    按本月成本支出估算
                  </div>
                </div>
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>待对账/高风险账户</div>
                  <div className="mt-2 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
                    {(view.accountSummary?.pendingReconciliationCount || 0) + financeModel.accountRiskCount}
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    建议月结前完成复核
                  </div>
                </div>
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>应收待闭环</div>
                  <div className="mt-2 text-xl font-bold" style={{ color: "var(--text-color-1)" }}>
                    {vouchersUnavailable ? "--" : formatAmount(financeModel.receivableAmount, currency)}
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    {vouchersUnavailable ? "票据列表暂不可用" : `${financeModel.receivableVouchers.length} 张收入凭证待匹配`}
                  </div>
                </div>
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>应付待处理</div>
                  <div className="mt-2 text-xl font-bold" style={{ color: "var(--text-color-1)" }}>
                    {vouchersUnavailable ? "--" : formatAmount(financeModel.payableAmount, currency)}
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    {vouchersUnavailable ? "票据列表暂不可用" : `${financeModel.overduePayables.length} 张可能逾期`}
                  </div>
                </div>
              </div>
            </div>
          )}
        </Card>

        <Card
          style={{ borderRadius: 12 }}
          title="重点动作"
          extra={<Button type="text" size="small" onClick={() => router.push("/accounts")}>资金账户</Button>}
        >
          {loading ? (
            <Skeleton />
          ) : (
            <div className="space-y-3">
              {financeModel.actions.slice(0, 5).map((action) => {
                const meta = statusMeta[action.severity];
                return (
                  <button
                    key={action.title}
                    type="button"
                    onClick={() => router.push(action.path)}
                    className="flex w-full cursor-pointer items-center gap-3 rounded-xl border bg-transparent p-3 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                    style={{ borderColor: "var(--border-color-light)" }}
                  >
                    <span
                      className="grid h-10 w-10 shrink-0 place-items-center rounded-xl"
                      style={{ backgroundColor: "var(--color-fill-1)", color: meta.color === "red" ? "var(--color-danger)" : "var(--color-primary)" }}
                    >
                      {action.icon}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-semibold" style={{ color: "var(--text-color-1)" }}>{action.title}</span>
                      <span className="mt-1 block truncate text-xs" style={{ color: "var(--text-color-3)" }}>{action.detail}</span>
                    </span>
                    <Tag color={meta.color}>{meta.label}</Tag>
                    <IconRight style={{ color: "var(--text-color-4)" }} />
                  </button>
                );
              })}
            </div>
          )}
        </Card>
      </div>

      <div className="bi-panel-cluster bi-cluster-xl mb-6 grid grid-cols-1 xl:grid-cols-3">
        <Card style={{ borderRadius: 12 }} title="资金结构">
          {loading ? (
            <Skeleton />
          ) : failedSections.includes("账户列表") || failedSections.includes("账户汇总") ? (
            <Empty description="账户结构数据暂不可用" />
          ) : financeModel.accountStructure.length === 0 ? (
            <Empty description="暂无资金账户" />
          ) : (
            <div className="space-y-3">
              {financeModel.accountStructure.map((item) => {
                const meta = accountTypeMeta[item.type] || { label: item.type, color: "gray", icon: <IconSafe /> };
                const percent = view.accountSummary?.totalAssets
                  ? clamp(Math.abs(item.balance) / Math.max(Math.abs(view.accountSummary.totalAssets), 1) * 100)
                  : 0;
                return (
                  <div key={item.type} className="rounded-xl border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                    <div className="flex items-center justify-between gap-3">
                      <div className="flex min-w-0 items-center gap-2">
                        <span
                          className="grid h-8 w-8 shrink-0 place-items-center rounded-lg"
                          style={{ backgroundColor: "var(--color-fill-1)", color: "var(--color-primary)" }}
                        >
                          {meta.icon}
                        </span>
                        <div className="min-w-0">
                          <div className="truncate font-medium" style={{ color: "var(--text-color-1)" }}>{meta.label}</div>
                          <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{item.count} 个账户</div>
                        </div>
                      </div>
                      <div className="text-right">
                        <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{formatAmount(item.balance, currency)}</div>
                        <Tag size="small" color={meta.color}>{percent.toFixed(0)}%</Tag>
                      </div>
                    </div>
                    <div className="mt-3 h-2 overflow-hidden rounded-full" style={{ backgroundColor: "var(--border-color-light)" }}>
                      <div className="h-full rounded-full" style={{ width: `${percent}%`, backgroundColor: "var(--color-primary)" }} />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </Card>

        <Card style={{ borderRadius: 12 }} title="回款与应收应付">
          {loading ? (
            <Skeleton />
          ) : (
            <div className="space-y-4">
              {vouchersUnavailable ? (
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
                  <Empty description="票据应收应付数据暂不可用" />
                </div>
              ) : (
                <>
                  <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
                    <div className="flex items-center justify-between">
                      <span className="text-sm" style={{ color: "var(--text-color-3)" }}>已交付待回款</span>
                      <Tag color={financeModel.overdueReceivables.length > 0 ? "red" : "green"}>
                        {financeModel.receivableVouchers.length} 笔
                      </Tag>
                    </div>
                    <div className="mt-2 text-2xl font-bold amount-income">{formatAmount(financeModel.receivableAmount, currency)}</div>
                    <div className="mt-2 flex flex-wrap gap-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                      <span>{financeModel.overdueReceivables.length} 笔逾期</span>
                      <span>{financeModel.dueSoonReceivables.length} 笔 7 天内到期</span>
                    </div>
                  </div>
                  <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
                    <div className="flex items-center justify-between">
                      <span className="text-sm" style={{ color: "var(--text-color-3)" }}>应付待处理</span>
                      <Tag color={financeModel.overduePayables.length > 0 ? "red" : "orange"}>{financeModel.payableVouchers.length} 张</Tag>
                    </div>
                    <div className="mt-2 text-2xl font-bold amount-expense">{formatAmount(financeModel.payableAmount, currency)}</div>
                  </div>
                  <div className="space-y-2">
                    {[...financeModel.receivableVouchers, ...financeModel.payableVouchers].slice(0, 4).map((voucher) => {
                      const days = daysUntil(voucher.dueDate);
                      return (
                        <div key={voucher.id} className="flex items-center justify-between gap-3 text-sm">
                          <div className="min-w-0">
                            <div className="truncate font-medium" style={{ color: "var(--text-color-1)" }}>{voucher.title}</div>
                            <div className="truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                              {voucher.direction === "income" ? "回款" : "付款"} · {voucherTypeLabels[voucher.voucherType] || voucher.voucherType} · {voucher.counterparty}
                            </div>
                          </div>
                          <div className="shrink-0 text-right">
                            <AmountDisplay amount={voucher.amount} type={voucher.direction === "income" ? 1 : 2} size="small" />
                            <div className="text-xs" style={{ color: days !== null && days < 0 ? "var(--color-danger)" : "var(--text-color-3)" }}>
                              {days === null ? "无截止日" : days < 0 ? `逾期 ${Math.abs(days)} 天` : `${days} 天后`}
                            </div>
                          </div>
                        </div>
                      );
                    })}
                    {financeModel.receivableVouchers.length + financeModel.payableVouchers.length === 0 && (
                      <Empty description="暂无回款或应付待办" />
                    )}
                  </div>
                </>
              )}

              {transactionsUnavailable ? (
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>收入净回款</div>
                  <div className="mt-2 text-2xl font-bold" style={{ color: "var(--text-color-3)" }}>--</div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>经营流水暂不可用</div>
                </div>
              ) : (
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
                  <div className="flex items-center justify-between">
                    <span className="text-sm" style={{ color: "var(--text-color-3)" }}>收入净回款</span>
                    <Tag color={financeModel.customerRefundAmount > 0 ? "orange" : "green"}>扣除客户退款</Tag>
                  </div>
                  <div className="mt-2 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
                    {formatAmount(financeModel.netCollectedIncome, currency)}
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    总收入 {formatAmount(financeModel.grossIncome, currency)} · 客户退款 {formatAmount(financeModel.customerRefundAmount, currency)}
                  </div>
                </div>
              )}
            </div>
          )}
        </Card>

        <Card style={{ borderRadius: 12 }} title="月结清单">
          {loading ? (
            <Skeleton />
          ) : (
            <div className="space-y-3">
              {financeModel.closingTasks.map((task) => (
                <div key={task.label} className="flex items-start gap-3 rounded-xl border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                  <span
                    className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full"
                    style={{
                      backgroundColor: task.unavailable
                        ? "var(--color-fill-2)"
                        : task.done ? "rgba(16, 185, 129, 0.14)" : "var(--color-warning-soft)",
                      color: task.unavailable
                        ? "var(--text-color-3)"
                        : task.done ? "var(--color-success)" : "var(--color-warning)",
                    }}
                  >
                    {task.done ? <IconCheckCircle /> : <IconExclamationCircle />}
                  </span>
                  <div className="min-w-0">
                    <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{task.label}</div>
                    <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{task.detail}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[0.95fr_1.05fr]">
        <Card
          style={{ borderRadius: 12 }}
          title="预算与费用控制"
          extra={<Button type="text" size="small" onClick={() => router.push("/budgets")}>查看预算</Button>}
        >
          {loading ? (
            <Skeleton />
          ) : failedSections.includes("预算") ? (
            <Empty description="预算数据暂不可用" />
          ) : financeModel.budgetRisks.length === 0 ? (
            <Empty description="暂无预算风险" />
          ) : (
            <div className="space-y-3">
              {financeModel.budgetRisks.slice(0, 5).map((budget) => (
                <div key={budget.id} className="rounded-xl border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                  <div className="flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <div className="truncate font-semibold" style={{ color: "var(--text-color-1)" }}>{budget.name}</div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        {budget.startDate} 至 {budget.endDate}
                      </div>
                    </div>
                    <Tag color={riskColor(budget.riskLevel)}>{(budget.usageRate * 100).toFixed(0)}%</Tag>
                  </div>
                  <div className="mt-3 h-2 overflow-hidden rounded-full" style={{ backgroundColor: "var(--border-color-light)" }}>
                    <div
                      className="h-full rounded-full"
                      style={{
                        width: `${clamp(budget.usageRate * 100)}%`,
                        backgroundColor: budget.riskLevel === "high" || budget.riskLevel === "critical"
                          ? "var(--color-danger)"
                          : "var(--color-warning)",
                      }}
                    />
                  </div>
                  <div className="mt-2 flex justify-between text-xs" style={{ color: "var(--text-color-3)" }}>
                    <span>已用 {formatAmount(budget.spent, currency)}</span>
                    <span>预算 {formatAmount(budget.amount, currency)}</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>

        <Card
          style={{ borderRadius: 12 }}
          title="最近经营流水"
          extra={<Button type="text" size="small" onClick={() => router.push("/transactions")}>查看流水</Button>}
        >
          {loading ? (
            <Skeleton />
          ) : failedSections.includes("经营流水") ? (
            <Empty description="经营流水暂不可用" />
          ) : view.transactions.length === 0 ? (
            <Empty description="暂无经营流水" />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[720px] table-fixed border-collapse text-sm">
                <colgroup>
                  <col style={{ width: "36%" }} />
                  <col style={{ width: "18%" }} />
                  <col style={{ width: "18%" }} />
                  <col style={{ width: "16%" }} />
                  <col style={{ width: "12%" }} />
                </colgroup>
                <thead>
                  <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                    <th className="px-3 py-2 text-left font-medium" style={{ color: "var(--text-color-2)" }}>事项</th>
                    <th className="px-3 py-2 text-left font-medium" style={{ color: "var(--text-color-2)" }}>分类</th>
                    <th className="px-3 py-2 text-left font-medium" style={{ color: "var(--text-color-2)" }}>账户</th>
                    <th className="px-3 py-2 text-right font-medium" style={{ color: "var(--text-color-2)" }}>金额</th>
                    <th className="px-3 py-2 text-center font-medium" style={{ color: "var(--text-color-2)" }}>日期</th>
                  </tr>
                </thead>
                <tbody>
                  {view.transactions.slice(0, 8).map((transaction) => (
                    <tr key={transaction.id} className="border-b" style={{ borderColor: "var(--border-color-light)" }}>
                      <td className="px-3 py-3 align-middle">
                        <div className="truncate font-medium" style={{ color: "var(--text-color-1)" }}>{transaction.note || "未命名流水"}</div>
                        {transaction.amount >= 10000 && <Tag className="mt-1" size="small" color="orange">大额</Tag>}
                      </td>
                      <td className="px-3 py-3 align-middle">
                        <div className="truncate" style={{ color: "var(--text-color-2)" }}>{transaction.categoryName || "未分类"}</div>
                      </td>
                      <td className="px-3 py-3 align-middle">
                        <div className="truncate" style={{ color: "var(--text-color-2)" }}>{transaction.accountName || "未设置"}</div>
                      </td>
                      <td className="px-3 py-3 text-right align-middle">
                        <AmountDisplay amount={transaction.amount} type={transaction.type} showSign size="small" />
                      </td>
                      <td className="px-3 py-3 text-center align-middle whitespace-nowrap" style={{ color: "var(--text-color-3)" }}>
                        {formatDate(transaction.date)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
