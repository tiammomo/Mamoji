"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Button, Card, DatePicker, Drawer, Form, Input, Message, Modal, Select, Switch, Tag } from "@arco-design/web-react";
import {
  IconCalendar,
  IconCheckCircle,
  IconDelete,
  IconEdit,
  IconExclamationCircle,
  IconEye,
  IconFile,
  IconPlayArrow,
  IconPlus,
  IconSearch,
} from "@arco-design/web-react/icon";
import { useTranslations } from "next-intl";
import { recurringApi } from "@/lib/api/recurring";
import type { CreateRecurringDTO, RecurringItem } from "@/lib/api/recurring";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import EmptyState from "@/components/common/EmptyState";
import AppPagination from "@/components/common/AppPagination";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount, formatDate } from "@/lib/utils/format";

const FormItem = Form.Item;
const DAY = 24 * 60 * 60 * 1000;

type QuickView = "all" | "today" | "week" | "overdue" | "active" | "income" | "expense";
type TypeFilter = "all" | "1" | "2";
type StatusFilter = "all" | "1" | "0";
type FrequencyFilter = "all" | RecurringItem["frequency"];
type CategoryKey = "finance" | "tax" | "hr" | "operation" | "other";
type CategoryFilter = "all" | CategoryKey;
type RecurringFormValues = CreateRecurringDTO;

const quickViews: Array<{ key: QuickView; label: string }> = [
  { key: "all", label: "全部事项" },
  { key: "today", label: "今日待办" },
  { key: "week", label: "7 天内" },
  { key: "overdue", label: "已逾期" },
  { key: "active", label: "启用中" },
  { key: "income", label: "周期收入" },
  { key: "expense", label: "周期支出" },
];

const categoryConfig: Record<CategoryKey, { label: string; color: string }> = {
  finance: { label: "财务事项", color: "arcoblue" },
  tax: { label: "税务合规", color: "orange" },
  hr: { label: "HR 事项", color: "green" },
  operation: { label: "经营管理", color: "purple" },
  other: { label: "其他事项", color: "gray" },
};

const categoryOptions: Array<{ value: CategoryFilter; label: string }> = [
  { value: "all", label: "全部事项类型" },
  { value: "finance", label: categoryConfig.finance.label },
  { value: "tax", label: categoryConfig.tax.label },
  { value: "hr", label: categoryConfig.hr.label },
  { value: "operation", label: categoryConfig.operation.label },
  { value: "other", label: categoryConfig.other.label },
];

const frequencyOptions: Array<{ value: FrequencyFilter; label: string }> = [
  { value: "all", label: "全部周期" },
  { value: "daily", label: "每天" },
  { value: "weekly", label: "每周" },
  { value: "monthly", label: "每月" },
  { value: "yearly", label: "每年" },
];

const weekDayOptions = [
  { value: 1, label: "周一" },
  { value: 2, label: "周二" },
  { value: 3, label: "周三" },
  { value: 4, label: "周四" },
  { value: 5, label: "周五" },
  { value: 6, label: "周六" },
  { value: 7, label: "周日" },
];

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function toIsoDate(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function todayIso() {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return toIsoDate(today);
}

function currentMonthRange() {
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), 1);
  const end = new Date(now.getFullYear(), now.getMonth() + 1, 0);
  return { start: toIsoDate(start), end: toIsoDate(end) };
}

function getDaysUntil(date: string) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const target = new Date(`${date}T00:00:00`);
  if (Number.isNaN(target.getTime())) return 0;
  return Math.ceil((target.getTime() - today.getTime()) / DAY);
}

function getDueText(item: RecurringItem) {
  const days = getDaysUntil(item.nextExecution);
  if (item.status !== 1) return "已暂停";
  if (days < 0) return `逾期 ${Math.abs(days)} 天`;
  if (days === 0) return "今日执行";
  return `${days} 天后执行`;
}

function getDueLevel(item: RecurringItem): "normal" | "soon" | "overdue" | "paused" {
  if (item.status !== 1) return "paused";
  const days = getDaysUntil(item.nextExecution);
  if (days < 0) return "overdue";
  if (days <= 7) return "soon";
  return "normal";
}

function inferCategory(item: RecurringItem) {
  const text = `${item.name} ${item.note ?? ""}`.toLowerCase();
  if (/税|报税|申报|增值税|所得税|个税|发票|票据/.test(text)) {
    return { key: "tax" as CategoryKey, ...categoryConfig.tax };
  }
  if (/工资|薪酬|社保|公积金|入职|离职|合同|试用期|员工|人事/.test(text)) {
    return { key: "hr" as CategoryKey, ...categoryConfig.hr };
  }
  if (/复盘|预算|回款|续签|项目|客户|会议|经营/.test(text)) {
    return { key: "operation" as CategoryKey, ...categoryConfig.operation };
  }
  if (/租金|水电|订阅|软件|服务器|办公|采购|差旅|餐饮|收款|付款/.test(text)) {
    return { key: "finance" as CategoryKey, ...categoryConfig.finance };
  }
  return { key: "other" as CategoryKey, ...categoryConfig.other };
}

function getFrequencyLabel(item: RecurringItem) {
  const intervalText = item.interval > 1 ? `每 ${item.interval} ` : "每";
  if (item.frequency === "daily") return item.interval > 1 ? `每 ${item.interval} 天` : "每天";
  if (item.frequency === "weekly") {
    const day = weekDayOptions.find((option) => option.value === item.dayOfWeek)?.label;
    return `${intervalText}周${day ? ` · ${day}` : ""}`;
  }
  if (item.frequency === "yearly") {
    const month = item.monthOfYear ? `${item.monthOfYear} 月` : "";
    const day = item.dayOfMonth ? `${item.dayOfMonth} 日` : "";
    return `${intervalText}年${month || day ? ` · ${month}${day}` : ""}`;
  }
  const day = item.dayOfMonth ? `${item.dayOfMonth} 日` : "";
  return `${intervalText}月${day ? ` · ${day}` : ""}`;
}

function isInRange(date: string, startDate: string, endDate: string) {
  if (startDate && date < startDate) return false;
  if (endDate && date > endDate) return false;
  return true;
}

function isThisMonth(date: string, monthRange: { start: string; end: string }) {
  return date >= monthRange.start && date <= monthRange.end;
}

function isActiveInMonth(item: RecurringItem, monthRange: { start: string; end: string }) {
  if (item.status !== 1) return false;
  if (item.startDate > monthRange.end) return false;
  if (item.endDate && item.endDate < monthRange.start) return false;
  return true;
}

export default function RecurringPage() {
  const t = useTranslations("recurring");
  const activeSubjectType = useAppStore((state) => state.activeSubjectType);
  const isHousehold = activeSubjectType === "household";
  const [items, setItems] = useState<RecurringItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [selectedItem, setSelectedItem] = useState<RecurringItem | null>(null);
  const [keyword, setKeyword] = useState("");
  const [quickView, setQuickView] = useState<QuickView>("all");
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("all");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [frequencyFilter, setFrequencyFilter] = useState<FrequencyFilter>("all");
  const [categoryFilter, setCategoryFilter] = useState<CategoryFilter>("all");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [form] = Form.useForm();
  const monthRange = useMemo(() => currentMonthRange(), []);
  const visibleCategoryOptions = useMemo(
    () => isHousehold
      ? categoryOptions.filter((option) => option.value === "all" || option.value === "finance" || option.value === "other")
      : categoryOptions,
    [isHousehold]
  );
  const effectiveCategoryFilter = visibleCategoryOptions.some((option) => option.value === categoryFilter)
    ? categoryFilter
    : "all";

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await recurringApi.list();
      setItems(res.data);
    } catch {
      Message.error("周期事项加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let cancelled = false;

    const loadRecurring = async () => {
      try {
        const res = await recurringApi.list();
        if (!cancelled) {
          setItems(res.data);
        }
      } catch {
        if (!cancelled) {
          Message.error("周期事项加载失败");
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void loadRecurring();

    return () => {
      cancelled = true;
    };
  }, []);

  const sortedItems = useMemo(() => {
    return [...items].sort((a, b) => {
      const dueCompare = a.nextExecution.localeCompare(b.nextExecution);
      if (dueCompare !== 0) return dueCompare;
      return a.name.localeCompare(b.name);
    });
  }, [items]);

  const filteredItems = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    const today = todayIso();

    return sortedItems.filter((item) => {
      const category = inferCategory(item);
      const searchable = `${item.name} ${item.note ?? ""} ${category.label} ${getFrequencyLabel(item)}`.toLowerCase();
      const days = getDaysUntil(item.nextExecution);

      if (normalizedKeyword && !searchable.includes(normalizedKeyword)) return false;
      if (typeFilter !== "all" && String(item.type) !== typeFilter) return false;
      if (statusFilter !== "all" && String(item.status) !== statusFilter) return false;
      if (frequencyFilter !== "all" && item.frequency !== frequencyFilter) return false;
      if (effectiveCategoryFilter !== "all" && category.key !== effectiveCategoryFilter) return false;
      if (!isInRange(item.nextExecution, startDate, endDate)) return false;
      if (quickView === "today" && item.nextExecution !== today) return false;
      if (quickView === "week" && (days < 0 || days > 7 || item.status !== 1)) return false;
      if (quickView === "overdue" && (days >= 0 || item.status !== 1)) return false;
      if (quickView === "active" && item.status !== 1) return false;
      if (quickView === "income" && item.type !== 1) return false;
      if (quickView === "expense" && item.type !== 2) return false;

      return true;
    });
  }, [effectiveCategoryFilter, endDate, frequencyFilter, keyword, quickView, sortedItems, startDate, statusFilter, typeFilter]);

  const maxPageIndex = Math.max(0, Math.ceil(filteredItems.length / pageSize) - 1);
  const effectivePageIndex = Math.min(pageIndex, maxPageIndex);
  const pagedItems = useMemo(() => {
    const start = effectivePageIndex * pageSize;
    return filteredItems.slice(start, start + pageSize);
  }, [effectivePageIndex, filteredItems, pageSize]);

  const summary = useMemo(() => {
    const activeItems = filteredItems.filter((item) => item.status === 1);
    const dueTodayCount = activeItems.filter((item) => item.nextExecution === todayIso()).length;
    const dueWeekCount = activeItems.filter((item) => {
      const days = getDaysUntil(item.nextExecution);
      return days >= 0 && days <= 7;
    }).length;
    const overdueCount = activeItems.filter((item) => getDaysUntil(item.nextExecution) < 0).length;
    const monthIncome = activeItems
      .filter((item) => item.type === 1 && isActiveInMonth(item, monthRange))
      .reduce((sum, item) => sum + Number(item.amount || 0), 0);
    const monthExpense = activeItems
      .filter((item) => item.type === 2 && isActiveInMonth(item, monthRange))
      .reduce((sum, item) => sum + Number(item.amount || 0), 0);
    const nearestItem = activeItems.filter((item) => getDaysUntil(item.nextExecution) >= 0)[0];

    return {
      activeCount: activeItems.length,
      dueTodayCount,
      dueWeekCount,
      overdueCount,
      monthIncome,
      monthExpense,
      nearestItem,
    };
  }, [filteredItems, monthRange]);

  const summaryCards: Array<{ label: string; value: ReactNode; helper: string; icon: ReactNode; accent: string }> = [
    {
      label: "今日待办",
      value: <span style={{ color: summary.dueTodayCount > 0 ? "var(--color-warning)" : "var(--text-color-1)" }}>{summary.dueTodayCount}</span>,
      helper: "今天需要处理",
      icon: <IconCalendar />,
      accent: "#a85a42",
    },
    {
      label: "7 天内到期",
      value: <span style={{ color: summary.dueWeekCount > 0 ? "#6366f1" : "var(--text-color-1)" }}>{summary.dueWeekCount}</span>,
      helper: "近期经营节奏",
      icon: <IconExclamationCircle />,
      accent: "#6366f1",
    },
    {
      label: "已逾期",
      value: <span style={{ color: summary.overdueCount > 0 ? "#ef4444" : "var(--text-color-1)" }}>{summary.overdueCount}</span>,
      helper: "需要立即处理",
      icon: <IconExclamationCircle />,
      accent: "#ef4444",
    },
    {
      label: "启用事项",
      value: <span style={{ color: "var(--text-color-1)" }}>{summary.activeCount}</span>,
      helper: "当前有效规则",
      icon: <IconCheckCircle />,
      accent: "#10b981",
    },
    {
      label: "本月预计收入",
      value: <span style={{ color: "#10b981" }}>{formatAmount(summary.monthIncome)}</span>,
      helper: "按周期规则估算",
      icon: <IconFile />,
      accent: "#10b981",
    },
    {
      label: "本月预计支出",
      value: <span style={{ color: "#ef4444" }}>{formatAmount(summary.monthExpense)}</span>,
      helper: summary.nearestItem ? `最近：${summary.nearestItem.name}` : "暂无待执行",
      icon: <IconFile />,
      accent: "#ef4444",
    },
  ];

  const handleReset = () => {
    setKeyword("");
    setQuickView("all");
    setTypeFilter("all");
    setStatusFilter("all");
    setFrequencyFilter("all");
    setCategoryFilter("all");
    setStartDate("");
    setEndDate("");
    setPageIndex(0);
  };

  const handlePageChange = (page: number, size: number) => {
    setPageIndex(page - 1);
    setPageSize(size);
  };

  const openCreate = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({
      type: 2,
      amount: undefined,
      frequency: "monthly",
      interval: 1,
      startDate: todayIso(),
    });
    setModalVisible(true);
  };

  const openEdit = (item: RecurringItem) => {
    setEditingId(item.id);
    form.setFieldsValue({
      name: item.name,
      type: item.type,
      amount: item.amount,
      frequency: item.frequency,
      interval: item.interval,
      dayOfWeek: item.dayOfWeek,
      dayOfMonth: item.dayOfMonth,
      monthOfYear: item.monthOfYear,
      startDate: item.startDate,
      endDate: item.endDate,
      note: item.note,
    });
    setModalVisible(true);
  };

  const handleSubmit = async (values: RecurringFormValues) => {
    try {
      const data: CreateRecurringDTO = {
        name: values.name,
        type: Number(values.type) as 1 | 2,
        amount: Number(values.amount || 0),
        frequency: values.frequency,
        interval: Number(values.interval || 1),
        dayOfWeek: values.dayOfWeek ? Number(values.dayOfWeek) : undefined,
        dayOfMonth: values.dayOfMonth ? Number(values.dayOfMonth) : undefined,
        monthOfYear: values.monthOfYear ? Number(values.monthOfYear) : undefined,
        startDate: String(values.startDate || ""),
        endDate: values.endDate ? String(values.endDate) : undefined,
        note: values.note || undefined,
      };

      if (editingId) {
        await recurringApi.update(editingId, data);
        Message.success("更新成功");
      } else {
        await recurringApi.create(data);
        Message.success("创建成功");
      }

      setModalVisible(false);
      setEditingId(null);
      form.resetFields();
      await fetchData();
    } catch {
      Message.error(editingId ? "更新失败" : "创建失败");
    }
  };

  const handleToggle = async (item: RecurringItem) => {
    try {
      await recurringApi.toggle(item.id);
      Message.success(item.status === 1 ? "已暂停" : "已启用");
      await fetchData();
    } catch {
      Message.error("操作失败");
    }
  };

  const handleExecute = async (item: RecurringItem) => {
    try {
      await recurringApi.execute(item.id);
      Message.success(isHousehold ? "执行成功，已生成家庭收支记录" : "执行成功，已生成经营流水");
      await fetchData();
    } catch {
      Message.error("执行失败");
    }
  };

  const handleDelete = (item: RecurringItem) => {
    Modal.confirm({
      title: "确认删除",
      content: `确定要删除「${item.name}」吗？`,
      onOk: async () => {
        try {
          await recurringApi.delete(item.id);
          Message.success("删除成功");
          if (selectedItem?.id === item.id) {
            setSelectedItem(null);
          }
          await fetchData();
        } catch {
          Message.error("删除失败");
        }
      },
    });
  };

  const renderActions = (item: RecurringItem) => (
    <div className="flex items-center gap-1" onClick={(event) => event.stopPropagation()}>
      <Button
        type="text"
        size="mini"
        icon={<IconEye />}
        onClick={() => setSelectedItem(item)}
        style={{ color: "var(--text-color-3)" }}
      />
      <Button
        type="text"
        size="mini"
        icon={<IconPlayArrow />}
        disabled={item.status !== 1}
        onClick={() => handleExecute(item)}
        style={{ color: item.status === 1 ? "var(--color-primary)" : "var(--text-color-4)" }}
      />
      <Button
        type="text"
        size="mini"
        icon={<IconEdit />}
        onClick={() => openEdit(item)}
        style={{ color: "var(--text-color-3)" }}
      />
      <Button
        type="text"
        size="mini"
        status="danger"
        icon={<IconDelete />}
        onClick={() => handleDelete(item)}
      />
    </div>
  );

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title={t("title")}
        subtitle={isHousehold ? "房租、贷款、订阅、固定收入和家庭周期事项统一管理" : "固定收入、固定支出、税务、HR 和经营节奏统一管理"}
        icon={<IconCalendar />}
        extra={
          <Button type="primary" icon={<IconPlus />} onClick={openCreate}>
            {t("new")}
          </Button>
        }
      />

      <div className="metric-grid grid grid-cols-2 lg:grid-cols-6">
        {summaryCards.map((item) => (
          <Card className="metric-card" key={item.label} style={{ borderRadius: 12 }}>
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

      <Card className="filter-card mb-4" style={{ borderRadius: 12 }}>
        <div className="mb-3 flex flex-wrap gap-2">
          {quickViews.map((item) => {
            const active = quickView === item.key;
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => {
                  setQuickView(item.key);
                  setPageIndex(0);
                }}
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

        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[minmax(260px,1fr)_140px_140px_150px_170px]">
          <Input
            prefix={<IconSearch style={{ color: "var(--text-color-4)" }} />}
            placeholder="搜索事项名称、备注或类型..."
            value={keyword}
            onChange={(value) => {
              setKeyword(value);
              setPageIndex(0);
            }}
            onPressEnter={() => setPageIndex(0)}
            className="w-full"
            style={{ borderRadius: 12 }}
          />
          <Select value={typeFilter} onChange={(value) => setTypeFilter(value as TypeFilter)} style={{ width: "100%", borderRadius: 12 }}>
            <Select.Option value="all">全部收支</Select.Option>
            <Select.Option value="1">收入</Select.Option>
            <Select.Option value="2">成本支出</Select.Option>
          </Select>
          <Select value={statusFilter} onChange={(value) => setStatusFilter(value as StatusFilter)} style={{ width: "100%", borderRadius: 12 }}>
            <Select.Option value="all">全部状态</Select.Option>
            <Select.Option value="1">启用中</Select.Option>
            <Select.Option value="0">已暂停</Select.Option>
          </Select>
          <Select value={frequencyFilter} onChange={(value) => setFrequencyFilter(value as FrequencyFilter)} style={{ width: "100%", borderRadius: 12 }}>
            {frequencyOptions.map((item) => (
              <Select.Option key={item.value} value={item.value}>{item.label}</Select.Option>
            ))}
          </Select>
          <Select value={effectiveCategoryFilter} onChange={(value) => setCategoryFilter(value as CategoryFilter)} style={{ width: "100%", borderRadius: 12 }}>
            {visibleCategoryOptions.map((item) => (
              <Select.Option key={item.value} value={item.value}>{item.label}</Select.Option>
            ))}
          </Select>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-3">
          <div className="grid w-full grid-cols-[minmax(0,1fr)_20px_minmax(0,1fr)] items-center md:w-[356px]">
            <DatePicker
              format="YYYY-MM-DD"
              value={startDate}
              onChange={(value) => {
                setStartDate(String(value || ""));
                setPageIndex(0);
              }}
              placeholder="起始日期"
              className="w-full"
              style={{ borderRadius: 12 }}
            />
            <span className="text-center text-sm" style={{ color: "var(--text-color-4)" }}>-</span>
            <DatePicker
              format="YYYY-MM-DD"
              value={endDate}
              onChange={(value) => {
                setEndDate(String(value || ""));
                setPageIndex(0);
              }}
              placeholder="结束日期"
              className="w-full"
              style={{ borderRadius: 12 }}
            />
          </div>
          <Button type="primary" className="w-full md:w-[110px]" onClick={() => setPageIndex(0)}>
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
            <span>周期事项台账</span>
            <Tag color="arcoblue">{filteredItems.length}</Tag>
          </div>
        }
        loading={loading}
        style={{ borderRadius: 12 }}
      >
        {pagedItems.length === 0 && !loading ? (
          <EmptyState
            icon={<IconCalendar style={{ fontSize: 56, color: "var(--text-color-4)" }} />}
            title="暂无周期事项"
            description={isHousehold ? "创建房租、贷款、订阅、固定收入等家庭周期事项" : "创建固定收入、成本、税务或 HR 节点，形成企业经营日历"}
            actionText="新建周期事项"
            onAction={openCreate}
          />
        ) : (
          <>
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full min-w-[1004px] table-fixed border-collapse text-sm">
                <colgroup>
                  <col style={{ width: 205 }} />
                  <col style={{ width: 128 }} />
                  <col style={{ width: 136 }} />
                  <col style={{ width: 128 }} />
                  <col style={{ width: 145 }} />
                  <col style={{ width: 96 }} />
                  <col style={{ width: 90 }} />
                  <col style={{ width: 76 }} />
                </colgroup>
                <thead>
                  <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                    {["事项与类型", "周期规则", "金额影响", "下次执行", "有效期", "执行情况", "状态", "操作"].map((column) => (
                      <th key={column} className="px-3 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>
                        {column}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {pagedItems.map((item) => {
                    const category = inferCategory(item);
                    const dueLevel = getDueLevel(item);
                    return (
                      <tr
                        key={item.id}
                        onClick={() => setSelectedItem(item)}
                        className="cursor-pointer border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                        style={{ borderColor: "var(--border-color-light)" }}
                      >
                        <td className="px-3 py-4 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{item.name}</div>
                          <div className="mt-2 flex flex-wrap gap-2">
                            <Tag color={category.color}>{category.label}</Tag>
                            <Tag color={item.type === 1 ? "green" : "red"}>{item.type === 1 ? "收入" : "成本支出"}</Tag>
                          </div>
                          {item.note && (
                            <div className="mt-2 truncate text-xs" style={{ color: "var(--text-color-4)" }}>{item.note}</div>
                          )}
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{getFrequencyLabel(item)}</div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>间隔 {item.interval}</div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <AmountDisplay amount={item.amount} type={item.type} showSign size="medium" />
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>
                            {isThisMonth(item.nextExecution, monthRange) ? "计入本月预估" : "非本月执行"}
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{formatDate(item.nextExecution)}</div>
                          <div
                            className="mt-2 inline-flex rounded-md px-2 py-1 text-xs"
                            style={{
                              color: dueLevel === "overdue" ? "#ef4444" : dueLevel === "soon" ? "var(--color-warning)" : "var(--text-color-3)",
                              backgroundColor: dueLevel === "overdue" ? "#ef44441a" : dueLevel === "soon" ? "var(--color-warning-soft)" : "var(--bg-color-page)",
                            }}
                          >
                            {getDueText(item)}
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <div style={{ color: "var(--text-color-1)" }}>{formatDate(item.startDate)}</div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>
                            至 {item.endDate ? formatDate(item.endDate) : "长期有效"}
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{item.executionCount} 次</div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-4)" }}>
                            上次 {item.lastExecuted ? formatDate(item.lastExecuted) : "尚未执行"}
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          <div className="flex items-center gap-2">
                            <Switch size="small" checked={item.status === 1} onChange={() => handleToggle(item)} />
                            <span className="text-xs" style={{ color: item.status === 1 ? "#10b981" : "var(--text-color-4)" }}>
                              {item.status === 1 ? "启用" : "暂停"}
                            </span>
                          </div>
                        </td>
                        <td className="px-3 py-4 align-middle">
                          {renderActions(item)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="space-y-3 md:hidden">
              {pagedItems.map((item) => {
                const category = inferCategory(item);
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => setSelectedItem(item)}
                    className="w-full cursor-pointer rounded-xl border p-4 text-left transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                    style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-card)" }}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="truncate font-medium" style={{ color: "var(--text-color-1)" }}>{item.name}</div>
                        <div className="mt-2 flex flex-wrap gap-2">
                          <Tag color={category.color}>{category.label}</Tag>
                          <Tag color={item.type === 1 ? "green" : "red"}>{item.type === 1 ? "收入" : "成本支出"}</Tag>
                        </div>
                      </div>
                      <AmountDisplay amount={item.amount} type={item.type} showSign size="medium" />
                    </div>
                    <div className="mt-4 grid grid-cols-2 gap-3 text-xs" style={{ color: "var(--text-color-3)" }}>
                      <div>下次：{formatDate(item.nextExecution)}</div>
                      <div className="text-right">{getDueText(item)}</div>
                      <div>{getFrequencyLabel(item)}</div>
                      <div className="text-right">{item.status === 1 ? "启用中" : "已暂停"}</div>
                    </div>
                  </button>
                );
              })}
            </div>

            <AppPagination
              current={effectivePageIndex + 1}
              pageSize={pageSize}
              total={filteredItems.length}
              pageSizeOptions={[10, 20, 50, 100]}
              onChange={handlePageChange}
            />
          </>
        )}
      </Card>

      <Drawer
        title="事项详情"
        visible={!!selectedItem}
        width={480}
        footer={null}
        onCancel={() => setSelectedItem(null)}
      >
        {selectedItem && (
          <div className="space-y-5">
            <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color)", backgroundColor: "var(--bg-color-page)" }}>
              <div className="text-sm" style={{ color: "var(--text-color-3)" }}>{selectedItem.name}</div>
              <div className="mt-2">
                <AmountDisplay amount={selectedItem.amount} type={selectedItem.type} showSign size="large" />
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <Tag color={inferCategory(selectedItem).color}>{inferCategory(selectedItem).label}</Tag>
                <Tag color={selectedItem.type === 1 ? "green" : "red"}>{selectedItem.type === 1 ? "收入" : "成本支出"}</Tag>
                <Tag color={selectedItem.status === 1 ? "green" : "gray"}>{selectedItem.status === 1 ? "启用中" : "已暂停"}</Tag>
              </div>
            </div>

            <div>
              <div className="mb-3 text-sm font-medium" style={{ color: "var(--text-color-1)" }}>执行节奏</div>
              <div className="grid grid-cols-2 gap-3">
                {[
                  ["周期规则", getFrequencyLabel(selectedItem), "var(--text-color-1)"],
                  ["下次执行", formatDate(selectedItem.nextExecution), "var(--text-color-1)"],
                  ["执行提醒", getDueText(selectedItem), getDueLevel(selectedItem) === "overdue" ? "#ef4444" : "var(--color-warning)"],
                  ["执行次数", `${selectedItem.executionCount} 次`, "var(--text-color-1)"],
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
                  ["事项编号", `#${selectedItem.id.slice(0, 8)}`],
                  ["开始日期", formatDate(selectedItem.startDate)],
                  ["截止日期", selectedItem.endDate ? formatDate(selectedItem.endDate) : "长期有效"],
                  ["上次执行", selectedItem.lastExecuted ? formatDate(selectedItem.lastExecuted) : "尚未执行"],
                  ["执行方式", "手动确认后生成经营流水"],
                  ["备注", selectedItem.note || "--"],
                ].map(([label, value]) => (
                  <div key={label} className="flex items-center justify-between gap-4">
                    <span style={{ color: "var(--text-color-3)" }}>{label}</span>
                    <span className="text-right" style={{ color: "var(--text-color-1)" }}>{value}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="flex flex-wrap gap-2">
              <Button type="primary" icon={<IconPlayArrow />} disabled={selectedItem.status !== 1} onClick={() => handleExecute(selectedItem)}>
                执行并生成流水
              </Button>
              <Button icon={<IconEdit />} onClick={() => openEdit(selectedItem)}>
                编辑事项
              </Button>
              <Button status="danger" icon={<IconDelete />} onClick={() => handleDelete(selectedItem)}>
                删除事项
              </Button>
            </div>
          </div>
        )}
      </Drawer>

      <Modal
        title={editingId ? "编辑周期事项" : t("new")}
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
          <FormItem label={t("name")} field="name" rules={[{ required: true, message: "请输入事项名称" }]}>
            <Input placeholder="例如：办公室租金、个税申报、员工合同续签" style={{ borderRadius: 12 }} />
          </FormItem>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <FormItem label="收支方向" field="type" rules={[{ required: true, message: "请选择收支方向" }]}>
              <Select style={{ borderRadius: 12 }}>
                <Select.Option value={1}>收入</Select.Option>
                <Select.Option value={2}>成本支出</Select.Option>
              </Select>
            </FormItem>
            <FormItem label="金额" field="amount" rules={[{ required: true, message: "请输入金额" }]}>
              <Input type="number" placeholder="0.00" style={{ borderRadius: 12 }} />
            </FormItem>
          </div>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <FormItem label={t("frequency")} field="frequency" rules={[{ required: true, message: "请选择周期" }]}>
              <Select style={{ borderRadius: 12 }}>
                <Select.Option value="daily">{t("daily")}</Select.Option>
                <Select.Option value="weekly">{t("weekly")}</Select.Option>
                <Select.Option value="monthly">{t("monthly")}</Select.Option>
                <Select.Option value="yearly">{t("yearly")}</Select.Option>
              </Select>
            </FormItem>
            <FormItem label={t("interval")} field="interval" initialValue={1}>
              <Input type="number" min={1} style={{ borderRadius: 12 }} />
            </FormItem>
          </div>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <FormItem label="每周几" field="dayOfWeek">
              <Select placeholder="可选" allowClear style={{ borderRadius: 12 }}>
                {weekDayOptions.map((item) => (
                  <Select.Option key={item.value} value={item.value}>{item.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="每月几号" field="dayOfMonth">
              <Input type="number" min={1} max={31} placeholder="可选" style={{ borderRadius: 12 }} />
            </FormItem>
            <FormItem label="每年几月" field="monthOfYear">
              <Input type="number" min={1} max={12} placeholder="可选" style={{ borderRadius: 12 }} />
            </FormItem>
          </div>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <FormItem label={t("startDate")} field="startDate" rules={[{ required: true, message: "请选择开始日期" }]}>
              <DatePicker format="YYYY-MM-DD" className="w-full" style={{ borderRadius: 12 }} />
            </FormItem>
            <FormItem label={t("endDate")} field="endDate">
              <DatePicker format="YYYY-MM-DD" className="w-full" placeholder="不设置则长期有效" allowClear style={{ borderRadius: 12 }} />
            </FormItem>
          </div>

          <FormItem label="备注" field="note">
            <Input.TextArea placeholder="可记录负责人、执行口径、票据要求或审批说明" style={{ borderRadius: 12 }} />
          </FormItem>
        </Form>
      </Modal>
    </div>
  );
}
