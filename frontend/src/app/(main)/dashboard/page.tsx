"use client";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Card, Grid, Button, Progress, Skeleton, Tag } from "@arco-design/web-react";
import {
  IconRight,
  IconArrowRise,
  IconArrowFall,
  IconCalendar,
  IconCheckCircle,
  IconDashboard,
  IconEdit,
  IconEmpty,
  IconExclamationCircle,
  IconFile,
  IconHome,
  IconIdcard,
  IconSafe,
  IconStorage,
  IconSwap,
  IconUserGroup,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { statsApi } from "@/lib/api/stats";
import { transactionApi } from "@/lib/api/transactions";
import { budgetApi } from "@/lib/api/budgets";
import { enterpriseApi } from "@/lib/api/enterprise";
import { accountApi } from "@/lib/api/accounts";
import { receiptApi } from "@/lib/api/receipts";
import { recurringApi, type RecurringItem } from "@/lib/api/recurring";
import AmountDisplay from "@/components/common/AmountDisplay";
import BudgetProgress from "@/components/common/BudgetProgress";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount, formatDate } from "@/lib/utils/format";
import type {
  AccountSummary,
  OverviewStats,
  Transaction,
  Budget,
  EnterpriseSummary,
  EntityTransfer,
  ReceiptSummary,
} from "@/lib/types";

const { Row, Col } = Grid;

const transferTypeLabelKeys: Record<string, string> = {
  shareholder_advance: "transferShareholderAdvance",
  advance_repayment: "transferAdvanceRepayment",
  expense_reimbursement: "transferExpenseReimbursement",
  reimbursement_payment: "transferReimbursementPayment",
  inter_entity_transfer: "transferInterEntity",
};

type WorkspaceSeverity = "success" | "notice" | "warning" | "danger";

type WorkspaceAction = {
  title: string;
  detail: string;
  path: string;
  severity: WorkspaceSeverity;
  icon: ReactNode;
};

const DAY = 24 * 60 * 60 * 1000;

const severityMeta: Record<WorkspaceSeverity, { label: string; color: string; accent: string }> = {
  success: { label: "正常", color: "green", accent: "#10b981" },
  notice: { label: "关注", color: "arcoblue", accent: "#3b82f6" },
  warning: { label: "预警", color: "orange", accent: "#f59e0b" },
  danger: { label: "风险", color: "red", accent: "#ef4444" },
};

const clamp = (value: number, min = 0, max = 100) => Math.max(min, Math.min(value, max));

const daysUntil = (date?: string | null) => {
  if (!date) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const target = new Date(`${date.slice(0, 10)}T00:00:00`);
  if (Number.isNaN(target.getTime())) return null;
  return Math.ceil((target.getTime() - today.getTime()) / DAY);
};

const dueText = (date?: string | null) => {
  const days = daysUntil(date);
  if (days === null) return "无截止日";
  if (days < 0) return `逾期 ${Math.abs(days)} 天`;
  if (days === 0) return "今日截止";
  return `${days} 天后`;
};

export default function DashboardPage() {
  const router = useRouter();
  const t = useTranslations("dashboard");
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const activeSubjectType = useAppStore((state) => state.activeSubjectType);
  const [stats, setStats] = useState<OverviewStats | null>(null);
  const [enterpriseSummary, setEnterpriseSummary] = useState<EnterpriseSummary | null>(null);
  const [recentTx, setRecentTx] = useState<Transaction[]>([]);
  const [entityTransfers, setEntityTransfers] = useState<EntityTransfer[]>([]);
  const [alerts, setAlerts] = useState<Budget[]>([]);
  const [activeBudgets, setActiveBudgets] = useState<Budget[]>([]);
  const [accountSummary, setAccountSummary] = useState<AccountSummary | null>(null);
  const [receiptSummary, setReceiptSummary] = useState<ReceiptSummary | null>(null);
  const [recurringItems, setRecurringItems] = useState<RecurringItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    const loadDashboard = async () => {
      setLoading(true);
      const transferRequest = activeCompanyId
        ? enterpriseApi.entityTransfers({ entityId: activeCompanyId })
        : enterpriseApi.entityTransfers();
      const [
        statsResult,
        enterpriseResult,
        transactionResult,
        budgetResult,
        transferResult,
        accountResult,
        receiptResult,
        recurringResult,
      ] = await Promise.allSettled([
        statsApi.overview(),
        enterpriseApi.summary(),
        transactionApi.list({ page: 0, size: 8 }),
        budgetApi.active(),
        transferRequest,
        accountApi.summary(),
        receiptApi.summary(),
        recurringApi.list(),
      ]);

      if (cancelled) return;

      if (statsResult.status === "fulfilled") {
        setStats(statsResult.value.data);
      }
      if (enterpriseResult.status === "fulfilled") {
        setEnterpriseSummary(enterpriseResult.value.data);
      }
      if (transactionResult.status === "fulfilled") {
        setRecentTx(transactionResult.value.data.content);
      }
      if (budgetResult.status === "fulfilled") {
        setActiveBudgets(budgetResult.value.data);
        setAlerts(budgetResult.value.data.filter((b) =>
          b.riskLevel === "high" || b.riskLevel === "critical" || b.warningReached
        ));
      }
      if (transferResult.status === "fulfilled") {
        setEntityTransfers(transferResult.value.data);
      } else {
        setEntityTransfers([]);
      }
      if (accountResult.status === "fulfilled") {
        setAccountSummary(accountResult.value.data);
      }
      if (receiptResult.status === "fulfilled") {
        setReceiptSummary(receiptResult.value.data);
      }
      if (recurringResult.status === "fulfilled") {
        setRecurringItems(recurringResult.value.data);
      }
      setLoading(false);
    };

    void loadDashboard();

    return () => {
      cancelled = true;
    };
  }, [activeCompanyId]);

  const quickActions = [
    {
      icon: <IconDashboard />,
      label: t("quickOperations"),
      path: "/operations",
      color: "#6366f1",
      bg: "linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)",
    },
    {
      icon: <IconEdit />,
      label: t("quickNewRecord"),
      path: "/transactions?action=new",
      color: "#10b981",
      bg: "linear-gradient(135deg, #10b981 0%, #059669 100%)",
    },
    {
      icon: <IconSafe />,
      label: t("quickFinance"),
      path: "/finance",
      color: "#3b82f6",
      bg: "linear-gradient(135deg, #3b82f6 0%, #2563eb 100%)",
    },
    {
      icon: <IconFile />,
      label: t("quickTax"),
      path: "/tax",
      color: "#f59e0b",
      bg: "linear-gradient(135deg, #f59e0b 0%, #d97706 100%)",
    },
    {
      icon: <IconCalendar />,
      label: t("quickBudgets"),
      path: "/budgets",
      color: "#8b5cf6",
      bg: "linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)",
    },
    {
      icon: <IconUserGroup />,
      label: t("quickPeople"),
      path: "/hr/organization",
      color: "#06b6d4",
      bg: "linear-gradient(135deg, #06b6d4 0%, #0891b2 100%)",
    },
  ];

  const statCards = [
    {
      label: t("monthlyIncome"),
      value: stats?.monthlyIncome || 0,
      type: 1 as const,
      icon: <IconSafe />,
      iconColor: "#059669",
      iconBg: "#10b98118",
      gradient: "var(--gradient-income)",
      trend: "+12%",
      trendUp: true,
    },
    {
      label: t("monthlyExpense"),
      value: stats?.monthlyExpense || 0,
      type: 2 as const,
      icon: <IconSwap />,
      iconColor: "#dc2626",
      iconBg: "#ef444418",
      gradient: "var(--gradient-expense)",
      trend: "-5%",
      trendUp: false,
    },
    {
      label: t("monthlyBalance"),
      value: stats?.monthlyBalance || 0,
      type: (stats?.monthlyBalance && stats.monthlyBalance >= 0 ? 1 : 2) as 1 | 2,
      icon: <IconDashboard />,
      iconColor: "#4f46e5",
      iconBg: "#6366f118",
      gradient: "var(--gradient-primary)",
      trend: stats?.monthlyBalance && stats.monthlyBalance >= 0 ? "+8%" : "-3%",
      trendUp: stats?.monthlyBalance ? stats.monthlyBalance >= 0 : true,
    },
    {
      label: t("budgetUsage"),
      value: stats?.budgetUsageRate ? stats.budgetUsageRate * 100 : 0,
      icon: <IconCalendar />,
      iconColor: "#d97706",
      iconBg: "#f59e0b18",
      gradient: "var(--gradient-warning)",
      suffix: "%",
      trend: "65%",
      trendUp: true,
    },
  ];
  const activeEntityId = enterpriseSummary?.company?.id ?? activeCompanyId;
  const isHousehold = activeSubjectType === "household" || enterpriseSummary?.company?.entityType === "household";

  const workspaceModel = useMemo(() => {
    const monthlyIncome = stats?.monthlyIncome || 0;
    const monthlyExpense = stats?.monthlyExpense || 0;
    const monthlyProfit = stats?.monthlyBalance ?? monthlyIncome - monthlyExpense;
    const profitMargin = monthlyIncome > 0 ? monthlyProfit / monthlyIncome : 0;
    const budgetRiskCount = alerts.length;
    const accountIssueCount = (accountSummary?.pendingReconciliationCount || 0) + (accountSummary?.highRiskCount || 0);
    const receiptIssueCount = (receiptSummary?.pendingReviewCount || 0)
      + (receiptSummary?.missingAttachmentCount || 0)
      + (receiptSummary?.missingTransactionCount || 0)
      + (receiptSummary?.highRiskCount || 0);
    const taxDueDays = daysUntil(enterpriseSummary?.nextTaxDueDate);
    const taxRiskCount = (enterpriseSummary?.pendingTaxAmount || 0) > 0
      ? taxDueDays !== null && taxDueDays <= 7 ? 2 : 1
      : 0;
    const activeRecurring = recurringItems.filter((item) => item.status === 1);
    const overdueRecurring = activeRecurring.filter((item) => {
      const days = daysUntil(item.nextExecution);
      return days !== null && days < 0;
    });
    const weekRecurring = activeRecurring.filter((item) => {
      const days = daysUntil(item.nextExecution);
      return days !== null && days >= 0 && days <= 7;
    });
    const largeTransactions = recentTx.filter((tx) => Number(tx.amount || 0) >= 10000);
    const reviewTransactions = recentTx.filter((tx) =>
      Number(tx.amount || 0) >= 10000 || !tx.note?.trim() || (tx.type === 2 && tx.isRefundable)
    );
    const peopleCost = enterpriseSummary?.monthlyPeopleCost || 0;
    const peopleCostRatio = monthlyExpense > 0 ? peopleCost / monthlyExpense : 0;
    const onboardingCount = enterpriseSummary?.onboardingCount || 0;

    const operationsScore = clamp(
      100
      - (monthlyProfit < 0 ? 28 : profitMargin < 0.15 ? 10 : 0)
      - Math.min(budgetRiskCount * 8, 24)
      - Math.min(reviewTransactions.length * 4, 18)
      - Math.min(overdueRecurring.length * 12, 20)
    );
    const financeScore = clamp(
      100
      - Math.min(accountIssueCount * 8, 28)
      - Math.min(receiptIssueCount * 5, 30)
      - ((accountSummary?.availableBalance || 0) < monthlyExpense ? 10 : 0)
    );
    const taxScore = clamp(
      100
      - Math.min(taxRiskCount * 18, 36)
      - ((enterpriseSummary?.pendingTaxAmount || 0) > 0 ? 10 : 0)
    );
    const hrScore = clamp(
      100
      - Math.min(onboardingCount * 6, 24)
      - Math.min((enterpriseSummary?.departuresThisMonth || 0) * 8, 24)
      - (peopleCostRatio > 0.65 ? 10 : 0)
    );
    const entityScore = clamp(
      100
      - Math.min(entityTransfers.filter((transfer) => transfer.status !== "settled" && transfer.status !== "completed").length * 7, 28)
    );
    const workspaceScore = Math.round((operationsScore + financeScore + taxScore + hrScore + entityScore) / 5);
    const workspaceSeverity: WorkspaceSeverity =
      workspaceScore >= 85 ? "success" : workspaceScore >= 70 ? "notice" : workspaceScore >= 55 ? "warning" : "danger";

    const priorityActions: WorkspaceAction[] = [];
    if (monthlyProfit < 0) {
      priorityActions.push({
        title: "经营利润为负",
        detail: `本月净额 ${formatAmount(monthlyProfit)}，建议复盘收入与成本结构`,
        path: "/operations",
        severity: "danger",
        icon: <IconExclamationCircle />,
      });
    }
    if (budgetRiskCount > 0) {
      priorityActions.push({
        title: "预算需要复核",
        detail: `${budgetRiskCount} 项预算触发预警或高风险`,
        path: "/budgets",
        severity: "warning",
        icon: <IconCalendar />,
      });
    }
    if (receiptIssueCount > 0) {
      priorityActions.push({
        title: "票据闭环未完成",
        detail: `${receiptIssueCount} 个票据/流水匹配问题需要处理`,
        path: "/receipts",
        severity: "warning",
        icon: <IconFile />,
      });
    }
    if (accountIssueCount > 0) {
      priorityActions.push({
        title: "资金账户需对账",
        detail: `${accountIssueCount} 个账户对账或风险项待处理`,
        path: "/accounts",
        severity: "notice",
        icon: <IconSafe />,
      });
    }
    if ((enterpriseSummary?.pendingTaxAmount || 0) > 0) {
      priorityActions.push({
        title: "税费待处理",
        detail: `${formatAmount(enterpriseSummary?.pendingTaxAmount || 0)} · ${dueText(enterpriseSummary?.nextTaxDueDate)}`,
        path: "/tax",
        severity: taxDueDays !== null && taxDueDays <= 7 ? "danger" : "warning",
        icon: <IconFile />,
      });
    }
    if (overdueRecurring.length > 0 || weekRecurring.length > 0) {
      priorityActions.push({
        title: "周期事项临近",
        detail: `逾期 ${overdueRecurring.length} 项，7 天内 ${weekRecurring.length} 项`,
        path: "/recurring",
        severity: overdueRecurring.length > 0 ? "danger" : "notice",
        icon: <IconCalendar />,
      });
    }
    if (priorityActions.length === 0) {
      priorityActions.push({
        title: "工作台状态良好",
        detail: "经营、财务、税务和 HR 暂无明显待办风险",
        path: "/dashboard",
        severity: "success",
        icon: <IconCheckCircle />,
      });
    }

    const moduleHealth = [
      {
        title: "经营管理",
        score: operationsScore,
        detail: `${reviewTransactions.length} 笔流水待复核 · ${budgetRiskCount} 项预算预警`,
        path: "/operations",
        icon: <IconDashboard />,
      },
      {
        title: "财务管理",
        score: financeScore,
        detail: `${accountIssueCount} 个账户问题 · ${receiptIssueCount} 个票据问题`,
        path: "/finance",
        icon: <IconSafe />,
      },
      {
        title: "税务合规",
        score: taxScore,
        detail: `${formatAmount(enterpriseSummary?.pendingTaxAmount || 0)} 待处理 · ${dueText(enterpriseSummary?.nextTaxDueDate)}`,
        path: "/tax",
        icon: <IconFile />,
      },
      {
        title: "组织与人事",
        score: hrScore,
        detail: `${enterpriseSummary?.activeEmployeeCount || 0} 人在职 · ${onboardingCount} 人待入职`,
        path: "/hr/organization",
        icon: <IconUserGroup />,
      },
      {
        title: "主体往来",
        score: entityScore,
        detail: `${entityTransfers.length} 笔公司/家庭主体往来记录`,
        path: "/dashboard",
        icon: <IconSwap />,
      },
    ];

    const dailyChecks = [
      {
        label: "经营流水日清",
        done: reviewTransactions.length === 0,
        detail: reviewTransactions.length === 0 ? "近期流水无需复核" : `${reviewTransactions.length} 笔需复核`,
        path: "/transactions",
      },
      {
        label: "资金账户对账",
        done: accountIssueCount === 0,
        detail: accountIssueCount === 0 ? "账户状态良好" : `${accountIssueCount} 个问题`,
        path: "/accounts",
      },
      {
        label: "票据凭证闭环",
        done: receiptIssueCount === 0,
        detail: receiptIssueCount === 0 ? "凭证状态良好" : `${receiptIssueCount} 个缺口`,
        path: "/receipts",
      },
      {
        label: "税务申报关注",
        done: taxRiskCount === 0,
        detail: taxRiskCount === 0 ? "暂无税费压力" : dueText(enterpriseSummary?.nextTaxDueDate),
        path: "/tax",
      },
      {
        label: "人员事项跟进",
        done: onboardingCount === 0,
        detail: onboardingCount === 0 ? "暂无待入职" : `${onboardingCount} 人待入职`,
        path: "/admin/users",
      },
    ];

    return {
      workspaceScore,
      workspaceSeverity,
      priorityActions,
      moduleHealth,
      dailyChecks,
      largeTransactions,
      weekRecurring,
      overdueRecurring,
      activeBudgetCount: activeBudgets.length,
    };
  }, [
    accountSummary,
    activeBudgets.length,
    alerts,
    enterpriseSummary,
    entityTransfers,
    receiptSummary,
    recentTx,
    recurringItems,
    stats,
  ]);

  const householdModel = useMemo(() => {
    const monthlyIncome = stats?.monthlyIncome || 0;
    const monthlyExpense = stats?.monthlyExpense || 0;
    const monthlyBalance = stats?.monthlyBalance ?? monthlyIncome - monthlyExpense;
    const availableBalance = accountSummary?.availableBalance ?? accountSummary?.netWorth ?? 0;
    const activeRecurring = recurringItems.filter((item) => item.status === 1);
    const overdueRecurring = activeRecurring.filter((item) => {
      const days = daysUntil(item.nextExecution);
      return days !== null && days < 0;
    });
    const weekRecurring = activeRecurring.filter((item) => {
      const days = daysUntil(item.nextExecution);
      return days !== null && days >= 0 && days <= 7;
    });
    const fixedIncome = activeRecurring.filter((item) => item.type === 1).reduce((sum, item) => sum + Number(item.amount || 0), 0);
    const fixedExpense = activeRecurring.filter((item) => item.type === 2).reduce((sum, item) => sum + Number(item.amount || 0), 0);
    const largeTransactions = recentTx.filter((tx) => Number(tx.amount || 0) >= 10000);
    const openTransfers = entityTransfers.filter((transfer) => transfer.status !== "settled" && transfer.status !== "completed");
    const budgetRiskCount = alerts.length;
    const score = clamp(
      100
      - (monthlyBalance < 0 ? 18 : 0)
      - Math.min(budgetRiskCount * 10, 30)
      - Math.min(overdueRecurring.length * 14, 24)
      - Math.min(largeTransactions.length * 4, 16)
      - (availableBalance < monthlyExpense ? 8 : 0)
    );
    const severity: WorkspaceSeverity = score >= 85 ? "success" : score >= 70 ? "notice" : score >= 55 ? "warning" : "danger";

    const priorityActions: WorkspaceAction[] = [];
    if (monthlyBalance < 0) {
      priorityActions.push({
        title: "本月家庭结余为负",
        detail: `结余 ${formatAmount(monthlyBalance)}，建议复盘大额支出和固定支出`,
        path: "/transactions",
        severity: "danger",
        icon: <IconExclamationCircle />,
      });
    }
    if (budgetRiskCount > 0) {
      priorityActions.push({
        title: "家庭预算需要关注",
        detail: `${budgetRiskCount} 项预算接近阈值或已超支`,
        path: "/budgets",
        severity: "warning",
        icon: <IconCalendar />,
      });
    }
    if (overdueRecurring.length > 0 || weekRecurring.length > 0) {
      priorityActions.push({
        title: "固定事项临近",
        detail: `逾期 ${overdueRecurring.length} 项，7 天内 ${weekRecurring.length} 项`,
        path: "/recurring",
        severity: overdueRecurring.length > 0 ? "danger" : "notice",
        icon: <IconCalendar />,
      });
    }
    if (openTransfers.length > 0) {
      priorityActions.push({
        title: "公司/家庭往来待闭环",
        detail: `${openTransfers.length} 笔主体资金往来未完成结清`,
        path: "/dashboard",
        severity: "notice",
        icon: <IconSwap />,
      });
    }
    if (priorityActions.length === 0) {
      priorityActions.push({
        title: "家庭资金状态良好",
        detail: "收入、支出、预算和固定事项暂无明显风险",
        path: "/dashboard",
        severity: "success",
        icon: <IconCheckCircle />,
      });
    }

    const dailyChecks = [
      {
        label: "家庭收支已记录",
        done: recentTx.length > 0,
        detail: recentTx.length > 0 ? `最近 ${recentTx.length} 笔` : "本月还没有收支记录",
        path: "/transactions",
      },
      {
        label: "预算执行可控",
        done: budgetRiskCount === 0,
        detail: budgetRiskCount === 0 ? "暂无预算风险" : `${budgetRiskCount} 项需关注`,
        path: "/budgets",
      },
      {
        label: "固定事项跟进",
        done: overdueRecurring.length === 0,
        detail: overdueRecurring.length === 0 ? `${weekRecurring.length} 项 7 天内` : `${overdueRecurring.length} 项逾期`,
        path: "/recurring",
      },
      {
        label: "账户资金充足",
        done: availableBalance >= monthlyExpense,
        detail: `可用资金 ${formatAmount(availableBalance)}`,
        path: "/accounts",
      },
    ];

    return {
      monthlyIncome,
      monthlyExpense,
      monthlyBalance,
      availableBalance,
      budgetUsage: stats?.budgetUsageRate ? stats.budgetUsageRate * 100 : 0,
      fixedIncome,
      fixedExpense,
      score,
      severity,
      priorityActions,
      dailyChecks,
      largeTransactions,
      weekRecurring,
      overdueRecurring,
      openTransfers,
    };
  }, [accountSummary, alerts.length, entityTransfers, recentTx, recurringItems, stats]);

  const householdMetricCards = [
    {
      label: "本月家庭收入",
      value: householdModel.monthlyIncome,
      type: 1 as const,
      icon: <IconSafe />,
      accent: "#10b981",
      helper: `固定收入 ${formatAmount(householdModel.fixedIncome)}`,
    },
    {
      label: "本月家庭支出",
      value: householdModel.monthlyExpense,
      type: 2 as const,
      icon: <IconSwap />,
      accent: "#ef4444",
      helper: `固定支出 ${formatAmount(householdModel.fixedExpense)}`,
    },
    {
      label: "本月家庭结余",
      value: Math.abs(householdModel.monthlyBalance),
      type: householdModel.monthlyBalance >= 0 ? 1 as const : 2 as const,
      icon: <IconDashboard />,
      accent: householdModel.monthlyBalance >= 0 ? "#10b981" : "#ef4444",
      helper: householdModel.monthlyBalance >= 0 ? "当月收入覆盖支出" : "支出高于收入",
    },
    {
      label: "家庭可用资金",
      value: householdModel.availableBalance,
      type: householdModel.availableBalance >= 0 ? 1 as const : 2 as const,
      icon: <IconHome />,
      accent: "#6366f1",
      helper: `预算使用 ${householdModel.budgetUsage.toFixed(0)}%`,
    },
  ];

  if (isHousehold) {
    return (
      <div className="max-w-7xl mx-auto animate-fade-in">
        <div
          className="rounded-2xl p-6 mb-6 relative overflow-hidden"
          style={{ background: "linear-gradient(135deg, #0f766e 0%, #2563eb 58%, #6366f1 100%)" }}
        >
          <div className="relative z-10">
            <div className="mb-2 inline-flex rounded-full bg-white/15 px-3 py-1 text-xs font-medium text-white">
              家庭主体
            </div>
            <h1 className="text-2xl font-bold text-white mb-2">家庭资金工作台</h1>
            <p className="text-white/80">
              {enterpriseSummary?.company?.name || "当前家庭主体"} 的收入、支出、预算、账户和公司往来管理
            </p>
          </div>
          <div className="absolute right-8 top-1/2 -translate-y-1/2 text-8xl opacity-20 text-white">
            <IconHome />
          </div>
        </div>

        <Row gutter={16} className="dashboard-card-row mb-6">
          {householdMetricCards.map((card, index) => (
            <Col key={card.label} xs={12} sm={12} md={6} className="dashboard-card-col">
              <div className="stat-card dashboard-metric-card animate-fade-in" style={{ animationDelay: `${index * 80}ms` }}>
                <div className="flex items-start justify-between mb-3">
                  <span className="dashboard-card-icon" style={{ color: card.accent, backgroundColor: `${card.accent}18` }}>
                    {card.icon}
                  </span>
                  <Tag color={card.type === 1 ? "green" : "red"}>{card.type === 1 ? "流入" : "流出"}</Tag>
                </div>
                <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>{card.label}</div>
                {loading ? (
                  <Skeleton />
                ) : (
                  <>
                    <AmountDisplay amount={card.value} type={card.type} showSign={card.label.includes("结余")} size="large" />
                    <div className="mt-2 truncate text-xs" style={{ color: "var(--text-color-3)" }}>{card.helper}</div>
                  </>
                )}
              </div>
            </Col>
          ))}
        </Row>

        <Card className="mb-6" style={{ borderRadius: 16 }} title={<div className="flex items-center gap-2"><IconHome /><span>家庭资金驾驶舱</span></div>}>
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-[260px_minmax(0,1fr)_320px]">
            <div className="flex min-h-[242px] flex-col items-center justify-center rounded-xl border px-5 py-6" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
              {loading ? (
                <Skeleton />
              ) : (
                <>
                  <Progress type="circle" percent={householdModel.score} width={136} color={severityMeta[householdModel.severity].accent} formatText={() => `${householdModel.score}分`} />
                  <Tag className="mt-4" color={severityMeta[householdModel.severity].color}>{severityMeta[householdModel.severity].label}</Tag>
                  <div className="mt-3 text-center text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
                    综合家庭结余、预算、账户、固定事项和主体往来计算
                  </div>
                </>
              )}
            </div>

            <div className="min-w-0">
              <div className="mb-3 flex items-center justify-between">
                <h3 className="m-0 text-base font-semibold" style={{ color: "var(--text-color-1)" }}>今日家庭待办</h3>
                <Button type="text" size="small" onClick={() => router.push("/transactions")}>家庭收支 <IconRight /></Button>
              </div>
              {loading ? (
                <div className="space-y-3">{[1, 2, 3].map((item) => <Skeleton key={item} style={{ height: 54 }} />)}</div>
              ) : (
                <div className="space-y-3">
                  {householdModel.priorityActions.slice(0, 4).map((action) => {
                    const meta = severityMeta[action.severity];
                    return (
                      <button
                        key={action.title}
                        type="button"
                        onClick={() => router.push(action.path)}
                        className="flex w-full cursor-pointer items-center gap-3 rounded-xl border bg-transparent p-3 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                        style={{ borderColor: "var(--border-color-light)" }}
                      >
                        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl" style={{ backgroundColor: "var(--color-fill-1)", color: meta.accent }}>{action.icon}</span>
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
            </div>

            <div className="min-w-0">
              <div className="mb-3 flex items-center justify-between">
                <h3 className="m-0 text-base font-semibold" style={{ color: "var(--text-color-1)" }}>家庭日清</h3>
                <Tag color="arcoblue">{householdModel.dailyChecks.filter((item) => item.done).length}/{householdModel.dailyChecks.length}</Tag>
              </div>
              {loading ? (
                <div className="space-y-3">{[1, 2, 3, 4].map((item) => <Skeleton key={item} style={{ height: 48 }} />)}</div>
              ) : (
                <div className="space-y-2">
                  {householdModel.dailyChecks.map((item) => (
                    <button
                      key={item.label}
                      type="button"
                      onClick={() => router.push(item.path)}
                      className="flex w-full cursor-pointer items-center gap-3 rounded-xl border bg-transparent px-3 py-2.5 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                      style={{ borderColor: "var(--border-color-light)" }}
                    >
                      <span
                        className="grid h-8 w-8 shrink-0 place-items-center rounded-full"
                        style={{
                          backgroundColor: item.done ? "rgba(16, 185, 129, 0.14)" : "rgba(245, 158, 11, 0.16)",
                          color: item.done ? "var(--color-success)" : "var(--color-warning)",
                        }}
                      >
                        {item.done ? <IconCheckCircle /> : <IconExclamationCircle />}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{item.label}</span>
                        <span className="mt-0.5 block truncate text-xs" style={{ color: "var(--text-color-3)" }}>{item.detail}</span>
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        </Card>

        <Row gutter={16} className="mb-6">
          {[
            { label: "大额收支", value: householdModel.largeTransactions.length, helper: "单笔 1 万以上", icon: <IconExclamationCircle />, accent: "#ef4444" },
            { label: "7 天内固定事项", value: householdModel.weekRecurring.length, helper: `逾期 ${householdModel.overdueRecurring.length} 项`, icon: <IconCalendar />, accent: "#f59e0b" },
            { label: "公司/家庭往来", value: entityTransfers.length, helper: `${householdModel.openTransfers.length} 笔待闭环`, icon: <IconSwap />, accent: "#6366f1" },
            { label: "家庭预算", value: activeBudgets.length, helper: `${alerts.length} 项预警`, icon: <IconStorage />, accent: "#0ea5e9" },
          ].map((card) => (
            <Col key={card.label} xs={12} md={6}>
              <Card style={{ borderRadius: 16, minHeight: 132 }}>
                <div className="flex h-[92px] flex-col justify-between">
                  <div className="flex items-center justify-between">
                    <span className="text-sm" style={{ color: "var(--text-color-3)" }}>{card.label}</span>
                    <span className="grid h-9 w-9 place-items-center rounded-xl" style={{ backgroundColor: `${card.accent}18`, color: card.accent }}>{card.icon}</span>
                  </div>
                  {loading ? <Skeleton /> : <div className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{card.value}</div>}
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{card.helper}</div>
                </div>
              </Card>
            </Col>
          ))}
        </Row>

        <Card className="mb-6" style={{ borderRadius: 16 }}>
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-lg font-semibold" style={{ color: "var(--text-color-1)" }}>家庭快捷入口</h3>
          </div>
          <Row gutter={16}>
            {[
              { icon: <IconEdit />, label: "记一笔", path: "/transactions?action=new", bg: "linear-gradient(135deg, #10b981 0%, #059669 100%)" },
              { icon: <IconSwap />, label: "家庭收支", path: "/transactions", bg: "linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)" },
              { icon: <IconSafe />, label: "家庭账户", path: "/accounts", bg: "linear-gradient(135deg, #0ea5e9 0%, #2563eb 100%)" },
              { icon: <IconCalendar />, label: "家庭预算", path: "/budgets", bg: "linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)" },
              { icon: <IconStorage />, label: "家庭报表", path: "/reports", bg: "linear-gradient(135deg, #f59e0b 0%, #d97706 100%)" },
              { icon: <IconCalendar />, label: "固定事项", path: "/recurring", bg: "linear-gradient(135deg, #14b8a6 0%, #0f766e 100%)" },
            ].map((action, index) => (
              <Col key={action.path} xs={12} sm={6}>
                <div className="quick-action-btn mb-4 animate-fade-in" style={{ animationDelay: `${index * 80 + 160}ms` }} onClick={() => router.push(action.path)}>
                  <div className="icon-wrapper text-2xl" style={{ background: action.bg }}>{action.icon}</div>
                  <span className="text-sm font-medium" style={{ color: "var(--text-color-2)" }}>{action.label}</span>
                </div>
              </Col>
            ))}
          </Row>
        </Card>

        <Row gutter={16}>
          <Col xs={24} md={16}>
            <Card
              style={{ borderRadius: 16 }}
              title={<div className="flex items-center gap-2"><IconStorage /><span>最近家庭收支</span></div>}
              extra={<Button type="text" size="small" onClick={() => router.push("/transactions")} style={{ color: "var(--color-primary)" }}>查看更多 <IconRight /></Button>}
            >
              {loading ? (
                <div className="space-y-4">{[1, 2, 3].map((i) => <Skeleton key={i} style={{ height: 60 }} />)}</div>
              ) : recentTx.length === 0 ? (
                <div className="text-center py-12">
                  <IconEmpty className="mb-4" style={{ fontSize: 42, color: "var(--text-color-4)" }} />
                  <p style={{ color: "var(--text-color-3)" }}>暂无家庭收支记录</p>
                  <Button type="primary" className="mt-4" onClick={() => router.push("/transactions?action=new")}>记一笔</Button>
                </div>
              ) : (
                <div className="space-y-2">
                  {recentTx.map((tx, index) => (
                    <div key={tx.id} className="transaction-item animate-fade-in" style={{ animationDelay: `${index * 50 + 240}ms` }} onClick={() => router.push("/transactions")}>
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-xl flex items-center justify-center text-lg" style={{ backgroundColor: tx.type === 1 ? "#10b98120" : "#ef444420" }}>
                          {tx.categoryIcon || (tx.type === 1 ? "💰" : "💸")}
                        </div>
                        <div>
                          <div className="font-medium text-sm" style={{ color: "var(--text-color-1)" }}>{tx.note || tx.categoryName || t("unnamed")}</div>
                          <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{tx.categoryName} · {formatDate(tx.date)}</div>
                        </div>
                      </div>
                      <AmountDisplay amount={tx.amount} type={tx.type} showSign />
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </Col>

          <Col xs={24} md={8}>
            <Card
              style={{ borderRadius: 16 }}
              title={<div className="flex items-center gap-2"><IconSwap /><span>公司/家庭往来</span></div>}
            >
              {loading ? (
                <div className="space-y-4">{[1, 2].map((i) => <Skeleton key={i} style={{ height: 72 }} />)}</div>
              ) : entityTransfers.length === 0 ? (
                <div className="text-center py-12">
                  <IconSwap className="mb-4" style={{ fontSize: 42, color: "var(--text-color-4)" }} />
                  <p className="font-medium" style={{ color: "var(--text-color-2)" }}>暂无主体往来</p>
                  <p className="text-sm mt-1" style={{ color: "var(--text-color-3)" }}>家庭垫资、公司归还、报销付款会在这里汇总</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {entityTransfers.slice(0, 4).map((transfer) => {
                    const isInbound = activeEntityId === transfer.toEntityId;
                    const amountType: 1 | 2 = isInbound ? 1 : 2;
                    const counterparty = isInbound ? transfer.fromEntityName || "来源主体" : transfer.toEntityName || "目标主体";
                    return (
                      <div key={transfer.id} className="rounded-xl border p-3" style={{ borderColor: "var(--border-color-light)" }}>
                        <div className="mb-2 flex items-center justify-between gap-2">
                          <span className="truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{counterparty}</span>
                          <AmountDisplay amount={transfer.amount} type={amountType} showSign />
                        </div>
                        <div className="truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                          {t(transferTypeLabelKeys[transfer.transferType] || "transferInterEntity")} · {formatDate(transfer.transferDate)}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </Card>
          </Col>
        </Row>
      </div>
    );
  }

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      {/* Welcome banner */}
      <div
        className="rounded-2xl p-6 mb-6 relative overflow-hidden"
        style={{ background: "var(--gradient-primary)" }}
      >
        <div className="relative z-10">
          <h1 className="text-2xl font-bold text-white mb-2">
            {t("welcomeTitle")}
          </h1>
          <p className="text-white/80">
            {enterpriseSummary?.company?.name
              ? t("welcomeSubtitleWithCompany", { company: enterpriseSummary.company.name })
              : t("welcomeSubtitle")}
          </p>
        </div>
        <div className="absolute right-8 top-1/2 -translate-y-1/2 text-8xl opacity-20 text-white">
          <IconDashboard />
        </div>
      </div>

      {/* Stat cards */}
      <Row gutter={16} className="dashboard-card-row mb-6">
        {statCards.map((card, index) => (
          <Col key={index} xs={12} sm={12} md={6} className="dashboard-card-col">
            <div
              className="stat-card dashboard-metric-card animate-fade-in"
              style={{ animationDelay: `${index * 100}ms` }}
            >
              <div className="flex items-start justify-between mb-3">
                <span
                  className="dashboard-card-icon"
                  style={{ color: card.iconColor, backgroundColor: card.iconBg }}
                >
                  {card.icon}
                </span>
                <div
                  className="flex items-center gap-1 text-xs px-2 py-1 rounded-full"
                  style={{
                    backgroundColor: card.trendUp ? "#10b98120" : "#ef444420",
                    color: card.trendUp ? "#10b981" : "#ef4444",
                  }}
                >
                  {card.trendUp ? <IconArrowRise /> : <IconArrowFall />}
                  {card.trend}
                </div>
              </div>
              <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>
                {card.label}
              </div>
              {loading ? (
                <Skeleton />
              ) : (
                <div className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
                  {card.suffix
                    ? `${card.value.toFixed(0)}${card.suffix}`
                    : <AmountDisplay amount={card.value} type={card.type} size="large" />
                  }
                </div>
              )}
            </div>
          </Col>
        ))}
      </Row>

      <Card
        className="mb-6"
        style={{ borderRadius: 16 }}
        title={
          <div className="flex items-center gap-2">
            <IconDashboard />
            <span>经营指挥台</span>
          </div>
        }
      >
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-[260px_minmax(0,1fr)_320px]">
          <div
            className="flex min-h-[242px] flex-col items-center justify-center rounded-xl border px-5 py-6"
            style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
          >
            {loading ? (
              <Skeleton />
            ) : (
              <>
                <Progress
                  type="circle"
                  percent={workspaceModel.workspaceScore}
                  width={136}
                  color={severityMeta[workspaceModel.workspaceSeverity].accent}
                  formatText={() => `${workspaceModel.workspaceScore}分`}
                />
                <Tag className="mt-4" color={severityMeta[workspaceModel.workspaceSeverity].color}>
                  {severityMeta[workspaceModel.workspaceSeverity].label}
                </Tag>
                <div className="mt-3 text-center text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
                  综合经营、财务、税务、HR 和主体往来计算
                </div>
              </>
            )}
          </div>

          <div className="min-w-0">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="m-0 text-base font-semibold" style={{ color: "var(--text-color-1)" }}>今日优先动作</h3>
              <Button type="text" size="small" onClick={() => router.push("/operations")}>
                经营总览 <IconRight />
              </Button>
            </div>
            {loading ? (
              <div className="space-y-3">
                {[1, 2, 3].map((item) => <Skeleton key={item} style={{ height: 54 }} />)}
              </div>
            ) : (
              <div className="space-y-3">
                {workspaceModel.priorityActions.slice(0, 4).map((action) => {
                  const meta = severityMeta[action.severity];
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
                        style={{ backgroundColor: "var(--color-fill-1)", color: meta.accent }}
                      >
                        {action.icon}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate font-semibold" style={{ color: "var(--text-color-1)" }}>
                          {action.title}
                        </span>
                        <span className="mt-1 block truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                          {action.detail}
                        </span>
                      </span>
                      <Tag color={meta.color}>{meta.label}</Tag>
                      <IconRight style={{ color: "var(--text-color-4)" }} />
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          <div className="min-w-0">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="m-0 text-base font-semibold" style={{ color: "var(--text-color-1)" }}>日清检查</h3>
              <Tag color="arcoblue">{workspaceModel.dailyChecks.filter((item) => item.done).length}/{workspaceModel.dailyChecks.length}</Tag>
            </div>
            {loading ? (
              <div className="space-y-3">
                {[1, 2, 3, 4].map((item) => <Skeleton key={item} style={{ height: 48 }} />)}
              </div>
            ) : (
              <div className="space-y-2">
                {workspaceModel.dailyChecks.map((item) => (
                  <button
                    key={item.label}
                    type="button"
                    onClick={() => router.push(item.path)}
                    className="flex w-full cursor-pointer items-center gap-3 rounded-xl border bg-transparent px-3 py-2.5 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                    style={{ borderColor: "var(--border-color-light)" }}
                  >
                    <span
                      className="grid h-8 w-8 shrink-0 place-items-center rounded-full"
                      style={{
                        backgroundColor: item.done ? "rgba(16, 185, 129, 0.14)" : "rgba(245, 158, 11, 0.16)",
                        color: item.done ? "var(--color-success)" : "var(--color-warning)",
                      }}
                    >
                      {item.done ? <IconCheckCircle /> : <IconExclamationCircle />}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{item.label}</span>
                      <span className="mt-0.5 block truncate text-xs" style={{ color: "var(--text-color-3)" }}>{item.detail}</span>
                    </span>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </Card>

      <Card className="mb-6" style={{ borderRadius: 16 }} title="模块健康">
        {loading ? (
          <Row gutter={16}>
            {[1, 2, 3, 4, 5].map((item) => (
              <Col key={item} xs={24} md={8}>
                <Skeleton style={{ height: 96 }} />
              </Col>
            ))}
          </Row>
        ) : (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-5">
            {workspaceModel.moduleHealth.map((module) => {
              const severity: WorkspaceSeverity = module.score >= 85 ? "success" : module.score >= 70 ? "notice" : module.score >= 55 ? "warning" : "danger";
              const meta = severityMeta[severity];
              return (
                <button
                  key={module.title}
                  type="button"
                  onClick={() => router.push(module.path)}
                  className="min-h-[128px] cursor-pointer rounded-xl border bg-transparent p-4 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                  style={{ borderColor: "var(--border-color-light)" }}
                >
                  <div className="mb-3 flex items-start justify-between gap-3">
                    <span
                      className="grid h-10 w-10 shrink-0 place-items-center rounded-xl"
                      style={{ backgroundColor: "var(--color-fill-1)", color: meta.accent }}
                    >
                      {module.icon}
                    </span>
                    <Tag color={meta.color}>{module.score.toFixed(0)}分</Tag>
                  </div>
                  <div className="truncate font-semibold" style={{ color: "var(--text-color-1)" }}>{module.title}</div>
                  <div className="mt-1 line-clamp-2 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{module.detail}</div>
                </button>
              );
            })}
          </div>
        )}
      </Card>

      {/* Enterprise overview */}
      <Row gutter={16} className="dashboard-card-row mb-6">
        <Col xs={12} sm={12} md={6} className="dashboard-card-col">
          <div className="stat-card dashboard-metric-card animate-fade-in">
            <div className="flex items-start justify-between mb-3">
              <span className="dashboard-card-icon" style={{ color: "#4f46e5", backgroundColor: "#6366f118" }}>
                <IconHome />
              </span>
              <span className="text-xs px-2 py-1 rounded-full" style={{ backgroundColor: "#6366f120", color: "#6366f1" }}>
                {t("companyBadge")}
              </span>
            </div>
            <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>{t("companySubject")}</div>
            {loading ? (
              <Skeleton />
            ) : (
              <div>
                <div className="text-base font-semibold truncate" style={{ color: "var(--text-color-1)" }}>
                  {enterpriseSummary?.company?.name || "--"}
                </div>
                <div className="text-xs mt-1 truncate" style={{ color: "var(--text-color-3)" }}>
                  {enterpriseSummary?.company?.taxpayerType || "--"}
                </div>
              </div>
            )}
          </div>
        </Col>
        <Col xs={12} sm={12} md={6} className="dashboard-card-col">
          <div className="stat-card dashboard-metric-card animate-fade-in">
            <div className="flex items-start justify-between mb-3">
              <span className="dashboard-card-icon" style={{ color: "#059669", backgroundColor: "#10b98118" }}>
                <IconUserGroup />
              </span>
              <span className="text-xs px-2 py-1 rounded-full" style={{ backgroundColor: "#10b98120", color: "#10b981" }}>
                {t("peopleBadge")}
              </span>
            </div>
            <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>{t("activeEmployees")}</div>
            {loading ? (
              <Skeleton />
            ) : (
              <div>
                <div className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>
                  {enterpriseSummary?.activeEmployeeCount || 0}
                </div>
                <div className="text-xs mt-1" style={{ color: "var(--text-color-3)" }}>
                  {t("pendingOnboarding")} {enterpriseSummary?.onboardingCount || 0} · {t("hiresThisMonth")} {enterpriseSummary?.hiresThisMonth || 0}
                </div>
              </div>
            )}
          </div>
        </Col>
        <Col xs={12} sm={12} md={6} className="dashboard-card-col">
          <div className="stat-card dashboard-metric-card animate-fade-in">
            <div className="flex items-start justify-between mb-3">
              <span className="dashboard-card-icon" style={{ color: "#dc2626", backgroundColor: "#ef444418" }}>
                <IconIdcard />
              </span>
              <span className="text-xs px-2 py-1 rounded-full" style={{ backgroundColor: "#ef444420", color: "#ef4444" }}>
                {t("peopleCostBadge")}
              </span>
            </div>
            <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>{t("monthlyPeopleCost")}</div>
            {loading ? (
              <Skeleton />
            ) : (
              <AmountDisplay amount={enterpriseSummary?.monthlyPeopleCost || 0} type={2} size="large" />
            )}
          </div>
        </Col>
        <Col xs={12} sm={12} md={6} className="dashboard-card-col">
          <div className="stat-card dashboard-metric-card animate-fade-in">
            <div className="flex items-start justify-between mb-3">
              <span className="dashboard-card-icon" style={{ color: "#d97706", backgroundColor: "#f59e0b18" }}>
                <IconFile />
              </span>
              <span className="text-xs px-2 py-1 rounded-full" style={{ backgroundColor: "#f59e0b20", color: "#d97706" }}>
                {t("taxBadge")}
              </span>
            </div>
            <div className="text-sm mb-1" style={{ color: "var(--text-color-3)" }}>{t("pendingTax")}</div>
            {loading ? (
              <Skeleton />
            ) : (
              <div>
                <AmountDisplay amount={enterpriseSummary?.pendingTaxAmount || 0} type={2} size="large" />
                <div className="text-xs mt-1" style={{ color: "var(--text-color-3)" }}>
                  {t("dueDate")} {enterpriseSummary?.nextTaxDueDate || "--"}
                </div>
              </div>
            )}
          </div>
        </Col>
      </Row>

      {/* Entity transfers */}
      <Card
        className="mb-6"
        style={{ borderRadius: 16 }}
        title={
          <div className="flex items-center gap-2">
            <IconSwap />
            <span>{t("entityTransfers")}</span>
          </div>
        }
      >
        {loading ? (
          <Row gutter={16}>
            {[1, 2].map((item) => (
              <Col key={item} xs={24} md={12}>
                <Skeleton style={{ height: 96 }} />
              </Col>
            ))}
          </Row>
        ) : entityTransfers.length === 0 ? (
          <div className="text-center py-10">
            <IconSwap className="mb-4" style={{ fontSize: 42, color: "var(--text-color-4)" }} />
            <p className="font-medium" style={{ color: "var(--text-color-2)" }}>
              {t("noEntityTransfers")}
            </p>
            <p className="text-sm mt-1" style={{ color: "var(--text-color-3)" }}>
              {t("entityTransfersDescription")}
            </p>
          </div>
        ) : (
          <Row gutter={16}>
            {entityTransfers.slice(0, 4).map((transfer, index) => {
              const isInbound = activeEntityId === transfer.toEntityId;
              const isOutbound = activeEntityId === transfer.fromEntityId;
              const amountType: 1 | 2 | undefined = isInbound ? 1 : isOutbound ? 2 : undefined;
              const direction = isInbound ? t("inbound") : isOutbound ? t("outbound") : t("transfer");
              const counterparty = isInbound
                ? transfer.fromEntityName || t("sourceEntity")
                : isOutbound
                  ? transfer.toEntityName || t("targetEntity")
                  : `${transfer.fromEntityName || t("sourceEntity")} → ${transfer.toEntityName || t("targetEntity")}`;

              return (
                <Col key={transfer.id} xs={24} md={12}>
                  <div
                    className="mb-4 rounded-xl border p-4 transition-all hover:shadow-md animate-fade-in"
                    style={{ borderColor: "var(--border-color)", animationDelay: `${index * 80}ms` }}
                  >
                    <div className="flex items-start justify-between gap-4">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 mb-2">
                          <span
                            className="text-xs px-2 py-1 rounded-full shrink-0"
                            style={{
                              backgroundColor: isInbound ? "#10b98120" : isOutbound ? "#ef444420" : "#6366f120",
                              color: isInbound ? "#10b981" : isOutbound ? "#ef4444" : "#6366f1",
                            }}
                          >
                            {direction}
                          </span>
                          <span className="text-sm font-semibold truncate" style={{ color: "var(--text-color-1)" }}>
                            {counterparty}
                          </span>
                        </div>
                        <div className="text-xs" style={{ color: "var(--text-color-3)" }}>
                          {t(transferTypeLabelKeys[transfer.transferType] || "transferInterEntity")} · {formatDate(transfer.transferDate)}
                        </div>
                        {transfer.note ? (
                          <div className="text-sm mt-3 line-clamp-2" style={{ color: "var(--text-color-2)" }}>
                            {transfer.note}
                          </div>
                        ) : null}
                      </div>
                      <span className="shrink-0 text-right">
                        <AmountDisplay
                          amount={transfer.amount}
                          type={amountType}
                          showSign={amountType !== undefined}
                          currency={transfer.currency}
                        />
                      </span>
                    </div>
                  </div>
                </Col>
              );
            })}
          </Row>
        )}
      </Card>

      {/* Quick actions */}
      <Card className="mb-6" style={{ borderRadius: 16 }}>
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold" style={{ color: "var(--text-color-1)" }}>
            {t("quickActions")}
          </h3>
        </div>
        <Row gutter={16}>
          {quickActions.map((action, index) => (
            <Col key={action.path} xs={12} sm={6}>
              <div
                className="quick-action-btn mb-4 animate-fade-in"
                style={{ animationDelay: `${index * 100 + 200}ms` }}
                onClick={() => router.push(action.path)}
              >
                <div
                  className="icon-wrapper text-2xl"
                  style={{ background: action.bg }}
                >
                  {action.icon}
                </div>
                <span className="text-sm font-medium" style={{ color: "var(--text-color-2)" }}>
                  {action.label}
                </span>
              </div>
            </Col>
          ))}
        </Row>
      </Card>

      <Row gutter={16}>
        {/* Recent transactions */}
        <Col xs={24} md={16}>
          <Card
            style={{ borderRadius: 16 }}
            title={
              <div className="flex items-center gap-2">
                <IconStorage />
                <span>{t("recentTransactions")}</span>
              </div>
            }
            extra={
              <Button
                type="text"
                size="small"
                onClick={() => router.push("/transactions")}
                style={{ color: "var(--color-primary)" }}
              >
                {t("viewMore")} <IconRight />
              </Button>
            }
          >
            {loading ? (
              <div className="space-y-4">
                {[1, 2, 3].map((i) => (
                  <Skeleton key={i} style={{ height: 60 }} />
                ))}
              </div>
            ) : recentTx.length === 0 ? (
              <div className="text-center py-12">
                <IconEmpty className="mb-4" style={{ fontSize: 42, color: "var(--text-color-4)" }} />
                <p style={{ color: "var(--text-color-3)" }}>{t("noRecentTransactions")}</p>
                <Button
                  type="primary"
                  className="mt-4"
                  onClick={() => router.push("/transactions?action=new")}
                >
                  {t("quickNewRecord")}
                </Button>
              </div>
            ) : (
              <div className="space-y-2">
                {recentTx.map((tx, index) => (
                  <div
                    key={tx.id}
                    className="transaction-item animate-fade-in"
                    style={{ animationDelay: `${index * 50 + 300}ms` }}
                    onClick={() => router.push("/transactions")}
                  >
                    <div className="flex items-center gap-3">
                      <div
                        className="w-10 h-10 rounded-xl flex items-center justify-center text-lg"
                        style={{
                          backgroundColor: tx.type === 1 ? "#10b98120" : "#ef444420",
                        }}
                      >
                        {tx.categoryIcon || (tx.type === 1 ? "💰" : "💸")}
                      </div>
                      <div>
                        <div className="font-medium text-sm" style={{ color: "var(--text-color-1)" }}>
                          {tx.note || tx.categoryName || t("unnamed")}
                        </div>
                        <div className="text-xs" style={{ color: "var(--text-color-3)" }}>
                          {tx.categoryName} · {formatDate(tx.date)}
                        </div>
                      </div>
                    </div>
                    <AmountDisplay amount={tx.amount} type={tx.type} showSign />
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>

        {/* Budget alerts */}
        <Col xs={24} md={8}>
          <Card
            style={{ borderRadius: 16 }}
            title={
              <div className="flex items-center gap-2">
                <IconExclamationCircle />
                <span>{t("budgetAlerts")}</span>
              </div>
            }
            extra={
              <Button
                type="text"
                size="small"
                onClick={() => router.push("/budgets")}
                style={{ color: "var(--color-primary)" }}
              >
                {t("manage")} <IconRight />
              </Button>
            }
          >
            {loading ? (
              <div className="space-y-4">
                {[1, 2].map((i) => (
                  <Skeleton key={i} style={{ height: 80 }} />
                ))}
              </div>
            ) : alerts.length === 0 ? (
              <div className="text-center py-12">
                <IconCheckCircle className="mb-4" style={{ fontSize: 42, color: "var(--color-success)" }} />
                <p className="font-medium" style={{ color: "var(--text-color-2)" }}>
                  {t("budgetHealthy")}
                </p>
                <p className="text-sm mt-1" style={{ color: "var(--text-color-3)" }}>
                  {t("noBudgetAlerts")}
                </p>
              </div>
            ) : (
              <div className="space-y-4">
                {alerts.map((budget) => (
                  <div
                    key={budget.id}
                    className="p-4 rounded-xl border cursor-pointer hover:shadow-md transition-all"
                    style={{ borderColor: "var(--border-color)" }}
                    onClick={() => router.push("/budgets")}
                  >
                    <div className="flex items-center justify-between mb-2">
                      <span className="font-medium text-sm">{budget.name}</span>
                      <span
                        className="text-xs px-2 py-1 rounded-full"
                        style={{
                          backgroundColor: budget.riskLevel === "critical" ? "#ef444420" : "#f59e0b20",
                          color: budget.riskLevel === "critical" ? "#ef4444" : "#f59e0b",
                        }}
                      >
                        {budget.riskLevel === "critical" ? t("overrun") : t("warning")}
                      </span>
                    </div>
                    <BudgetProgress
                      spent={budget.spent}
                      amount={budget.amount}
                      usageRate={budget.usageRate}
                      warningThreshold={budget.warningThreshold}
                      riskLevel={budget.riskLevel}
                    />
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
