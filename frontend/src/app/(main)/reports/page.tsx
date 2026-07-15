"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Alert, Button, Card, DatePicker, Drawer, Progress, Radio, Select, Skeleton, Tabs, Tag } from "@arco-design/web-react";
import {
  IconCalendar,
  IconDashboard,
  IconExclamationCircle,
  IconFile,
  IconSafe,
  IconSearch,
} from "@arco-design/web-react/icon";
import ReactECharts from "echarts-for-react";
import { statsApi } from "@/lib/api/stats";
import { enterpriseApi } from "@/lib/api/enterprise";
import { budgetApi } from "@/lib/api/budgets";
import { recurringApi } from "@/lib/api/recurring";
import type { RecurringItem } from "@/lib/api/recurring";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import EmptyState from "@/components/common/EmptyState";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount, formatPercent } from "@/lib/utils/format";
import type {
  AdvancedInsight,
  AssetLiability,
  Budget,
  CategoryStat,
  ComparisonData,
  Department,
  Employee,
  EnterpriseSummary,
  TaxItem,
  TrendPoint,
  YearlyReport,
} from "@/lib/types";

const { TabPane } = Tabs;

type ReportPeriod = "month" | "quarter" | "year";
type ReportTab = "overview" | "profit" | "cashflow" | "budget" | "tax" | "people" | "insights";
type DetailPayload = {
  title: string;
  subtitle?: string;
  amount?: number;
  amountType?: 1 | 2 | 3;
  tags?: ReactNode;
  rows: Array<[string, ReactNode]>;
};

const trendLimit: Record<ReportPeriod, number> = {
  month: 6,
  quarter: 4,
  year: 5,
};

const periodLabels: Record<ReportPeriod, string> = {
  month: "本月",
  quarter: "本季度",
  year: "本年",
};

const budgetRiskColors: Record<string, string> = {
  low: "green",
  medium: "orange",
  high: "red",
  critical: "red",
};

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function toIsoDate(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function periodRange(period: ReportPeriod) {
  const now = new Date();
  const start = new Date(now);
  if (period === "year") {
    start.setMonth(0, 1);
  } else if (period === "quarter") {
    start.setMonth(Math.floor(now.getMonth() / 3) * 3, 1);
  } else {
    start.setDate(1);
  }
  return { startDate: toIsoDate(start), endDate: toIsoDate(now) };
}

function monthsCovered(startDate: string, endDate: string) {
  const start = new Date(`${startDate}T00:00:00`);
  const end = new Date(`${endDate}T00:00:00`);
  const inclusiveDays = Math.max(1, Math.floor((end.getTime() - start.getTime()) / 86400000) + 1);
  return inclusiveDays / (365.2425 / 12);
}

function displayDate(date?: string | null) {
  if (!date) return "--";
  return date.slice(0, 10);
}

function displayPercent(value: number) {
  if (!Number.isFinite(value)) return "0.0%";
  return formatPercent(value);
}

function displayChangePercent(data?: ComparisonData | null) {
  if (!data) return "--";
  const raw = Number(data.changePercent || 0);
  const sign = raw > 0 ? "+" : raw < 0 ? "-" : "";
  return `${sign}${Math.abs(raw).toFixed(1)}%`;
}

function taxTypeLabel(type: string) {
  const labels: Record<string, string> = {
    vat: "增值税",
    corporate_income_tax: "企业所得税",
    personal_income_tax: "个税代扣",
    surcharge: "附加税",
  };
  return labels[type] || type;
}

function taxStatus(item: TaxItem) {
  const unpaid = Math.max(0, Number(item.taxAmount || 0) - Number(item.paidAmount || 0));
  if (item.status === "paid" || unpaid <= 0) return { label: "已缴清", color: "green" };
  if (item.status === "overdue") return { label: "已逾期", color: "red" };
  if (item.status === "estimated") return { label: "预估", color: "orange" };
  return { label: "待处理", color: "arcoblue" };
}

function resolved<T>(result: PromiseSettledResult<{ data: T }>, fallback: T) {
  return result.status === "fulfilled" ? result.value.data : fallback;
}

function csvCell(value: unknown) {
  const raw = value == null ? "" : String(value);
  const safe = /^[=+\-@]/.test(raw) ? `'${raw}` : raw;
  return `"${safe.replaceAll('"', '""')}"`;
}

export default function ReportsPage() {
  const activeSubjectType = useAppStore((state) => state.activeSubjectType);
  const [period, setPeriod] = useState<ReportPeriod>("month");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [focus, setFocus] = useState<"all" | "finance" | "people" | "tax" | "budget">("all");
  const [activeTab, setActiveTab] = useState<ReportTab>("overview");
  const [loading, setLoading] = useState(true);
  const [theme, setTheme] = useState<"light" | "dark">("light");
  const [trend, setTrend] = useState<TrendPoint[]>([]);
  const [expenseCats, setExpenseCats] = useState<CategoryStat[]>([]);
  const [incomeCats, setIncomeCats] = useState<CategoryStat[]>([]);
  const [yearly, setYearly] = useState<YearlyReport | null>(null);
  const [assets, setAssets] = useState<AssetLiability | null>(null);
  const [mom, setMom] = useState<ComparisonData | null>(null);
  const [yoy, setYoy] = useState<ComparisonData | null>(null);
  const [insights, setInsights] = useState<AdvancedInsight | null>(null);
  const [enterpriseSummary, setEnterpriseSummary] = useState<EnterpriseSummary | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [taxItems, setTaxItems] = useState<TaxItem[]>([]);
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [recurringItems, setRecurringItems] = useState<RecurringItem[]>([]);
  const [detail, setDetail] = useState<DetailPayload | null>(null);
  const [failedSections, setFailedSections] = useState<string[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const isHousehold = activeSubjectType === "household" || enterpriseSummary?.company?.entityType === "household";

  useEffect(() => {
    const syncTheme = () => {
      const root = document.documentElement;
      setTheme(root.dataset.theme === "dark" || root.classList.contains("dark") ? "dark" : "light");
    };

    syncTheme();
    const observer = new MutationObserver(syncTheme);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme", "class"] });
    return () => observer.disconnect();
  }, []);

  const effectiveActiveTab: ReportTab = isHousehold && (activeTab === "tax" || activeTab === "people")
    ? "overview"
    : activeTab;
  const effectiveFocus = isHousehold && (focus === "tax" || focus === "people")
    ? "all"
    : focus;

  const defaultRange = useMemo(() => periodRange(period), [period]);
  const reportRange = useMemo(
    () => ({
      startDate: startDate || defaultRange.startDate,
      endDate: endDate || defaultRange.endDate,
    }),
    [defaultRange.endDate, defaultRange.startDate, endDate, startDate]
  );
  const rangeInvalid = reportRange.startDate > reportRange.endDate;

  useEffect(() => {
    let cancelled = false;

    const loadReports = async () => {
      setLoading(true);
      if (rangeInvalid) {
        setFailedSections([]);
        setLoading(false);
        return;
      }
      const year = Number(reportRange.endDate.slice(0, 4));
      const month = reportRange.endDate.slice(0, 7);
      const [
        trendResult,
        expenseResult,
        incomeResult,
        yearlyResult,
        assetResult,
        comparisonResult,
        insightsResult,
        summaryResult,
        departmentsResult,
        employeesResult,
        taxResult,
        budgetsResult,
        recurringResult,
      ] = await Promise.allSettled([
        statsApi.trend({ period, limit: trendLimit[period], endDate: reportRange.endDate }),
        statsApi.category({ type: "expense", ...reportRange }),
        statsApi.category({ type: "income", ...reportRange }),
        statsApi.yearly(year),
        statsApi.assetLiability(),
        statsApi.comparison({ month }),
        statsApi.insights(),
        enterpriseApi.summary(),
        enterpriseApi.departments(),
        enterpriseApi.employees({ status: "active" }),
        enterpriseApi.taxItems(),
        budgetApi.list({ page: 0, size: 500 }),
        recurringApi.list(),
      ]);

      if (cancelled) return;

      setTrend(resolved(trendResult, []));
      setExpenseCats(resolved(expenseResult, []));
      setIncomeCats(resolved(incomeResult, []));
      setYearly(resolved(yearlyResult, null));
      setAssets(resolved(assetResult, null));
      if (comparisonResult.status === "fulfilled") {
        setMom(comparisonResult.value.data.mom);
        setYoy(comparisonResult.value.data.yoy);
      } else {
        setMom(null);
        setYoy(null);
      }
      setInsights(resolved(insightsResult, null));
      setEnterpriseSummary(resolved(summaryResult, null));
      setDepartments(resolved(departmentsResult, []));
      setEmployees(resolved(employeesResult, []));
      setTaxItems(resolved(taxResult, []));
      setBudgets(budgetsResult.status === "fulfilled" ? budgetsResult.value.data.content : []);
      setRecurringItems(resolved(recurringResult, []));
      const namedResults: Array<[string, PromiseSettledResult<unknown>, boolean]> = [
        ["经营趋势", trendResult, true],
        ["成本分类", expenseResult, true],
        ["收入分类", incomeResult, true],
        ["年度汇总", yearlyResult, true],
        ["资产负债", assetResult, true],
        ["环比同比", comparisonResult, true],
        ["经营洞察", insightsResult, true],
        ["主体信息", summaryResult, true],
        ["部门", departmentsResult, activeSubjectType !== "household"],
        ["员工", employeesResult, activeSubjectType !== "household"],
        ["税务", taxResult, activeSubjectType !== "household"],
        ["预算", budgetsResult, true],
        ["周期事项", recurringResult, true],
      ];
      setFailedSections(namedResults
        .filter(([, result, relevant]) => relevant && result.status === "rejected")
        .map(([name]) => name));
      setLoading(false);
    };

    void loadReports();

    return () => {
      cancelled = true;
    };
  }, [activeSubjectType, period, rangeInvalid, reloadKey, reportRange]);

  const chartColors = useMemo(() => ({
    text: theme === "dark" ? "#94a3b8" : "#64748b",
    split: theme === "dark" ? "#334155" : "#e2e8f0",
    bg: theme === "dark" ? "#1e293b" : "#ffffff",
  }), [theme]);

  const operatingSummary = useMemo(() => {
    const income = incomeCats.reduce((sum, item) => sum + Number(item.amount || 0), 0);
    const expense = expenseCats.reduce((sum, item) => sum + Number(item.amount || 0), 0);
    const profit = income - expense;
    const profitMargin = income > 0 ? profit / income : 0;
    const totalBudget = budgets.reduce((sum, budget) => sum + Number(budget.amount || 0), 0);
    const spentBudget = budgets.reduce((sum, budget) => sum + Number(budget.spent || 0), 0);
    const budgetUsage = totalBudget > 0 ? spentBudget / totalBudget : 0;
    const budgetAlerts = budgets.filter((budget) => budget.riskLevel === "high" || budget.riskLevel === "critical" || budget.warningReached).length;
    const pendingTax = taxItems.reduce((sum, item) => sum + Math.max(0, Number(item.taxAmount || 0) - Number(item.paidAmount || 0)), 0);
    const peopleCost = employees.reduce((sum, employee) => sum + Number(employee.monthlyCost || 0), 0) || Number(enterpriseSummary?.monthlyPeopleCost || 0);
    const recurringExpense = recurringItems
      .filter((item) => item.status === 1 && item.type === 2)
      .reduce((sum, item) => sum + Number(item.amount || 0), 0);
    return { income, expense, profit, profitMargin, totalBudget, spentBudget, budgetUsage, budgetAlerts, pendingTax, peopleCost, recurringExpense };
  }, [budgets, employees, enterpriseSummary?.monthlyPeopleCost, expenseCats, incomeCats, recurringItems, taxItems]);

  const cashSummary = useMemo(() => {
    const netWorth = Number(assets?.netWorth || 0);
    const assetsTotal = Number(assets?.totalAssets || 0);
    const liabilities = Number(assets?.totalLiabilities || 0);
    const monthlyBurn = operatingSummary.expense > 0
      ? operatingSummary.expense / monthsCovered(reportRange.startDate, reportRange.endDate)
      : operatingSummary.peopleCost + operatingSummary.recurringExpense;
    const runway = monthlyBurn > 0 ? assetsTotal / monthlyBurn : 0;
    return { netWorth, assetsTotal, liabilities, monthlyBurn, runway };
  }, [assets, operatingSummary.expense, operatingSummary.peopleCost, operatingSummary.recurringExpense, reportRange.endDate, reportRange.startDate]);

  const departmentCostRows = useMemo(() => {
    const departmentNames = new Map(departments.map((department) => [department.id, department.name]));
    const rows = new Map<string, { name: string; employees: number; cost: number; budget: number }>();

    departments.forEach((department) => {
      rows.set(String(department.id), { name: department.name, employees: 0, cost: 0, budget: Number(department.budget || 0) });
    });

    employees.forEach((employee) => {
      const key = String(employee.departmentId || "none");
      const row = rows.get(key) || {
        name: employee.departmentName || (employee.departmentId ? departmentNames.get(employee.departmentId) : "未分配部门") || "未分配部门",
        employees: 0,
        cost: 0,
        budget: 0,
      };
      row.employees += 1;
      row.cost += Number(employee.monthlyCost || 0);
      rows.set(key, row);
    });

    return [...rows.values()].sort((a, b) => b.cost - a.cost);
  }, [departments, employees]);

  const incomeUnavailable = failedSections.includes("收入分类");
  const expenseUnavailable = failedSections.includes("成本分类");
  const profitUnavailable = incomeUnavailable || expenseUnavailable;
  const cashBurnFallbackUnavailable = operatingSummary.expense <= 0
    && (failedSections.includes("周期事项") || (!isHousehold && failedSections.includes("员工")));
  const cashflowUnavailable = failedSections.includes("经营趋势")
    || failedSections.includes("资产负债")
    || expenseUnavailable
    || cashBurnFallbackUnavailable;

  const findingCards = useMemo(() => {
    const items: Array<{ title: string; description: string; level: "good" | "warn" | "risk" }> = [];
    if (failedSections.length > 0) {
      items.push({ title: "部分结论已暂停", description: "存在未加载的数据模块；页面只保留可验证信息，不会用 0 补齐缺失数据。", level: "warn" });
    }
    if (!profitUnavailable) {
      if (operatingSummary.profit < 0) {
        items.push({ title: "利润为负", description: "当前筛选周期内成本高于收入，建议复核大额支出和固定成本。", level: "risk" });
      } else if (operatingSummary.profit === 0) {
        items.push({ title: "当前收支持平", description: "当前筛选周期未形成正向利润，建议结合交易笔数确认是否尚未录入完整。", level: "warn" });
      } else {
        items.push({ title: "经营利润为正", description: `利润率 ${displayPercent(operatingSummary.profitMargin)}，可继续关注现金回款节奏。`, level: "good" });
      }
    }
    if (operatingSummary.budgetAlerts > 0) {
      items.push({ title: "预算存在预警", description: `${operatingSummary.budgetAlerts} 项预算已接近或超过阈值。`, level: "warn" });
    }
    if (operatingSummary.pendingTax > 0) {
      items.push({ title: "税费待处理", description: `待处理税费 ${formatAmount(operatingSummary.pendingTax)}，需关注申报截止日。`, level: "warn" });
    }
    if (!cashflowUnavailable && cashSummary.runway > 0 && cashSummary.runway < 3) {
      items.push({ title: "现金支撑偏紧", description: `按当前成本估算可支撑 ${cashSummary.runway.toFixed(1)} 个月。`, level: "risk" });
    }
    if (items.length < 4 && insights?.largeTransactions?.length) {
      items.push({ title: "存在大额流水", description: `${insights.largeTransactions.length} 条大额交易建议复核票据和税务口径。`, level: "warn" });
    }
    return items.slice(0, 4);
  }, [cashSummary.runway, cashflowUnavailable, failedSections, insights, operatingSummary.budgetAlerts, operatingSummary.pendingTax, operatingSummary.profit, operatingSummary.profitMargin, profitUnavailable]);
  const summaryCards: Array<{ label: string; value: ReactNode; helper: string; icon: ReactNode; accent: string; unavailable: boolean }> = [
    {
      label: isHousehold ? "家庭收入" : "经营收入",
      value: <span className="text-lg font-bold" style={{ color: "#10b981" }}>{formatAmount(operatingSummary.income)}</span>,
      helper: `${displayDate(reportRange.startDate)} 至 ${displayDate(reportRange.endDate)}`,
      icon: <IconDashboard />,
      accent: "#10b981",
      unavailable: incomeUnavailable,
    },
    {
      label: isHousehold ? "家庭支出" : "经营成本",
      value: <span className="text-lg font-bold" style={{ color: "#ef4444" }}>{formatAmount(operatingSummary.expense)}</span>,
      helper: isHousehold
        ? failedSections.includes("周期事项") ? "固定事项暂不可用" : `固定支出 ${formatAmount(operatingSummary.recurringExpense)}`
        : failedSections.includes("员工") ? "人力数据暂不可用" : `人力 ${formatAmount(operatingSummary.peopleCost)}`,
      icon: <IconFile />,
      accent: "#ef4444",
      unavailable: expenseUnavailable,
    },
    {
      label: isHousehold ? "家庭结余" : "经营利润",
      value: (
        <span className="text-lg font-bold" style={{ color: operatingSummary.profit >= 0 ? "#10b981" : "#ef4444" }}>
          {operatingSummary.profit >= 0 ? "+" : "-"}{formatAmount(Math.abs(operatingSummary.profit))}
        </span>
      ),
      helper: `${isHousehold ? "结余率" : "利润率"} ${displayPercent(operatingSummary.profitMargin)}`,
      icon: <IconSafe />,
      accent: operatingSummary.profit >= 0 ? "#10b981" : "#ef4444",
      unavailable: profitUnavailable,
    },
    {
      label: "预算使用率",
      value: <span className="text-lg font-bold" style={{ color: operatingSummary.budgetUsage >= 1 ? "#ef4444" : "var(--text-color-1)" }}>{displayPercent(operatingSummary.budgetUsage)}</span>,
      helper: `${operatingSummary.budgetAlerts} 项预警`,
      icon: <IconCalendar />,
      accent: "#6366f1",
      unavailable: failedSections.includes("预算"),
    },
    isHousehold
      ? {
        label: "固定事项",
        value: <span className="text-lg font-bold" style={{ color: "var(--text-color-1)" }}>{recurringItems.filter((item) => item.status === 1).length}</span>,
        helper: `月固定支出 ${formatAmount(operatingSummary.recurringExpense)}`,
        icon: <IconExclamationCircle />,
        accent: "#a85a42",
        unavailable: failedSections.includes("周期事项"),
      }
      : {
        label: "待处理税费",
        value: <span className="text-lg font-bold" style={{ color: "#ef4444" }}>{formatAmount(operatingSummary.pendingTax)}</span>,
        helper: `截止日 ${enterpriseSummary?.nextTaxDueDate || "--"}`,
        icon: <IconExclamationCircle />,
        accent: "#a85a42",
        unavailable: failedSections.includes("税务") || failedSections.includes("主体信息"),
      },
    {
      label: isHousehold ? "家庭净资产" : "现金支撑",
      value: (
        <span className="text-lg font-bold" style={{ color: "var(--text-color-1)" }}>
          {isHousehold ? formatAmount(cashSummary.netWorth) : cashSummary.runway ? `${cashSummary.runway.toFixed(1)} 月` : "--"}
        </span>
      ),
      helper: `净资产 ${formatAmount(cashSummary.netWorth)}`,
      icon: <IconSafe />,
      accent: "#0ea5e9",
      unavailable: failedSections.includes("资产负债")
        || expenseUnavailable
        || cashBurnFallbackUnavailable,
    },
  ];

  const exportCsv = () => {
    const subjectName = enterpriseSummary?.company?.name || "当前主体";
    const rows: unknown[][] = [
      ["Mamoji 经营报表"],
      ["主体", subjectName],
      ["生成时间", new Date().toLocaleString("zh-CN")],
      ["收支统计区间", reportRange.startDate, reportRange.endDate],
      ["历史趋势口径", `截至 ${reportRange.endDate} 的近 ${trendLimit[period]} 个${period === "month" ? "月" : period === "quarter" ? "季度" : "年度"}`],
      ["快照口径", "资产、预算、税务与人员为导出时的当前状态"],
      [],
      ["核心指标", "数值"],
      [isHousehold ? "家庭收入" : "经营收入", operatingSummary.income],
      [isHousehold ? "家庭支出" : "经营成本", operatingSummary.expense],
      [isHousehold ? "家庭结余" : "经营利润", operatingSummary.profit],
      ["当前活动预算金额", operatingSummary.totalBudget],
      ["当前活动预算已使用", operatingSummary.spentBudget],
      ["当前净资产", cashSummary.netWorth],
      [],
      ["趋势期间", "收入", "支出", "结余"],
      ...trend.map((item) => [item.month, item.income, item.expense, item.balance]),
      [],
      ["收入分类", "金额", "占比", "笔数"],
      ...incomeCats.map((item) => [item.categoryName, item.amount, item.percentage, item.count]),
      [],
      ["成本分类", "金额", "占比", "笔数"],
      ...expenseCats.map((item) => [item.categoryName, item.amount, item.percentage, item.count]),
    ];
    const csv = `\uFEFF${rows.map((row) => row.map(csvCell).join(",")).join("\r\n")}`;
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `mamoji-report-${reportRange.startDate}-${reportRange.endDate}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const trendOption = useMemo(() => ({
    color: ["#10b981", "#ef4444", "#6366f1"],
    tooltip: {
      trigger: "axis" as const,
      valueFormatter: (value: number | null) => value == null ? "无数据" : formatAmount(Number(value)),
    },
    legend: { data: ["收入", "成本", "利润"], bottom: 0, textStyle: { color: chartColors.text } },
    grid: { left: 64, right: 32, top: 28, bottom: 56 },
    xAxis: {
      type: "category" as const,
      data: trend.map((item) => item.month),
      axisLabel: { color: chartColors.text },
      axisLine: { lineStyle: { color: chartColors.split } },
    },
    yAxis: {
      type: "value" as const,
      axisLabel: { color: chartColors.text, formatter: (value: number) => value.toLocaleString() },
      splitLine: { lineStyle: { color: chartColors.split } },
    },
    series: [
      { name: "收入", type: "bar", data: trend.map((item) => item.income), barMaxWidth: 36, itemStyle: { color: "#10b981", borderRadius: [4, 4, 0, 0] } },
      { name: "成本", type: "bar", data: trend.map((item) => item.expense), barMaxWidth: 36, itemStyle: { color: "#ef4444", borderRadius: [4, 4, 0, 0] } },
      {
        name: "利润",
        type: "line",
        data: trend.map((item) => (item.hasData ?? (item.income !== 0 || item.expense !== 0)) ? item.balance : null),
        smooth: true,
        connectNulls: false,
        symbolSize: 7,
        itemStyle: { color: "#6366f1" },
        lineStyle: { width: 2 },
      },
    ],
  }), [chartColors.split, chartColors.text, trend]);

  const cashFlowOption = useMemo(() => {
    const cashLine = trend.map((_, index) =>
      trend.slice(0, index + 1).reduce((sum, item) => sum + Number(item.balance || 0), 0)
    );
    return {
      color: ["#0ea5e9", "#6366f1"],
      tooltip: { trigger: "axis" as const, valueFormatter: (value: number | null) => value == null ? "无数据" : formatAmount(Number(value)) },
      legend: { data: ["净现金流", "累计净流入"], bottom: 0, textStyle: { color: chartColors.text } },
      grid: { left: 64, right: 32, top: 28, bottom: 56 },
      xAxis: { type: "category" as const, data: trend.map((item) => item.month), axisLabel: { color: chartColors.text }, axisLine: { lineStyle: { color: chartColors.split } } },
      yAxis: { type: "value" as const, axisLabel: { color: chartColors.text, formatter: (value: number) => value.toLocaleString() }, splitLine: { lineStyle: { color: chartColors.split } } },
      series: [
        { name: "净现金流", type: "bar", data: trend.map((item) => item.balance), barMaxWidth: 34, itemStyle: { color: "#0ea5e9", borderRadius: [4, 4, 0, 0] } },
        { name: "累计净流入", type: "line", smooth: true, data: cashLine, itemStyle: { color: "#6366f1" }, lineStyle: { width: 2 } },
      ],
    };
  }, [chartColors.split, chartColors.text, trend]);

  const pieOption = (data: CategoryStat[]) => ({
    tooltip: { trigger: "item" as const, formatter: "{b}: {c} ({d}%)" },
    legend: { bottom: 0, textStyle: { color: chartColors.text } },
    series: [
      {
        type: "pie",
        radius: ["42%", "70%"],
        center: ["50%", "45%"],
        data: data.map((item) => ({ name: item.categoryName, value: item.amount })),
        label: { color: chartColors.text },
      },
    ],
  });

  const yearlyOption = yearly ? {
    color: ["#10b981", "#ef4444", "#6366f1"],
    tooltip: { trigger: "axis" as const, valueFormatter: (value: number | null) => value == null ? "无数据" : formatAmount(Number(value)) },
    legend: { data: ["收入", "成本", "利润"], bottom: 0, textStyle: { color: chartColors.text } },
    grid: { left: 64, right: 32, top: 28, bottom: 56 },
    xAxis: { type: "category" as const, data: yearly.months.map((item) => `${item.month}月`), axisLabel: { color: chartColors.text }, axisLine: { lineStyle: { color: chartColors.split } } },
    yAxis: { type: "value" as const, axisLabel: { color: chartColors.text, formatter: (value: number) => value.toLocaleString() }, splitLine: { lineStyle: { color: chartColors.split } } },
    series: [
      { name: "收入", type: "bar", data: yearly.months.map((item) => item.income), barMaxWidth: 32, itemStyle: { borderRadius: [4, 4, 0, 0] } },
      { name: "成本", type: "bar", data: yearly.months.map((item) => item.expense), barMaxWidth: 32, itemStyle: { borderRadius: [4, 4, 0, 0] } },
      { name: "利润", type: "line", smooth: true, data: yearly.months.map((item) => item.balance), lineStyle: { width: 2 } },
    ],
  } : null;

  const departmentCostOption = {
    color: ["#6366f1"],
    tooltip: { trigger: "axis" as const, valueFormatter: (value: number | null) => value == null ? "无数据" : formatAmount(Number(value)) },
    grid: { left: 80, right: 24, top: 20, bottom: 32 },
    xAxis: { type: "value" as const, axisLabel: { color: chartColors.text }, splitLine: { lineStyle: { color: chartColors.split } } },
    yAxis: { type: "category" as const, data: departmentCostRows.map((item) => item.name).reverse(), axisLabel: { color: chartColors.text }, axisLine: { lineStyle: { color: chartColors.split } } },
    series: [
      {
        type: "bar",
        data: departmentCostRows.map((item) => item.cost).reverse(),
        barMaxWidth: 24,
        itemStyle: { color: "#6366f1", borderRadius: [0, 4, 4, 0] },
      },
    ],
  };

  const openCategoryDetail = (category: CategoryStat, type: 1 | 2) => {
    setDetail({
      title: category.categoryName,
      subtitle: type === 1 ? "收入分类" : "成本分类",
      amount: category.amount,
      amountType: type,
      tags: <Tag color={type === 1 ? "green" : "red"}>{type === 1 ? "收入" : "成本"}</Tag>,
      rows: [
        ["分类编号", `#${category.categoryId}`],
        ["交易笔数", `${category.count} 笔`],
        ["占比", `${Number(category.percentage || 0).toFixed(1)}%`],
        ["统计周期", `${displayDate(reportRange.startDate)} - ${displayDate(reportRange.endDate)}`],
      ],
    });
  };

  const openBudgetDetail = (budget: Budget) => {
    setDetail({
      title: budget.name,
      subtitle: "预算执行",
      amount: budget.amount,
      amountType: 2,
      tags: <Tag color={budgetRiskColors[budget.riskLevel] || "gray"}>{budget.riskMessage || budget.riskLevel}</Tag>,
      rows: [
        ["预算金额", formatAmount(budget.amount)],
        ["已使用", formatAmount(budget.spent)],
        ["剩余额度", formatAmount(budget.remainingAmount)],
        ["使用率", displayPercent(budget.usageRate)],
        ["周期", `${displayDate(budget.startDate)} - ${displayDate(budget.endDate)}`],
        ["预警阈值", `${budget.warningThreshold}%`],
      ],
    });
  };

  const openTaxDetail = (item: TaxItem) => {
    const status = taxStatus(item);
    setDetail({
      title: item.name,
      subtitle: "税务事项",
      amount: Math.max(0, item.taxAmount - item.paidAmount),
      amountType: 2,
      tags: <Tag color={status.color}>{status.label}</Tag>,
      rows: [
        ["税种", taxTypeLabel(item.taxType)],
        ["所属期间", item.period],
        ["计税金额", formatAmount(item.taxableAmount)],
        ["应缴税额", formatAmount(item.taxAmount)],
        ["已缴金额", formatAmount(item.paidAmount)],
        ["截止日期", displayDate(item.dueDate)],
        ["备注", item.note || "--"],
      ],
    });
  };

  const openEmployeeDetail = (employee: Employee) => {
    setDetail({
      title: employee.name,
      subtitle: `${employee.departmentName || "未分配部门"} · ${employee.position}`,
      amount: employee.monthlyCost,
      amountType: 2,
      tags: <Tag color="arcoblue">{employee.status}</Tag>,
      rows: [
        ["薪资", formatAmount(employee.salary)],
        ["社保", formatAmount(employee.socialInsurance)],
        ["公积金", formatAmount(employee.housingFund)],
        ["个税估算", formatAmount(employee.taxEstimate)],
        ["入职日期", displayDate(employee.hireDate)],
        ["权限角色", employee.accessRole],
      ],
    });
  };

  const renderCategoryRows = (items: CategoryStat[], type: 1 | 2, source: "收入分类" | "成本分类") => (
    <div className="bi-flat-list">
      {items.slice(0, 6).map((item, index) => (
        <button
          key={`${type}-${item.categoryId}`}
          type="button"
          onClick={() => openCategoryDetail(item, type)}
          className="flex w-full cursor-pointer items-center justify-between rounded-xl border p-3 text-left transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
          style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-card)" }}
        >
          <div className="flex min-w-0 items-center gap-3">
            <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg text-sm font-semibold" style={{ backgroundColor: `${item.categoryColor || "#6366f1"}20`, color: item.categoryColor || "#6366f1" }}>
              {index + 1}
            </span>
            <div className="min-w-0">
              <div className="truncate font-medium" style={{ color: "var(--text-color-1)" }}>{item.categoryIcon} {item.categoryName}</div>
              <div className="text-xs" style={{ color: "var(--text-color-4)" }}>{item.count} 笔 · {Number(item.percentage || 0).toFixed(1)}%</div>
            </div>
          </div>
          <AmountDisplay amount={item.amount} type={type} showSign size="medium" />
        </button>
      ))}
      {items.length === 0 && (
        <div className="py-10 text-center text-sm" style={{ color: "var(--text-color-3)" }}>
          {failedSections.includes(source) ? "分类数据暂不可用" : "暂无分类数据"}
        </div>
      )}
    </div>
  );

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title={isHousehold ? "家庭报表" : "经营报表"}
        subtitle={`${enterpriseSummary?.company?.name || "当前主体"} · ${isHousehold ? "家庭收入、支出、现金流、预算和固定事项分析" : "收入、成本、现金流、预算、税务和人力成本分析"}`}
        icon={<IconDashboard />}
        extra={
          <Button icon={<IconFile />} disabled={loading || rangeInvalid || failedSections.length > 0} onClick={exportCsv}>
            导出 CSV
          </Button>
        }
      />

      {failedSections.length > 0 && (
        <Alert
          className="mb-4"
          type="warning"
          title="报表数据不完整"
          content={`${failedSections.join("、")} 暂不可用；相关指标显示为“--”，完整导出已暂停。`}
          action={<Button size="small" onClick={() => setReloadKey((key) => key + 1)}>重新加载</Button>}
        />
      )}

      <Card className="filter-card mb-4" style={{ borderRadius: 12 }}>
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[220px_minmax(340px,1fr)_170px_110px]">
          <Radio.Group type="button" value={period} onChange={(value) => setPeriod(value as ReportPeriod)}>
            <Radio value="month">月度</Radio>
            <Radio value="quarter">季度</Radio>
            <Radio value="year">年度</Radio>
          </Radio.Group>
          <div className="grid grid-cols-[minmax(0,1fr)_20px_minmax(0,1fr)] items-center">
            <DatePicker
              format="YYYY-MM-DD"
              value={startDate}
              onChange={(value) => setStartDate(value || "")}
              placeholder="起始日期"
              className="w-full"
              style={{ borderRadius: 12 }}
            />
            <span className="text-center text-sm" style={{ color: "var(--text-color-4)" }}>-</span>
            <DatePicker
              format="YYYY-MM-DD"
              value={endDate}
              onChange={(value) => setEndDate(value || "")}
              placeholder="结束日期"
              className="w-full"
              style={{ borderRadius: 12 }}
            />
          </div>
          <Select
            value={effectiveFocus}
            onChange={(value) => {
              const nextFocus = value as typeof focus;
              setFocus(nextFocus);
              const nextTab: ReportTab = nextFocus === "people"
                ? "people"
                : nextFocus === "tax"
                  ? "tax"
                  : nextFocus === "budget"
                    ? "budget"
                    : nextFocus === "finance"
                      ? "profit"
                      : "overview";
              setActiveTab(nextTab);
            }}
            style={{ width: "100%", borderRadius: 12 }}
          >
            <Select.Option value="all">全部视角</Select.Option>
            <Select.Option value="finance">{isHousehold ? "收支分析" : "财务经营"}</Select.Option>
            <Select.Option value="budget">预算执行</Select.Option>
            {!isHousehold && <Select.Option value="tax">税务合规</Select.Option>}
            {!isHousehold && <Select.Option value="people">人力成本</Select.Option>}
          </Select>
          <Button
            icon={<IconSearch />}
            onClick={() => {
              setStartDate("");
              setEndDate("");
            }}
          >
            重置
          </Button>
        </div>
        <div className="mt-3 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
          日期范围用于收入、成本和月均现金消耗；趋势以结束日为锚点，资产、预算、税务与人员显示当前主体快照。
        </div>
      </Card>

      {rangeInvalid ? (
        <Alert
          type="error"
          title="统计区间无效"
          content="起始日期不能晚于结束日期，请修正日期后再生成报表。"
        />
      ) : loading ? (
        <Card style={{ borderRadius: 12 }}>
          <Skeleton className="h-80" />
        </Card>
      ) : (
        <>
          <div className="metric-grid metric-wrap-until-lg grid grid-cols-2 lg:grid-cols-6">
            {summaryCards.map((item) => (
              <Card className="metric-card" key={item.label} style={{ borderRadius: 12 }}>
                <div className="flex h-[98px] flex-col justify-between">
                  <div className="flex items-center justify-between">
                    <span className="text-xs" style={{ color: "var(--text-color-3)" }}>{item.label}</span>
                    <span className="grid h-8 w-8 place-items-center rounded-lg text-base" style={{ backgroundColor: `${item.accent}18`, color: item.accent }}>
                      {item.icon}
                    </span>
                  </div>
                  <div className="truncate">{item.unavailable ? <span className="text-lg font-bold">--</span> : item.value}</div>
                  <div className="truncate text-xs" style={{ color: "var(--text-color-4)" }}>{item.unavailable ? "数据暂不可用" : item.helper}</div>
                </div>
              </Card>
            ))}
          </div>

          <Tabs activeTab={effectiveActiveTab} onChange={(key) => setActiveTab(key as ReportTab)}>
            <TabPane key="overview" title={isHousehold ? "家庭总览" : "经营总览"}>
              <div className="bi-panel-cluster bi-cluster-lg grid grid-cols-1 lg:grid-cols-[minmax(0,2fr)_minmax(320px,1fr)]">
                <Card title={`${periodLabels[period]}${isHousehold ? "收支趋势" : "经营趋势"}`} style={{ borderRadius: 12 }}>
                  {failedSections.includes("经营趋势")
                    ? <EmptyState title="趋势数据暂不可用" description="重新加载后再查看经营趋势" />
                    : <ReactECharts option={trendOption} style={{ height: 360 }} />}
                </Card>
                <Card title={isHousehold ? "家庭结论" : "经营结论"} style={{ borderRadius: 12 }}>
                  <div className="bi-flat-list">
                    {findingCards.map((item) => (
                      <div key={item.title} className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)", backgroundColor: "var(--bg-color-page)" }}>
                        <div className="flex items-center gap-2">
                          <span className="h-2 w-2 rounded-full" style={{ backgroundColor: item.level === "good" ? "#10b981" : item.level === "warn" ? "#a85a42" : "#ef4444" }} />
                          <span className="font-medium" style={{ color: "var(--text-color-1)" }}>{item.title}</span>
                        </div>
                        <div className="mt-2 text-sm" style={{ color: "var(--text-color-3)" }}>{item.description}</div>
                      </div>
                    ))}
                  </div>
                </Card>
              </div>

              <div className="bi-panel-cluster bi-cluster-lg mt-4 grid grid-cols-1 lg:grid-cols-2">
                <Card title="成本结构" style={{ borderRadius: 12 }}>
                  {expenseCats.length
                    ? <ReactECharts option={pieOption(expenseCats)} style={{ height: 320 }} />
                    : <EmptyState title={failedSections.includes("成本分类") ? "成本数据暂不可用" : "暂无成本数据"} description={failedSections.includes("成本分类") ? "重新加载后再查看成本结构" : "当前周期没有成本流水"} />}
                </Card>
                <Card title="收入结构" style={{ borderRadius: 12 }}>
                  {incomeCats.length
                    ? <ReactECharts option={pieOption(incomeCats)} style={{ height: 320 }} />
                    : <EmptyState title={failedSections.includes("收入分类") ? "收入数据暂不可用" : "暂无收入数据"} description={failedSections.includes("收入分类") ? "重新加载后再查看收入结构" : "当前周期没有收入流水"} />}
                </Card>
              </div>
            </TabPane>

            <TabPane key="profit" title={isHousehold ? "收支结余" : "利润分析"}>
              {profitUnavailable ? (
                <EmptyState title="利润数据暂不可用" description="收入或成本分类尚未加载完成，请重新加载后查看" />
              ) : (
              <>
              <div className="bi-panel-cluster bi-cluster-lg grid grid-cols-1 lg:grid-cols-[minmax(0,1.35fr)_minmax(320px,0.65fr)]">
                <Card title="年度利润走势" style={{ borderRadius: 12 }}>
                  {yearlyOption
                    ? <ReactECharts option={yearlyOption} style={{ height: 360 }} />
                    : <EmptyState title={failedSections.includes("年度汇总") ? "年度数据暂不可用" : "暂无年度数据"} description={failedSections.includes("年度汇总") ? "重新加载后再查看年度走势" : "没有可展示的年度经营数据"} />}
                </Card>
                <Card title="利润口径" style={{ borderRadius: 12 }}>
                  <div className="space-y-4">
                    {[
                      ["收入合计", operatingSummary.income, 1 as const],
                      ["成本合计", operatingSummary.expense, 2 as const],
                      ["经营利润", Math.abs(operatingSummary.profit), operatingSummary.profit >= 0 ? 1 as const : 2 as const],
                    ].map(([label, amount, type]) => (
                      <div key={label as string} className="flex items-center justify-between border-b pb-3" style={{ borderColor: "var(--border-color-light)" }}>
                        <span style={{ color: "var(--text-color-3)" }}>{label}</span>
                        <AmountDisplay amount={amount as number} type={type as 1 | 2} showSign={label === "经营利润"} />
                      </div>
                    ))}
                    <div className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                      <div className="text-xs" style={{ color: "var(--text-color-3)" }}>利润率</div>
                      <div className="mt-2 text-xl font-semibold" style={{ color: operatingSummary.profitMargin >= 0 ? "#10b981" : "#ef4444" }}>{displayPercent(operatingSummary.profitMargin)}</div>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <div className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                        <div className="text-xs" style={{ color: "var(--text-color-3)" }}>成本环比</div>
                        <div className="mt-2 font-semibold" style={{ color: mom ? (mom.change || 0) >= 0 ? "#ef4444" : "#10b981" : "var(--text-color-3)" }}>{displayChangePercent(mom)}</div>
                      </div>
                      <div className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                        <div className="text-xs" style={{ color: "var(--text-color-3)" }}>成本同比</div>
                        <div className="mt-2 font-semibold" style={{ color: yoy ? (yoy.change || 0) >= 0 ? "#ef4444" : "#10b981" : "var(--text-color-3)" }}>{displayChangePercent(yoy)}</div>
                      </div>
                    </div>
                  </div>
                </Card>
              </div>

              <div className="bi-panel-cluster bi-cluster-lg mt-4 grid grid-cols-1 lg:grid-cols-2">
                <Card title="成本排行" style={{ borderRadius: 12 }}>{renderCategoryRows(expenseCats, 2, "成本分类")}</Card>
                <Card title="收入排行" style={{ borderRadius: 12 }}>{renderCategoryRows(incomeCats, 1, "收入分类")}</Card>
              </div>
              </>
              )}
            </TabPane>

            <TabPane key="cashflow" title="现金流">
              {cashflowUnavailable ? (
                <EmptyState title="现金流数据暂不可用" description="趋势、资产或成本数据尚未加载完成，请重新加载后查看" />
              ) : (
              <>
              <div className="bi-panel-cluster bi-cluster-lg grid grid-cols-1 lg:grid-cols-[minmax(0,1.4fr)_minmax(320px,0.6fr)]">
                <Card title="现金流趋势" style={{ borderRadius: 12 }}>
                  <ReactECharts option={cashFlowOption} style={{ height: 360 }} />
                </Card>
                <Card title="现金健康度" style={{ borderRadius: 12 }}>
                  <div className="grid grid-cols-2 gap-3">
                    <div className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                      <div className="text-xs" style={{ color: "var(--text-color-3)" }}>总资产</div>
                      <div className="mt-2 font-semibold">{formatAmount(cashSummary.assetsTotal)}</div>
                    </div>
                    <div className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                      <div className="text-xs" style={{ color: "var(--text-color-3)" }}>总负债</div>
                      <div className="mt-2 font-semibold" style={{ color: "#ef4444" }}>{formatAmount(cashSummary.liabilities)}</div>
                    </div>
                    <div className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                      <div className="text-xs" style={{ color: "var(--text-color-3)" }}>区间折算月成本</div>
                      <div className="mt-2 font-semibold">{formatAmount(cashSummary.monthlyBurn)}</div>
                    </div>
                    <div className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                      <div className="text-xs" style={{ color: "var(--text-color-3)" }}>支撑月数</div>
                      <div className="mt-2 font-semibold">{cashSummary.runway ? `${cashSummary.runway.toFixed(1)} 月` : "--"}</div>
                    </div>
                  </div>
                </Card>
              </div>

              <Card className="mt-4" title="资金账户" style={{ borderRadius: 12 }}>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[720px] table-fixed text-sm">
                    <thead>
                      <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                        {[
                          { label: "账户", align: "text-left" },
                          { label: "类型", align: "text-center" },
                          { label: "余额", align: "text-right" },
                          { label: "经营判断", align: "text-left" },
                        ].map((column) => (
                          <th key={column.label} className={`px-4 py-3 font-medium ${column.align}`} style={{ color: "var(--text-color-2)" }}>{column.label}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {(assets?.accounts || []).map((account) => (
                        <tr key={`${account.type}-${account.name}`} className="border-b" style={{ borderColor: "var(--border-color-light)" }}>
                          <td className="px-4 py-3 align-middle">{account.name}</td>
                          <td className="px-4 py-3 text-center align-middle">{account.type}</td>
                          <td className="px-4 py-3 text-right align-middle"><AmountDisplay amount={Math.abs(account.balance)} type={account.balance >= 0 ? 1 : 2} showSign /></td>
                          <td className="px-4 py-3 align-middle" style={{ color: "var(--text-color-3)" }}>{account.balance < 0 ? "需关注负债" : "可用资金"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Card>
              </>
              )}
            </TabPane>

            <TabPane key="budget" title="预算执行">
              {failedSections.includes("预算") ? (
                <EmptyState title="预算数据暂不可用" description="重新加载后再查看预算执行台账" />
              ) : (
              <Card title="预算执行台账" style={{ borderRadius: 12 }}>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[860px] table-fixed text-sm">
                    <colgroup>
                      <col style={{ width: 220 }} />
                      <col style={{ width: 140 }} />
                      <col style={{ width: 240 }} />
                      <col style={{ width: 120 }} />
                      <col style={{ width: 140 }} />
                      <col style={{ width: 100 }} />
                    </colgroup>
                    <thead>
                      <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                        {[
                          { label: "预算", align: "text-left" },
                          { label: "周期", align: "text-center" },
                          { label: "执行进度", align: "text-left" },
                          { label: "风险", align: "text-center" },
                          { label: "剩余额度", align: "text-right" },
                          { label: "操作", align: "text-center" },
                        ].map((column) => (
                          <th key={column.label} className={`px-4 py-3 font-medium ${column.align}`} style={{ color: "var(--text-color-2)" }}>{column.label}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {budgets.map((budget) => (
                        <tr key={budget.id} className="border-b" style={{ borderColor: "var(--border-color-light)" }}>
                          <td className="px-4 py-4 align-middle">
                            <div className="font-medium">{budget.name}</div>
                            <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>{budget.categoryName || "公司整体"}</div>
                          </td>
                          <td className="px-4 py-4 text-center text-xs align-middle" style={{ color: "var(--text-color-3)" }}>{displayDate(budget.startDate)}<br />至 {displayDate(budget.endDate)}</td>
                          <td className="px-4 py-4 align-middle">
                            <Progress percent={Math.min(100, budget.usageRate * 100)} showText={false} color={budget.usageRate >= 1 ? "#ef4444" : "#6366f1"} />
                            <div className="mt-1 flex justify-between text-xs" style={{ color: "var(--text-color-4)" }}>
                              <span>{displayPercent(budget.usageRate)}</span>
                              <span>{formatAmount(budget.spent)} / {formatAmount(budget.amount)}</span>
                            </div>
                          </td>
                          <td className="px-4 py-4 text-center align-middle"><Tag color={budgetRiskColors[budget.riskLevel] || "gray"}>{budget.riskMessage || budget.riskLevel}</Tag></td>
                          <td className="px-4 py-4 text-right align-middle"><AmountDisplay amount={Math.abs(budget.remainingAmount)} type={budget.remainingAmount >= 0 ? 1 : 2} /></td>
                          <td className="px-4 py-4 text-center align-middle"><Button type="text" size="small" onClick={() => openBudgetDetail(budget)}>查看详情</Button></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Card>
              )}
            </TabPane>

            {!isHousehold && <TabPane key="tax" title="税务分析">
              {failedSections.includes("税务") || incomeUnavailable || failedSections.includes("主体信息") ? (
                <EmptyState title="税务分析暂不可用" description="税务、收入或主体数据尚未加载完成，请重新加载后查看" />
              ) : (
              <>
              <div className="metric-grid metric-grid-odd metric-stack-until-lg mb-4 grid grid-cols-1 lg:grid-cols-3">
                <Card className="metric-card" style={{ borderRadius: 12 }}>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>待处理税费</div>
                  <div className="mt-2"><AmountDisplay amount={operatingSummary.pendingTax} type={2} size="large" /></div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-4)" }}>下一截止日 {enterpriseSummary?.nextTaxDueDate || "--"}</div>
                </Card>
                <Card className="metric-card" style={{ borderRadius: 12 }}>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>税负率</div>
                  <div className="mt-2 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{operatingSummary.income > 0 ? displayPercent(operatingSummary.pendingTax / operatingSummary.income) : "--"}</div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-4)" }}>按待处理税费 / 收入估算</div>
                </Card>
                <Card className="metric-card" style={{ borderRadius: 12 }}>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>政策口径</div>
                  <div className="mt-2 font-semibold">{enterpriseSummary?.company?.taxpayerType || "--"}</div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-4)" }}>{enterpriseSummary?.company?.operatingRegion || "地区待完善"}</div>
                </Card>
              </div>
              <Card title="税务事项" style={{ borderRadius: 12 }}>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[840px] table-fixed text-sm">
                    <thead>
                      <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                        {[
                          { label: "事项", align: "text-left" },
                          { label: "期间", align: "text-center" },
                          { label: "税种", align: "text-center" },
                          { label: "应缴/已缴", align: "text-right" },
                          { label: "截止日", align: "text-center" },
                          { label: "状态", align: "text-center" },
                          { label: "操作", align: "text-center" },
                        ].map((column) => (
                          <th key={column.label} className={`px-4 py-3 font-medium ${column.align}`} style={{ color: "var(--text-color-2)" }}>{column.label}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {taxItems.map((item) => {
                        const status = taxStatus(item);
                        return (
                          <tr key={item.id} className="border-b" style={{ borderColor: "var(--border-color-light)" }}>
                            <td className="px-4 py-4 align-middle">{item.name}</td>
                            <td className="px-4 py-4 text-center align-middle">{item.period}</td>
                            <td className="px-4 py-4 text-center align-middle">{taxTypeLabel(item.taxType)}</td>
                            <td className="px-4 py-4 text-right align-middle">{formatAmount(item.taxAmount)} / {formatAmount(item.paidAmount)}</td>
                            <td className="px-4 py-4 text-center align-middle">{displayDate(item.dueDate)}</td>
                            <td className="px-4 py-4 text-center align-middle"><Tag color={status.color}>{status.label}</Tag></td>
                            <td className="px-4 py-4 text-center align-middle"><Button type="text" size="small" onClick={() => openTaxDetail(item)}>查看详情</Button></td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </Card>
              </>
              )}
            </TabPane>}

            {!isHousehold && <TabPane key="people" title="人力成本">
              {failedSections.includes("员工") || failedSections.includes("部门") || failedSections.includes("主体信息") ? (
                <EmptyState title="人力成本数据暂不可用" description="员工、部门或主体数据尚未加载完成，请重新加载后查看" />
              ) : (
              <>
              <div className="bi-panel-cluster bi-cluster-lg grid grid-cols-1 lg:grid-cols-[minmax(0,1.3fr)_minmax(320px,0.7fr)]">
                <Card title="部门人力成本" style={{ borderRadius: 12 }}>
                  <ReactECharts option={departmentCostOption} style={{ height: 360 }} />
                </Card>
                <Card title="人力成本拆分" style={{ borderRadius: 12 }}>
                  <div className="space-y-3">
                    {[
                      ["员工人数", `${employees.length} 人`],
                      ["月人力成本", formatAmount(operatingSummary.peopleCost)],
                      ["平均人力成本", employees.length ? formatAmount(operatingSummary.peopleCost / employees.length) : "--"],
                      ["本月入职", `${enterpriseSummary?.hiresThisMonth || 0} 人`],
                      ["本月离职", `${enterpriseSummary?.departuresThisMonth || 0} 人`],
                    ].map(([label, value]) => (
                      <div key={label} className="flex items-center justify-between border-b pb-3" style={{ borderColor: "var(--border-color-light)" }}>
                        <span style={{ color: "var(--text-color-3)" }}>{label}</span>
                        <span className="font-medium">{value}</span>
                      </div>
                    ))}
                  </div>
                </Card>
              </div>
              <Card className="mt-4" title="人员成本明细" style={{ borderRadius: 12 }}>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[860px] table-fixed text-sm">
                    <thead>
                      <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                        {[
                          { label: "人员", align: "text-left" },
                          { label: "部门", align: "text-left" },
                          { label: "岗位", align: "text-left" },
                          { label: "薪资", align: "text-right" },
                          { label: "社保/公积金", align: "text-right" },
                          { label: "月成本", align: "text-right" },
                          { label: "操作", align: "text-center" },
                        ].map((column) => (
                          <th key={column.label} className={`px-4 py-3 font-medium ${column.align}`} style={{ color: "var(--text-color-2)" }}>{column.label}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {employees.map((employee) => (
                        <tr key={employee.id} className="border-b" style={{ borderColor: "var(--border-color-light)" }}>
                          <td className="px-4 py-4 align-middle">{employee.name}</td>
                          <td className="px-4 py-4 align-middle">{employee.departmentName || "--"}</td>
                          <td className="px-4 py-4 align-middle">{employee.position}</td>
                          <td className="px-4 py-4 text-right align-middle">{formatAmount(employee.salary)}</td>
                          <td className="px-4 py-4 text-right align-middle">{formatAmount(employee.socialInsurance + employee.housingFund)}</td>
                          <td className="px-4 py-4 text-right align-middle"><AmountDisplay amount={employee.monthlyCost} type={2} /></td>
                          <td className="px-4 py-4 text-center align-middle"><Button type="text" size="small" onClick={() => openEmployeeDetail(employee)}>查看详情</Button></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Card>
              </>
              )}
            </TabPane>}

            <TabPane key="insights" title="异常洞察">
              {failedSections.includes("经营洞察") ? (
                <EmptyState title="异常洞察暂不可用" description="重新加载后再查看大额流水与预算风险" />
              ) : (
              <div className="bi-panel-cluster bi-cluster-lg grid grid-cols-1 lg:grid-cols-2">
                <Card title="大额流水" style={{ borderRadius: 12 }}>
                  <div className="bi-flat-list">
                    {(insights?.largeTransactions || []).map((item) => (
                      <div key={item.id} className="flex items-center justify-between rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                        <div>
                          <div className="font-medium">{item.category}</div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>{displayDate(item.date)} · #{item.id}</div>
                        </div>
                        <AmountDisplay amount={item.amount} type={2} />
                      </div>
                    ))}
                    {!insights?.largeTransactions?.length && <div className="py-10 text-center text-sm" style={{ color: "var(--text-color-3)" }}>暂无大额流水</div>}
                  </div>
                </Card>
                <Card title="预算与经营风险" style={{ borderRadius: 12 }}>
                  <div className="bi-flat-list">
                    {(insights?.budgetAlerts || []).map((item) => (
                      <div key={item.name} className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                        <div className="flex items-center justify-between">
                          <span className="font-medium">{item.name}</span>
                          <Tag color={budgetRiskColors[item.riskLevel] || "orange"}>{item.riskLevel}</Tag>
                        </div>
                        <div className="mt-2 text-sm" style={{ color: "var(--text-color-3)" }}>预算使用率 {displayPercent(item.usageRate)}</div>
                      </div>
                    ))}
                    {!insights?.budgetAlerts?.length && <div className="py-10 text-center text-sm" style={{ color: "var(--text-color-3)" }}>暂无预算风险</div>}
                  </div>
                </Card>
              </div>
              )}
            </TabPane>
          </Tabs>
        </>
      )}

      <Drawer title="报表明细" visible={!!detail} width={460} footer={null} onCancel={() => setDetail(null)}>
        {detail && (
          <div className="space-y-5">
            <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color)", backgroundColor: "var(--bg-color-page)" }}>
              <div className="text-sm" style={{ color: "var(--text-color-3)" }}>{detail.subtitle}</div>
              <div className="mt-2 text-xl font-semibold" style={{ color: "var(--text-color-1)" }}>{detail.title}</div>
              {detail.amount != null && (
                <div className="mt-3">
                  <AmountDisplay amount={detail.amount} type={detail.amountType} showSign size="large" />
                </div>
              )}
              {detail.tags && <div className="mt-3 flex flex-wrap gap-2">{detail.tags}</div>}
            </div>

            <div className="space-y-3 text-sm">
              {detail.rows.map(([label, value]) => (
                <div key={label} className="flex items-center justify-between gap-4">
                  <span style={{ color: "var(--text-color-3)" }}>{label}</span>
                  <span className="text-right" style={{ color: "var(--text-color-1)" }}>{value}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </Drawer>
    </div>
  );
}
