"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Button, Card, DatePicker, Drawer, Form, Input, Message, Modal, Select, Slider, Tag } from "@arco-design/web-react";
import {
  IconCalendar,
  IconCheckCircle,
  IconDelete,
  IconEdit,
  IconExclamationCircle,
  IconEye,
  IconFile,
  IconPlus,
  IconSearch,
} from "@arco-design/web-react/icon";
import { useTranslations } from "next-intl";
import { budgetApi } from "@/lib/api/budgets";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import PageHeader from "@/components/common/PageHeader";
import BudgetProgress from "@/components/common/BudgetProgress";
import RiskBadge from "@/components/common/RiskBadge";
import EmptyState from "@/components/common/EmptyState";
import AppPagination from "@/components/common/AppPagination";
import { formatAmount, formatDateTime, formatPercent } from "@/lib/utils/format";
import type { Budget, BudgetStatus, CreateBudgetDTO, RiskLevel } from "@/lib/types";

const FormItem = Form.Item;
const DAY = 24 * 60 * 60 * 1000;

type RiskFilter = "all" | RiskLevel;
type BudgetTypeKey = "total" | "category" | "hr" | "tax" | "project";
type BudgetTypeFilter = "all" | BudgetTypeKey;
type QuickView = "all" | "active" | "warning" | "overrun" | "thisMonth";
type BudgetFormValues = CreateBudgetDTO & { categoryId?: number; status?: BudgetStatus };

const statusConfig: Record<BudgetStatus, { label: string; color: string }> = {
  0: { label: "已停用", color: "gray" },
  1: { label: "进行中", color: "green" },
  2: { label: "已完成", color: "arcoblue" },
  3: { label: "已超支", color: "red" },
};

const riskOptions: Array<{ value: RiskFilter; label: string }> = [
  { value: "all", label: "全部风险" },
  { value: "low", label: "低风险" },
  { value: "medium", label: "中风险" },
  { value: "high", label: "高风险" },
  { value: "critical", label: "严重" },
];

const budgetTypeConfig: Record<BudgetTypeKey, { label: string; color: string }> = {
  total: { label: "总预算", color: "purple" },
  category: { label: "分类预算", color: "arcoblue" },
  hr: { label: "人力预算", color: "green" },
  tax: { label: "税费预算", color: "orange" },
  project: { label: "项目预算", color: "magenta" },
};

const budgetTypeOptions: Array<{ value: BudgetTypeFilter; label: string }> = [
  { value: "all", label: "全部类型" },
  { value: "total", label: budgetTypeConfig.total.label },
  { value: "category", label: budgetTypeConfig.category.label },
  { value: "hr", label: budgetTypeConfig.hr.label },
  { value: "tax", label: budgetTypeConfig.tax.label },
  { value: "project", label: budgetTypeConfig.project.label },
];

const quickViews: Array<{ key: QuickView; label: string }> = [
  { key: "all", label: "全部预算" },
  { key: "active", label: "进行中" },
  { key: "warning", label: "预警预算" },
  { key: "overrun", label: "已超支" },
  { key: "thisMonth", label: "本月周期" },
];

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function toIsoDate(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function currentMonthRange() {
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), 1);
  const end = new Date(now.getFullYear(), now.getMonth() + 1, 0);
  return { start: toIsoDate(start), end: toIsoDate(end) };
}

function formatIsoDate(date?: string | null) {
  if (!date) return "--";
  return date.slice(0, 10).replace(/\//g, "-");
}

function getBudgetType(budget: Budget) {
  if (!budget.categoryId) return { key: "total" as BudgetTypeKey, ...budgetTypeConfig.total };

  const text = `${budget.name} ${budget.categoryName ?? ""}`;
  if (/薪酬|工资|社保|公积金|个税|人力/.test(text)) {
    return { key: "hr" as BudgetTypeKey, ...budgetTypeConfig.hr };
  }
  if (/税|增值税|所得税|附加税/.test(text)) {
    return { key: "tax" as BudgetTypeKey, ...budgetTypeConfig.tax };
  }
  if (/项目|客户|研发|推广|营销|投放/.test(text)) {
    return { key: "project" as BudgetTypeKey, ...budgetTypeConfig.project };
  }
  return { key: "category" as BudgetTypeKey, ...budgetTypeConfig.category };
}

function overlapsRange(budget: Budget, startDate: string, endDate: string) {
  if (startDate && budget.endDate < startDate) return false;
  if (endDate && budget.startDate > endDate) return false;
  return true;
}

function getDaysLeft(endDate: string) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const end = new Date(`${endDate}T00:00:00`);
  if (Number.isNaN(end.getTime())) return 0;
  return Math.ceil((end.getTime() - today.getTime()) / DAY);
}

function getPeriodProgress(budget: Budget) {
  const start = new Date(`${budget.startDate}T00:00:00`).getTime();
  const end = new Date(`${budget.endDate}T23:59:59`).getTime();
  const now = Date.now();
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return 1;
  return Math.min(1, Math.max(0, (now - start) / (end - start)));
}

function getDeadlineText(budget: Budget) {
  const days = getDaysLeft(budget.endDate);
  if (days < 0) return `已结束 ${Math.abs(days)} 天`;
  if (days === 0) return "今日截止";
  return `${days} 天后截止`;
}

function getPaceText(budget: Budget) {
  const pace = budget.usageRate - getPeriodProgress(budget);
  if (budget.usageRate >= 1) return "已突破预算";
  if (pace > 0.2) return "快于时间进度";
  if (pace < -0.2) return "低于时间进度";
  return "节奏正常";
}

export default function BudgetsPage() {
  const t = useTranslations("budget");
  const { categories, fetchCategories } = useCategoryStore();
  const [allBudgets, setAllBudgets] = useState<Budget[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(12);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [selectedBudget, setSelectedBudget] = useState<Budget | null>(null);
  const [keyword, setKeyword] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [status, setStatus] = useState<string>("all");
  const [riskFilter, setRiskFilter] = useState<RiskFilter>("all");
  const [budgetTypeFilter, setBudgetTypeFilter] = useState<BudgetTypeFilter>("all");
  const [quickView, setQuickView] = useState<QuickView>("all");
  const [form] = Form.useForm();
  const monthRange = useMemo(() => currentMonthRange(), []);

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await budgetApi.list({ page: 0, size: 500 });
      setAllBudgets(res.data.content);
    } catch {
      Message.error("预算数据加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let cancelled = false;

    const loadBudgets = async () => {
      try {
        const res = await budgetApi.list({ page: 0, size: 500 });
        if (!cancelled) {
          setAllBudgets(res.data.content);
        }
      } catch {
        if (!cancelled) {
          Message.error("预算数据加载失败");
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchCategories();
    void loadBudgets();

    return () => {
      cancelled = true;
    };
  }, [fetchCategories]);

  const filteredBudgets = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    return allBudgets.filter((budget) => {
      const budgetType = getBudgetType(budget);
      const searchable = `${budget.name} ${budget.categoryName ?? ""} ${budgetType.label} ${budget.riskMessage ?? ""}`.toLowerCase();

      if (normalizedKeyword && !searchable.includes(normalizedKeyword)) return false;
      if (!overlapsRange(budget, startDate, endDate)) return false;
      if (status !== "all" && budget.status !== Number(status)) return false;
      if (riskFilter !== "all" && budget.riskLevel !== riskFilter) return false;
      if (budgetTypeFilter !== "all" && budgetType.key !== budgetTypeFilter) return false;
      if (quickView === "active" && budget.status !== 1) return false;
      if (quickView === "warning" && !budget.warningReached && budget.riskLevel !== "high" && budget.riskLevel !== "critical") return false;
      if (quickView === "overrun" && budget.status !== 3 && budget.riskLevel !== "critical") return false;
      if (quickView === "thisMonth" && !overlapsRange(budget, monthRange.start, monthRange.end)) return false;

      return true;
    });
  }, [allBudgets, budgetTypeFilter, endDate, keyword, monthRange.end, monthRange.start, quickView, riskFilter, startDate, status]);

  const maxPageIndex = Math.max(0, Math.ceil(filteredBudgets.length / pageSize) - 1);
  const effectivePageIndex = Math.min(pageIndex, maxPageIndex);

  const pagedBudgets = useMemo(() => {
    const start = effectivePageIndex * pageSize;
    return filteredBudgets.slice(start, start + pageSize);
  }, [effectivePageIndex, filteredBudgets, pageSize]);

  const summary = useMemo(() => {
    const totalAmount = filteredBudgets.reduce((sum, budget) => sum + Number(budget.amount || 0), 0);
    const spentAmount = filteredBudgets.reduce((sum, budget) => sum + Number(budget.spent || 0), 0);
    const remainingAmount = filteredBudgets.reduce((sum, budget) => sum + Number(budget.remainingAmount || 0), 0);
    const warningCount = filteredBudgets.filter((budget) => budget.warningReached || budget.riskLevel === "high" || budget.riskLevel === "critical").length;
    const overrunCount = filteredBudgets.filter((budget) => budget.status === 3 || budget.riskLevel === "critical").length;
    const activeCount = filteredBudgets.filter((budget) => budget.status === 1).length;
    const nearestDeadline = [...filteredBudgets]
      .filter((budget) => getDaysLeft(budget.endDate) >= 0)
      .sort((a, b) => a.endDate.localeCompare(b.endDate))[0];

    return {
      totalAmount,
      spentAmount,
      remainingAmount,
      usageRate: totalAmount > 0 ? spentAmount / totalAmount : 0,
      warningCount,
      overrunCount,
      activeCount,
      nearestDeadline,
    };
  }, [filteredBudgets]);

  const summaryCards: Array<{ label: string; value: ReactNode; helper: string; icon: ReactNode; accent: string }> = [
    {
      label: "总预算",
      value: <span style={{ color: "var(--text-color-1)" }}>{formatAmount(summary.totalAmount)}</span>,
      helper: `${filteredBudgets.length} 项预算`,
      icon: <IconFile />,
      accent: "#6366f1",
    },
    {
      label: "已使用",
      value: <span style={{ color: "#ef4444" }}>{formatAmount(summary.spentAmount)}</span>,
      helper: `使用率 ${formatPercent(summary.usageRate)}`,
      icon: <IconExclamationCircle />,
      accent: "#ef4444",
    },
    {
      label: "剩余额度",
      value: <span style={{ color: summary.remainingAmount < 0 ? "#ef4444" : "#10b981" }}>{formatAmount(summary.remainingAmount)}</span>,
      helper: summary.remainingAmount < 0 ? "存在超支" : "仍可支配",
      icon: <IconCheckCircle />,
      accent: "#10b981",
    },
    {
      label: "进行中",
      value: <span style={{ color: "var(--text-color-1)" }}>{summary.activeCount}</span>,
      helper: "当前可执行预算",
      icon: <IconCalendar />,
      accent: "#0ea5e9",
    },
    {
      label: "预警预算",
      value: <span style={{ color: summary.warningCount > 0 ? "#f59e0b" : "var(--text-color-1)" }}>{summary.warningCount}</span>,
      helper: "已触发阈值",
      icon: <IconExclamationCircle />,
      accent: "#f59e0b",
    },
    {
      label: "最近截止",
      value: <span style={{ color: "var(--text-color-1)" }}>{summary.nearestDeadline ? getDeadlineText(summary.nearestDeadline) : "--"}</span>,
      helper: summary.nearestDeadline?.name ?? "暂无未到期预算",
      icon: <IconCalendar />,
      accent: "#8b5cf6",
    },
  ];

  const handleSearch = () => {
    setPageIndex(0);
  };

  const handleReset = () => {
    setKeyword("");
    setStartDate("");
    setEndDate("");
    setStatus("all");
    setRiskFilter("all");
    setBudgetTypeFilter("all");
    setQuickView("all");
    setPageIndex(0);
  };

  const handlePageChange = (page: number, size: number) => {
    setPageIndex(page - 1);
    setPageSize(size);
  };

  const handleQuickView = (view: QuickView) => {
    setQuickView(view);
    setPageIndex(0);
  };

  const openCreate = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({
      startDate: monthRange.start,
      endDate: monthRange.end,
      warningThreshold: 80,
    });
    setModalVisible(true);
  };

  const openEdit = (budget: Budget) => {
    setEditingId(budget.id);
    form.setFieldsValue({
      ...budget,
      categoryId: budget.categoryId || undefined,
      status: budget.status,
    });
    setModalVisible(true);
  };

  const handleSubmit = async (values: BudgetFormValues) => {
    try {
      const data = {
        name: values.name,
        amount: Number(values.amount || 0),
        startDate: String(values.startDate || ""),
        endDate: String(values.endDate || ""),
        warningThreshold: Number(values.warningThreshold ?? 80),
        categoryId: values.categoryId || undefined,
      };

      if (editingId) {
        await budgetApi.update(editingId, { ...data, status: values.status });
        Message.success("更新成功");
      } else {
        await budgetApi.create(data);
        Message.success("创建成功");
      }
      setModalVisible(false);
      form.resetFields();
      setEditingId(null);
      await fetchData();
    } catch {
      Message.error("操作失败");
    }
  };

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: "确认删除",
      content: "确定要删除这个预算吗？",
      onOk: async () => {
        try {
          await budgetApi.delete(id);
          Message.success("删除成功");
          if (selectedBudget?.id === id) {
            setSelectedBudget(null);
          }
          await fetchData();
        } catch {
          Message.error("删除失败");
        }
      },
    });
  };

  const renderActions = (budget: Budget) => (
    <div className="flex items-center gap-1" onClick={(event) => event.stopPropagation()}>
      <Button
        type="text"
        size="mini"
        icon={<IconEye />}
        onClick={() => setSelectedBudget(budget)}
        style={{ color: "var(--text-color-3)" }}
      />
      <Button
        type="text"
        size="mini"
        icon={<IconEdit />}
        onClick={() => openEdit(budget)}
        style={{ color: "var(--text-color-3)" }}
      />
      <Button
        type="text"
        size="mini"
        status="danger"
        icon={<IconDelete />}
        onClick={() => handleDelete(budget.id)}
      />
    </div>
  );

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="经营预算"
        subtitle="预算周期、执行进度和超支预警统一管理"
        icon={<IconCalendar />}
        extra={
          <Button type="primary" icon={<IconPlus />} onClick={openCreate}>
            {t("new")}
          </Button>
        }
      />

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-6">
        {summaryCards.map((item) => (
          <Card key={item.label} style={{ borderRadius: 12 }}>
            <div className="flex h-[92px] flex-col justify-between">
              <div className="flex items-center justify-between">
                <span className="text-xs" style={{ color: "var(--text-color-3)" }}>{item.label}</span>
                <span className="grid h-8 w-8 place-items-center rounded-lg text-base" style={{ backgroundColor: `${item.accent}18`, color: item.accent }}>
                  {item.icon}
                </span>
              </div>
              <div className="truncate text-lg font-semibold">{item.value}</div>
              <div className="truncate text-xs" style={{ color: "var(--text-color-4)" }}>{item.helper}</div>
            </div>
          </Card>
        ))}
      </div>

      <Card className="mb-4" style={{ borderRadius: 12 }}>
        <div className="mb-3 flex flex-wrap gap-2">
          {quickViews.map((item) => {
            const active = quickView === item.key;
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

        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[minmax(280px,1fr)_150px_150px_170px]">
          <Input
            prefix={<IconSearch style={{ color: "var(--text-color-4)" }} />}
            placeholder="搜索预算名称、分类或风险..."
            value={keyword}
            onChange={setKeyword}
            onPressEnter={handleSearch}
            className="w-full"
            style={{ borderRadius: 12 }}
          />
          <Select value={status} onChange={(value) => setStatus(String(value))} style={{ width: "100%", borderRadius: 12 }}>
            <Select.Option value="all">全部状态</Select.Option>
            <Select.Option value="1">进行中</Select.Option>
            <Select.Option value="2">已完成</Select.Option>
            <Select.Option value="3">已超支</Select.Option>
            <Select.Option value="0">已停用</Select.Option>
          </Select>
          <Select value={riskFilter} onChange={(value) => setRiskFilter(value as RiskFilter)} style={{ width: "100%", borderRadius: 12 }}>
            {riskOptions.map((item) => (
              <Select.Option key={item.value} value={item.value}>{item.label}</Select.Option>
            ))}
          </Select>
          <Select value={budgetTypeFilter} onChange={(value) => setBudgetTypeFilter(value as BudgetTypeFilter)} style={{ width: "100%", borderRadius: 12 }}>
            {budgetTypeOptions.map((item) => (
              <Select.Option key={item.value} value={item.value}>{item.label}</Select.Option>
            ))}
          </Select>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-3">
          <div className="grid w-full grid-cols-[minmax(0,1fr)_20px_minmax(0,1fr)] items-center md:w-[356px]">
            <DatePicker
              format="YYYY-MM-DD"
              value={startDate}
              onChange={(value) => setStartDate(String(value || ""))}
              placeholder="起始日期"
              className="w-full"
              style={{ borderRadius: 12 }}
            />
            <span className="text-center text-sm" style={{ color: "var(--text-color-4)" }}>-</span>
            <DatePicker
              format="YYYY-MM-DD"
              value={endDate}
              onChange={(value) => setEndDate(String(value || ""))}
              placeholder="结束日期"
              className="w-full"
              style={{ borderRadius: 12 }}
            />
          </div>
          <Button type="primary" className="w-full md:w-[110px]" onClick={handleSearch}>
            搜索
          </Button>
          <Button className="w-full md:w-[110px]" onClick={handleReset}>
            重置
          </Button>
        </div>
      </Card>

      <Card
        title={
          <div className="flex items-center gap-2">
            <IconFile />
            <span>预算执行台账</span>
            <Tag color="arcoblue">{filteredBudgets.length}</Tag>
          </div>
        }
        loading={loading}
        style={{ borderRadius: 12 }}
      >
        {pagedBudgets.length === 0 && !loading ? (
          <EmptyState
            icon="🎯"
            title="暂无预算"
            description="创建预算来控制公司、部门或项目成本"
            actionText="创建预算"
            onAction={openCreate}
          />
        ) : (
          <>
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full min-w-[1080px] table-fixed border-collapse text-sm">
                <colgroup>
                  <col style={{ width: 210 }} />
                  <col style={{ width: 138 }} />
                  <col style={{ width: 160 }} />
                  <col style={{ width: 190 }} />
                  <col style={{ width: 82 }} />
                  <col style={{ width: 96 }} />
                  <col style={{ width: 120 }} />
                  <col style={{ width: 84 }} />
                </colgroup>
                <thead>
                  <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                    {["预算与口径", "周期", "金额", "执行进度", "状态", "风险", "预警", "操作"].map((column) => (
                      <th key={column} className="px-3 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>
                        {column}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {pagedBudgets.map((budget) => {
                    const budgetType = getBudgetType(budget);
                    return (
                      <tr
                        key={budget.id}
                        onClick={() => setSelectedBudget(budget)}
                        className="cursor-pointer border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                        style={{ borderColor: "var(--border-color-light)" }}
                      >
                        <td className="px-3 py-4 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{budget.name}</div>
                          <div className="mt-2 flex flex-wrap gap-2">
                            <Tag color={budgetType.color}>{budgetType.label}</Tag>
                            <Tag color={budget.categoryName ? "arcoblue" : "purple"}>{budget.categoryName || "公司整体"}</Tag>
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
                            {formatIsoDate(budget.startDate)}
                          </div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>
                            至 {formatIsoDate(budget.endDate)}
                          </div>
                          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                            {getDeadlineText(budget)}
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{formatAmount(budget.amount)}</div>
                          <div className="mt-1 text-xs" style={{ color: "#ef4444" }}>已用 {formatAmount(budget.spent)}</div>
                          <div className="mt-1 text-xs" style={{ color: budget.remainingAmount < 0 ? "#ef4444" : "#10b981" }}>
                            剩余 {formatAmount(budget.remainingAmount)}
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <BudgetProgress
                            spent={budget.spent}
                            amount={budget.amount}
                            usageRate={budget.usageRate}
                            warningThreshold={budget.warningThreshold}
                            riskLevel={budget.riskLevel}
                            showLabel={false}
                          />
                          <div className="mt-2 flex justify-between text-xs" style={{ color: "var(--text-color-4)" }}>
                            <span>{formatPercent(budget.usageRate)}</span>
                            <span>时间 {formatPercent(getPeriodProgress(budget))}</span>
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <Tag color={statusConfig[budget.status]?.color || "gray"}>{statusConfig[budget.status]?.label || "未知"}</Tag>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <RiskBadge level={budget.riskLevel} />
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <div className="text-xs" style={{ color: "var(--text-color-3)" }}>阈值 {budget.warningThreshold}%</div>
                          <div className="mt-1 text-xs" style={{ color: budget.warningReached ? "#f59e0b" : "var(--text-color-4)" }}>
                            {getPaceText(budget)}
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          {renderActions(budget)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="space-y-3 md:hidden">
              {pagedBudgets.map((budget) => {
                const budgetType = getBudgetType(budget);
                return (
                  <button
                    key={budget.id}
                    type="button"
                    onClick={() => setSelectedBudget(budget)}
                    className="w-full cursor-pointer rounded-xl border p-4 text-left transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                    style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-card)" }}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="truncate font-medium" style={{ color: "var(--text-color-1)" }}>{budget.name}</div>
                        <div className="mt-2 flex flex-wrap gap-2">
                          <Tag color={budgetType.color}>{budgetType.label}</Tag>
                          <RiskBadge level={budget.riskLevel} />
                        </div>
                      </div>
                      <div className="text-right">
                        <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{formatPercent(budget.usageRate)}</div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>{getDeadlineText(budget)}</div>
                      </div>
                    </div>
                    <div className="mt-4">
                      <BudgetProgress
                        spent={budget.spent}
                        amount={budget.amount}
                        usageRate={budget.usageRate}
                        warningThreshold={budget.warningThreshold}
                        riskLevel={budget.riskLevel}
                      />
                    </div>
                  </button>
                );
              })}
            </div>

            <AppPagination
              current={effectivePageIndex + 1}
              pageSize={pageSize}
              total={filteredBudgets.length}
              pageSizeOptions={[10, 12, 24, 48]}
              onChange={handlePageChange}
            />
          </>
        )}
      </Card>

      <Drawer
        title="预算详情"
        visible={!!selectedBudget}
        width={480}
        footer={null}
        onCancel={() => setSelectedBudget(null)}
      >
        {selectedBudget && (
          <div className="space-y-5">
            <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color)", backgroundColor: "var(--bg-color-page)" }}>
              <div className="text-sm" style={{ color: "var(--text-color-3)" }}>{selectedBudget.name}</div>
              <div className="mt-2 text-2xl font-semibold" style={{ color: "var(--text-color-1)" }}>
                {formatAmount(selectedBudget.amount)}
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <Tag color={getBudgetType(selectedBudget).color}>{getBudgetType(selectedBudget).label}</Tag>
                <Tag color={statusConfig[selectedBudget.status]?.color || "gray"}>{statusConfig[selectedBudget.status]?.label || "未知"}</Tag>
                <RiskBadge level={selectedBudget.riskLevel} />
              </div>
              <div className="mt-4">
                <BudgetProgress
                  spent={selectedBudget.spent}
                  amount={selectedBudget.amount}
                  usageRate={selectedBudget.usageRate}
                  warningThreshold={selectedBudget.warningThreshold}
                  riskLevel={selectedBudget.riskLevel}
                />
              </div>
            </div>

            <div>
              <div className="mb-3 text-sm font-medium" style={{ color: "var(--text-color-1)" }}>执行情况</div>
              <div className="grid grid-cols-2 gap-3">
                {[
                  ["已使用", formatAmount(selectedBudget.spent), "#ef4444"],
                  ["剩余额度", formatAmount(selectedBudget.remainingAmount), selectedBudget.remainingAmount < 0 ? "#ef4444" : "#10b981"],
                  ["使用率", formatPercent(selectedBudget.usageRate), "var(--text-color-1)"],
                  ["时间进度", formatPercent(getPeriodProgress(selectedBudget)), "var(--text-color-1)"],
                ].map(([label, value, color]) => (
                  <div key={label} className="rounded-xl border p-3" style={{ borderColor: "var(--border-color)" }}>
                    <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{label}</div>
                    <div className="mt-2 font-semibold" style={{ color }}>{value}</div>
                  </div>
                ))}
              </div>
            </div>

            <div>
              <div className="mb-3 text-sm font-medium" style={{ color: "var(--text-color-1)" }}>基础信息</div>
              <div className="space-y-3 text-sm">
                {[
                  ["预算编号", `#${selectedBudget.id}`],
                  ["统计口径", selectedBudget.categoryName || "公司整体"],
                  ["预算周期", `${formatIsoDate(selectedBudget.startDate)} - ${formatIsoDate(selectedBudget.endDate)}`],
                  ["截止状态", getDeadlineText(selectedBudget)],
                  ["预警阈值", `${selectedBudget.warningThreshold}%`],
                  ["风险判断", selectedBudget.riskMessage || getPaceText(selectedBudget)],
                  ["创建时间", formatDateTime(selectedBudget.createdAt)],
                  ["更新时间", formatDateTime(selectedBudget.updatedAt)],
                ].map(([label, value]) => (
                  <div key={label} className="flex items-center justify-between gap-4">
                    <span style={{ color: "var(--text-color-3)" }}>{label}</span>
                    <span className="text-right" style={{ color: "var(--text-color-1)" }}>{value}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="flex gap-2">
              <Button type="primary" icon={<IconEdit />} onClick={() => openEdit(selectedBudget)}>
                编辑预算
              </Button>
              <Button status="danger" icon={<IconDelete />} onClick={() => handleDelete(selectedBudget.id)}>
                删除预算
              </Button>
            </div>
          </div>
        )}
      </Drawer>

      <Modal
        title={editingId ? "编辑预算" : t("new")}
        visible={modalVisible}
        onCancel={() => {
          setModalVisible(false);
          setEditingId(null);
        }}
        onOk={() => form.submit()}
        style={{ borderRadius: 16 }}
        unmountOnExit
      >
        <Form form={form} layout="vertical" onSubmit={handleSubmit}>
          <FormItem label={t("name")} field="name" rules={[{ required: true, message: "请输入名称" }]}>
            <Input placeholder="预算名称" style={{ borderRadius: 12 }} />
          </FormItem>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <FormItem label={t("amount")} field="amount" rules={[{ required: true, message: "请输入金额" }]}>
              <Input type="number" placeholder="0.00" style={{ borderRadius: 12 }} />
            </FormItem>
            <FormItem label={t("category")} field="categoryId">
              <Select placeholder="选择分类，留空为总预算" allowClear style={{ borderRadius: 12 }}>
                {categories.filter((category) => category.type === "expense").map((category) => (
                  <Select.Option key={category.id} value={category.id}>
                    {category.icon} {category.name}
                  </Select.Option>
                ))}
              </Select>
            </FormItem>
          </div>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <FormItem label={t("startDate")} field="startDate" rules={[{ required: true, message: "请选择开始日期" }]}>
              <DatePicker format="YYYY-MM-DD" className="w-full" style={{ borderRadius: 12 }} />
            </FormItem>
            <FormItem label={t("endDate")} field="endDate" rules={[{ required: true, message: "请选择结束日期" }]}>
              <DatePicker format="YYYY-MM-DD" className="w-full" style={{ borderRadius: 12 }} />
            </FormItem>
          </div>

          {editingId && (
            <FormItem label="预算状态" field="status">
              <Select style={{ borderRadius: 12 }}>
                <Select.Option value={1}>进行中</Select.Option>
                <Select.Option value={2}>已完成</Select.Option>
                <Select.Option value={3}>已超支</Select.Option>
                <Select.Option value={0}>已停用</Select.Option>
              </Select>
            </FormItem>
          )}

          <FormItem label={`${t("warningThreshold")}%`} field="warningThreshold" initialValue={80}>
            <Slider min={50} max={100} marks={{ 50: "50%", 70: "70%", 80: "80%", 90: "90%", 100: "100%" }} />
          </FormItem>
        </Form>
      </Modal>
    </div>
  );
}
