"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Alert, Button, Card, Empty, Message, Progress, Skeleton, Tag } from "@arco-design/web-react";
import {
  IconArrowFall,
  IconArrowRise,
  IconCalendar,
  IconCheckCircle,
  IconDashboard,
  IconExclamationCircle,
  IconPlus,
  IconRefresh,
  IconRight,
  IconSafe,
  IconSwap,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import { budgetApi } from "@/lib/api/budgets";
import { recurringApi, type RecurringItem } from "@/lib/api/recurring";
import { statsApi } from "@/lib/api/stats";
import { transactionApi } from "@/lib/api/transactions";
import { formatAmount, formatDate } from "@/lib/utils/format";
import type { AdvancedInsight, Budget, CategoryStat, OverviewStats, Transaction, TrendPoint } from "@/lib/types";

type OperationsView = {
  overview: OverviewStats | null;
  trend: TrendPoint[];
  incomeCategories: CategoryStat[];
  expenseCategories: CategoryStat[];
  insights: AdvancedInsight | null;
  budgets: Budget[];
  transactions: Transaction[];
  recurringItems: RecurringItem[];
};

type OperationAction = {
  title: string;
  detail: string;
  path: string;
  severity: "success" | "notice" | "warning" | "danger";
  icon: ReactNode;
};

const emptyView: OperationsView = {
  overview: null,
  trend: [],
  incomeCategories: [],
  expenseCategories: [],
  insights: null,
  budgets: [],
  transactions: [],
  recurringItems: [],
};

const largeTransactionThreshold = 10000;
const DAY = 24 * 60 * 60 * 1000;

const statusMeta: Record<OperationAction["severity"], { label: string; color: string }> = {
  success: { label: "正常", color: "green" },
  notice: { label: "关注", color: "arcoblue" },
  warning: { label: "预警", color: "orange" },
  danger: { label: "风险", color: "red" },
};

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function toIsoDate(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function currentMonthRange() {
  const now = new Date();
  return {
    startDate: toIsoDate(new Date(now.getFullYear(), now.getMonth(), 1)),
    endDate: toIsoDate(now),
  };
}

function daysUntil(date?: string | null) {
  if (!date) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const target = new Date(`${date.slice(0, 10)}T00:00:00`);
  if (Number.isNaN(target.getTime())) return null;
  return Math.ceil((target.getTime() - today.getTime()) / DAY);
}

function clamp(value: number, min = 0, max = 100) {
  return Math.max(min, Math.min(value, max));
}

function dueText(date?: string | null) {
  const days = daysUntil(date);
  if (days === null) return "无截止日";
  if (days < 0) return `逾期 ${Math.abs(days)} 天`;
  if (days === 0) return "今日截止";
  return `${days} 天后`;
}

function categoryColor(index: number, type: "income" | "expense") {
  const incomeColors = ["#10b981", "#22c55e", "#14b8a6", "#0ea5e9"];
  const expenseColors = ["#e5484d", "#b86a52", "#7c5cc4", "#4f6bdc"];
  return (type === "income" ? incomeColors : expenseColors)[index % 4];
}

type OperationsLoadResult = {
  view: OperationsView;
  failedSections: string[];
};

const loadOperationsView = async (): Promise<OperationsLoadResult> => {
  const range = currentMonthRange();
  const [
    overviewResult,
    trendResult,
    incomeResult,
    expenseResult,
    insightsResult,
    budgetsResult,
    transactionsResult,
    recurringResult,
  ] = await Promise.allSettled([
    statsApi.overview(),
    statsApi.trend({ period: "month", limit: 6 }),
    statsApi.category({ type: "income", ...range }),
    statsApi.category({ type: "expense", ...range }),
    statsApi.insights(),
    budgetApi.list({ page: 0, size: 500 }),
    transactionApi.list({ page: 0, size: 12 }),
    recurringApi.list(),
  ]);

  const namedResults = [
    ["月度经营数据", overviewResult],
    ["趋势", trendResult],
    ["收入分类", incomeResult],
    ["成本分类", expenseResult],
    ["经营洞察", insightsResult],
    ["预算", budgetsResult],
    ["经营流水", transactionsResult],
    ["周期事项", recurringResult],
  ] as const;

  return {
    view: {
      overview: overviewResult.status === "fulfilled" ? overviewResult.value.data : null,
      trend: trendResult.status === "fulfilled" ? trendResult.value.data : [],
      incomeCategories: incomeResult.status === "fulfilled" ? incomeResult.value.data : [],
      expenseCategories: expenseResult.status === "fulfilled" ? expenseResult.value.data : [],
      insights: insightsResult.status === "fulfilled" ? insightsResult.value.data : null,
      budgets: budgetsResult.status === "fulfilled" ? budgetsResult.value.data.content : [],
      transactions: transactionsResult.status === "fulfilled" ? transactionsResult.value.data.content : [],
      recurringItems: recurringResult.status === "fulfilled" ? recurringResult.value.data : [],
    },
    failedSections: namedResults
      .filter(([, result]) => result.status === "rejected")
      .map(([name]) => name),
  };
};

export default function OperationsPage() {
  const router = useRouter();
  const [view, setView] = useState<OperationsView>(emptyView);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [failedSections, setFailedSections] = useState<string[]>([]);

  const loadData = async (quiet = false) => {
    if (quiet) setRefreshing(true);
    try {
      const result = await loadOperationsView();
      setView(result.view);
      setFailedSections(result.failedSections);
      if (quiet) {
        if (result.failedSections.length > 0) Message.warning("部分经营数据未能刷新");
        else Message.success("经营总览已刷新");
      }
    } catch {
      Message.error("经营总览加载失败");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      setLoading(true);
      const result = await loadOperationsView();
      if (!cancelled) {
        setView(result.view);
        setFailedSections(result.failedSections);
        setLoading(false);
      }
    };

    void load().catch(() => {
      if (!cancelled) {
        Message.error("经营总览加载失败");
        setLoading(false);
      }
    });

    return () => {
      cancelled = true;
    };
  }, []);

  const model = useMemo(() => {
    const income = Number(view.overview?.monthlyIncome || 0);
    const expense = Number(view.overview?.monthlyExpense || 0);
    const profit = Number(view.overview?.monthlyBalance ?? income - expense);
    const profitMargin = income > 0 ? profit / income : 0;
    const budgetTotal = view.budgets.reduce((sum, budget) => sum + Number(budget.amount || 0), 0);
    const budgetSpent = view.budgets.reduce((sum, budget) => sum + Number(budget.spent || 0), 0);
    const budgetUsage = budgetTotal > 0 ? budgetSpent / budgetTotal : Number(view.overview?.budgetUsageRate || 0);
    const budgetRisks = view.budgets.filter((budget) =>
      budget.warningReached || budget.riskLevel === "high" || budget.riskLevel === "critical" || budget.status === 3
    );
    const largeTransactions = view.transactions.filter((transaction) => Number(transaction.amount || 0) >= largeTransactionThreshold);
    const reviewTransactions = view.transactions.filter((transaction) =>
      Number(transaction.amount || 0) >= largeTransactionThreshold
      || !transaction.note?.trim()
      || (transaction.type === 2 && transaction.isRefundable)
    );
    const activeRecurring = view.recurringItems.filter((item) => item.status === 1);
    const overdueRecurring = activeRecurring.filter((item) => {
      const days = daysUntil(item.nextExecution);
      return days !== null && days < 0;
    });
    const upcomingRecurring = activeRecurring.filter((item) => {
      const days = daysUntil(item.nextExecution);
      return days !== null && days >= 0 && days <= 7;
    });
    const recurringIncome = activeRecurring
      .filter((item) => item.type === 1)
      .reduce((sum, item) => sum + Number(item.amount || 0), 0);
    const recurringExpense = activeRecurring
      .filter((item) => item.type === 2)
      .reduce((sum, item) => sum + Number(item.amount || 0), 0);
    const trendProfit = view.trend.map((item) => Number(item.balance || 0));
    const lastTrend = view.trend.at(-1);
    const previousTrend = view.trend.at(-2);
    const profitChange = previousTrend ? Number(lastTrend?.balance || 0) - Number(previousTrend.balance || 0) : 0;
    const bestIncomeCategory = [...view.incomeCategories].sort((a, b) => Number(b.amount || 0) - Number(a.amount || 0))[0];
    const biggestExpenseCategory = [...view.expenseCategories].sort((a, b) => Number(b.amount || 0) - Number(a.amount || 0))[0];

    const deductions = [
      profit < 0 ? 24 : profitMargin < 0.15 ? 10 : 0,
      Math.min(budgetRisks.length * 8, 24),
      budgetUsage >= 1 ? 18 : budgetUsage >= 0.85 ? 10 : 0,
      Math.min(overdueRecurring.length * 10, 20),
      Math.min(reviewTransactions.length * 3, 15),
      profitChange < 0 ? 6 : 0,
    ];
    const score = clamp(100 - deductions.reduce((sum, value) => sum + value, 0));
    const severity: OperationAction["severity"] =
      score >= 85 ? "success" : score >= 70 ? "notice" : score >= 55 ? "warning" : "danger";

    const actions: OperationAction[] = [];
    if (profit < 0) {
      actions.push({
        title: "本月经营利润为负",
        detail: `净额 ${formatAmount(profit)}，建议优先复盘成本结构`,
        path: "/reports",
        severity: "danger",
        icon: <IconExclamationCircle />,
      });
    }
    if (budgetRisks.length > 0) {
      actions.push({
        title: "预算执行存在偏差",
        detail: `${budgetRisks.length} 项预算触发预警或超支`,
        path: "/budgets",
        severity: "warning",
        icon: <IconCalendar />,
      });
    }
    if (overdueRecurring.length > 0) {
      actions.push({
        title: "周期事项已逾期",
        detail: `${overdueRecurring.length} 个经营事项需要立即处理`,
        path: "/recurring",
        severity: "danger",
        icon: <IconCalendar />,
      });
    }
    if (upcomingRecurring.length > 0) {
      actions.push({
        title: "7 天内有周期事项",
        detail: `${upcomingRecurring.length} 个事项即将执行`,
        path: "/recurring",
        severity: "notice",
        icon: <IconCheckCircle />,
      });
    }
    if (reviewTransactions.length > 0) {
      actions.push({
        title: "经营流水需要复核",
        detail: `${reviewTransactions.length} 笔流水含大额、退款或备注缺失`,
        path: "/transactions?view=large",
        severity: "warning",
        icon: <IconSwap />,
      });
    }
    if (actions.length === 0) {
      actions.push({
        title: failedSections.length > 0 ? "部分经营数据暂不可用" : "经营节奏稳定",
        detail: failedSections.length > 0
          ? "无法在数据不完整时判断经营状态，请先重新加载"
          : "当前收入、预算、周期事项没有明显待处理风险",
        path: "/operations",
        severity: failedSections.length > 0 ? "warning" : "success",
        icon: failedSections.length > 0 ? <IconExclamationCircle /> : <IconCheckCircle />,
      });
    }

    return {
      income,
      expense,
      profit,
      profitMargin,
      budgetTotal,
      budgetSpent,
      budgetUsage,
      budgetRisks,
      largeTransactions,
      reviewTransactions,
      activeRecurring,
      overdueRecurring,
      upcomingRecurring,
      recurringIncome,
      recurringExpense,
      trendProfit,
      lastTrend,
      previousTrend,
      profitChange,
      bestIncomeCategory,
      biggestExpenseCategory,
      score,
      severity,
      actions,
    };
  }, [failedSections.length, view]);

  const healthUnavailable = ["月度经营数据", "趋势", "预算", "经营流水", "周期事项"]
    .some((section) => failedSections.includes(section));

  const metricCards = [
    {
      label: "本月收入",
      value: <AmountDisplay amount={model.income} type={1} size="large" />,
      hint: failedSections.includes("收入分类")
        ? "分类数据暂不可用"
        : model.bestIncomeCategory ? `最大来源：${model.bestIncomeCategory.categoryName}` : "暂无收入分类",
      icon: <IconArrowRise />,
      accent: "var(--color-success)",
      unavailable: failedSections.includes("月度经营数据"),
    },
    {
      label: "本月成本",
      value: <AmountDisplay amount={model.expense} type={2} size="large" />,
      hint: failedSections.includes("成本分类")
        ? "分类数据暂不可用"
        : model.biggestExpenseCategory ? `最大成本：${model.biggestExpenseCategory.categoryName}` : "暂无成本分类",
      icon: <IconArrowFall />,
      accent: "var(--color-danger)",
      unavailable: failedSections.includes("月度经营数据"),
    },
    {
      label: "经营利润",
      value: <AmountDisplay amount={Math.abs(model.profit)} type={model.profit >= 0 ? 1 : 2} showSign size="large" />,
      hint: `利润率 ${(model.profitMargin * 100).toFixed(1)}%`,
      icon: <IconSafe />,
      accent: model.profit >= 0 ? "var(--color-success)" : "var(--color-danger)",
      unavailable: failedSections.includes("月度经营数据"),
    },
    {
      label: "经营健康度",
      value: <span className="text-2xl font-bold">{model.score.toFixed(0)}分</span>,
      hint: statusMeta[model.severity].label,
      icon: <IconDashboard />,
      accent: model.severity === "danger" ? "var(--color-danger)" : "var(--color-primary)",
      unavailable: healthUnavailable,
    },
  ];

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="经营总览"
        subtitle="收入增长、成本结构、预算偏差和周期事项集中管理"
        icon={<IconDashboard />}
        extra={
          <div className="flex items-center gap-2">
            {refreshing && <Tag color="arcoblue">刷新中</Tag>}
            <Button icon={<IconRefresh />} onClick={() => loadData(true)}>
              刷新
            </Button>
            <Button type="primary" icon={<IconPlus />} onClick={() => router.push("/transactions?action=new")}>
              录入流水
            </Button>
          </div>
        }
      />

      {failedSections.length > 0 && (
        <Alert
          className="mb-6"
          type="warning"
          title="部分经营数据未能加载"
          content={`${failedSections.join("、")} 暂不可用；缺失内容不会被当作 0 或“无风险”。`}
          action={<Button size="small" icon={<IconRefresh />} loading={refreshing} onClick={() => void loadData(true)}>重新加载</Button>}
        />
      )}

      <div className="metric-grid metric-wrap-until-xl grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4">
        {metricCards.map((card) => (
          <Card className="metric-card" key={card.label} style={{ borderRadius: 12, minHeight: 136 }}>
            <div className="flex h-full min-h-[96px] flex-col justify-between">
              <div className="flex items-start justify-between">
                <span className="text-sm" style={{ color: "var(--text-color-3)" }}>{card.label}</span>
                <span className="inline-flex text-xl" style={{ color: card.accent }}>{card.icon}</span>
              </div>
              {loading ? (
                <Skeleton />
              ) : (
                <div>
                  <div style={{ color: "var(--text-color-1)" }}>{card.unavailable ? <span className="text-2xl font-bold">--</span> : card.value}</div>
                  <div className="mt-2 truncate text-xs" style={{ color: "var(--text-color-3)" }}>{card.unavailable ? "数据暂不可用" : card.hint}</div>
                </div>
              )}
            </div>
          </Card>
        ))}
      </div>

      <div className="mb-6 grid grid-cols-1 gap-4 xl:grid-cols-[1.08fr_0.92fr]">
        <Card style={{ borderRadius: 12 }} title="经营健康看板">
          {loading ? (
            <Skeleton />
          ) : healthUnavailable ? (
            <div className="flex min-h-[224px] flex-col items-center justify-center px-6 text-center">
              <IconExclamationCircle style={{ color: "var(--color-warning)", fontSize: 34 }} />
              <div className="mt-4 font-semibold">暂不计算经营健康度</div>
              <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>数据不完整，避免输出误导性结论</div>
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-[240px_1fr]">
              <div
                className="flex min-h-[224px] flex-col items-center justify-center rounded-xl border px-5 py-6"
                style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
              >
                <Progress
                  type="circle"
                  percent={model.score}
                  width={132}
                  color={model.severity === "danger" ? "var(--color-danger)" : "var(--color-primary)"}
                  formatText={() => `${model.score.toFixed(0)}分`}
                />
                <Tag className="mt-4" color={statusMeta[model.severity].color}>
                  {statusMeta[model.severity].label}
                </Tag>
                <div className="mt-3 text-center text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
                  由利润、预算、周期事项、大额流水和趋势变化综合计算
                </div>
              </div>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>预算使用率</div>
                  <div className="mt-2 text-2xl font-bold" style={{ color: model.budgetUsage >= 1 ? "var(--color-danger)" : "var(--text-color-1)" }}>
                    {(model.budgetUsage * 100).toFixed(1)}%
                  </div>
                  <div className="mt-3 h-2 overflow-hidden rounded-full" style={{ backgroundColor: "var(--border-color-light)" }}>
                    <div
                      className="h-full rounded-full"
                      style={{
                        width: `${clamp(model.budgetUsage * 100)}%`,
                        backgroundColor: model.budgetUsage >= 0.85 ? "var(--color-warning)" : "var(--color-primary)",
                      }}
                    />
                  </div>
                </div>
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>周期净额</div>
                  <div className="mt-2 text-2xl font-bold" style={{ color: model.recurringIncome - model.recurringExpense >= 0 ? "var(--color-success)" : "var(--color-danger)" }}>
                    {formatAmount(model.recurringIncome - model.recurringExpense)}
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    收入 {formatAmount(model.recurringIncome)} · 支出 {formatAmount(model.recurringExpense)}
                  </div>
                </div>
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>待复核流水</div>
                  <div className="mt-2 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{model.reviewTransactions.length}</div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    大额 {model.largeTransactions.length} 笔
                  </div>
                </div>
                <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
                  <div className="text-sm" style={{ color: "var(--text-color-3)" }}>周期事项风险</div>
                  <div className="mt-2 text-2xl font-bold" style={{ color: model.overdueRecurring.length > 0 ? "var(--color-danger)" : "var(--text-color-1)" }}>
                    {model.overdueRecurring.length + model.upcomingRecurring.length}
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    逾期 {model.overdueRecurring.length} · 7 天内 {model.upcomingRecurring.length}
                  </div>
                </div>
              </div>
            </div>
          )}
        </Card>

        <Card
          style={{ borderRadius: 12 }}
          title="经营动作"
          extra={<Button type="text" size="small" onClick={() => router.push("/reports")}>经营报表</Button>}
        >
          {loading ? (
            <Skeleton />
          ) : (
            <div className="space-y-3">
              {model.actions.slice(0, 5).map((action) => {
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
        <Card
          className={!loading && !failedSections.includes("收入分类") && view.incomeCategories.length === 0 ? "bi-compact-empty" : undefined}
          style={{ borderRadius: 12 }}
          title="收入来源"
        >
          {loading ? (
            <Skeleton />
          ) : failedSections.includes("收入分类") ? (
            <Empty description="收入分类数据暂不可用" />
          ) : view.incomeCategories.length === 0 ? (
            <Empty description="暂无收入分类" />
          ) : (
            <div className="space-y-3">
              {view.incomeCategories.slice(0, 5).map((item, index) => {
                const percent = Number(item.percentage || 0);
                return (
                  <button
                    key={item.categoryId}
                    type="button"
                    onClick={() => router.push(`/transactions?type=1&categoryId=${item.categoryId}`)}
                    className="w-full cursor-pointer rounded-xl border bg-transparent p-3 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                    style={{ borderColor: "var(--border-color-light)" }}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex min-w-0 items-center gap-2 font-medium" style={{ color: "var(--text-color-1)" }}>
                          <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: categoryColor(index, "income") }} />
                          <span className="truncate">{item.categoryName}</span>
                        </div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{item.count} 笔 · {percent.toFixed(1)}%</div>
                      </div>
                      <AmountDisplay amount={item.amount} type={1} size="small" />
                    </div>
                    <div className="mt-3 h-2 overflow-hidden rounded-full" style={{ backgroundColor: "var(--border-color-light)" }}>
                      <div className="h-full rounded-full" style={{ width: `${clamp(percent)}%`, backgroundColor: categoryColor(index, "income") }} />
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </Card>

        <Card
          className={!loading && !failedSections.includes("成本分类") && view.expenseCategories.length === 0 ? "bi-compact-empty" : undefined}
          style={{ borderRadius: 12 }}
          title="成本结构"
        >
          {loading ? (
            <Skeleton />
          ) : failedSections.includes("成本分类") ? (
            <Empty description="成本分类数据暂不可用" />
          ) : view.expenseCategories.length === 0 ? (
            <Empty description="暂无成本分类" />
          ) : (
            <div className="space-y-3">
              {view.expenseCategories.slice(0, 5).map((item, index) => {
                const percent = Number(item.percentage || 0);
                return (
                  <button
                    key={item.categoryId}
                    type="button"
                    onClick={() => router.push(`/transactions?type=2&categoryId=${item.categoryId}`)}
                    className="w-full cursor-pointer rounded-xl border bg-transparent p-3 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                    style={{ borderColor: "var(--border-color-light)" }}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex min-w-0 items-center gap-2 font-medium" style={{ color: "var(--text-color-1)" }}>
                          <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: categoryColor(index, "expense") }} />
                          <span className="truncate">{item.categoryName}</span>
                        </div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{item.count} 笔 · {percent.toFixed(1)}%</div>
                      </div>
                      <AmountDisplay amount={item.amount} type={2} size="small" />
                    </div>
                    <div className="mt-3 h-2 overflow-hidden rounded-full" style={{ backgroundColor: "var(--border-color-light)" }}>
                      <div className="h-full rounded-full" style={{ width: `${clamp(percent)}%`, backgroundColor: categoryColor(index, "expense") }} />
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </Card>

        <Card style={{ borderRadius: 12 }} title="周期事项">
          {loading ? (
            <Skeleton />
          ) : failedSections.includes("周期事项") ? (
            <Empty description="周期事项数据暂不可用" />
          ) : model.activeRecurring.length === 0 ? (
            <Empty description="暂无启用周期事项" />
          ) : (
            <div className="space-y-3">
              {[...model.overdueRecurring, ...model.upcomingRecurring, ...model.activeRecurring]
                .filter((item, index, list) => list.findIndex((target) => target.id === item.id) === index)
                .slice(0, 6)
                .map((item) => {
                  const days = daysUntil(item.nextExecution);
                  return (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => router.push("/recurring")}
                      className="flex w-full cursor-pointer items-center justify-between gap-3 rounded-xl border bg-transparent p-3 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                      style={{ borderColor: "var(--border-color-light)" }}
                    >
                      <div className="min-w-0">
                        <div className="truncate font-medium" style={{ color: "var(--text-color-1)" }}>{item.name}</div>
                        <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                          {item.nextExecution} · {item.type === 1 ? "周期收入" : "周期支出"}
                        </div>
                      </div>
                      <div className="shrink-0 text-right">
                        <AmountDisplay amount={item.amount} type={item.type} size="small" />
                        <div className="mt-1 text-xs" style={{ color: days !== null && days < 0 ? "var(--color-danger)" : "var(--text-color-3)" }}>
                          {dueText(item.nextExecution)}
                        </div>
                      </div>
                    </button>
                  );
                })}
            </div>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[0.95fr_1.05fr]">
        <Card
          style={{ borderRadius: 12 }}
          title="预算偏差"
          extra={<Button type="text" size="small" onClick={() => router.push("/budgets")}>查看预算</Button>}
        >
          {loading ? (
            <Skeleton />
          ) : failedSections.includes("预算") ? (
            <Empty description="预算数据暂不可用" />
          ) : model.budgetRisks.length === 0 ? (
            <Empty description="暂无预算风险" />
          ) : (
            <div className="space-y-3">
              {model.budgetRisks.slice(0, 5).map((budget) => (
                <button
                  key={budget.id}
                  type="button"
                  onClick={() => router.push("/budgets")}
                  className="w-full cursor-pointer rounded-xl border bg-transparent p-3 text-left transition-colors hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                  style={{ borderColor: "var(--border-color-light)" }}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <div className="truncate font-semibold" style={{ color: "var(--text-color-1)" }}>{budget.name}</div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        {budget.startDate} 至 {budget.endDate}
                      </div>
                    </div>
                    <Tag color={budget.riskLevel === "critical" || budget.riskLevel === "high" ? "red" : "orange"}>
                      {(budget.usageRate * 100).toFixed(0)}%
                    </Tag>
                  </div>
                  <div className="mt-3 h-2 overflow-hidden rounded-full" style={{ backgroundColor: "var(--border-color-light)" }}>
                    <div
                      className="h-full rounded-full"
                      style={{
                        width: `${clamp(budget.usageRate * 100)}%`,
                        backgroundColor: budget.riskLevel === "critical" || budget.riskLevel === "high"
                          ? "var(--color-danger)"
                          : "var(--color-warning)",
                      }}
                    />
                  </div>
                  <div className="mt-2 flex justify-between text-xs" style={{ color: "var(--text-color-3)" }}>
                    <span>已用 {formatAmount(budget.spent)}</span>
                    <span>剩余 {formatAmount(budget.remainingAmount)}</span>
                  </div>
                </button>
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
                  {view.transactions.map((transaction) => (
                    <tr key={transaction.id} className="border-b" style={{ borderColor: "var(--border-color-light)" }}>
                      <td className="px-3 py-3 align-middle">
                        <div className="truncate font-medium" style={{ color: "var(--text-color-1)" }}>{transaction.note || "未命名流水"}</div>
                        {Number(transaction.amount || 0) >= largeTransactionThreshold && <Tag className="mt-1" size="small" color="orange">大额</Tag>}
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
