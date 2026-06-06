"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Button,
  Card,
  Empty,
  Form,
  Grid,
  Input,
  InputNumber,
  Message,
  Modal,
  Select,
  Skeleton,
  Tag,
} from "@arco-design/web-react";
import {
  IconCalendarClock,
  IconCheckCircle,
  IconDelete,
  IconEdit,
  IconExclamationCircle,
  IconFile,
  IconPlus,
  IconRefresh,
  IconSafe,
  IconSearch,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount } from "@/lib/utils/format";
import type { EnterpriseSummary, TaxItem, TaxItemPayload } from "@/lib/types";

const { Row, Col } = Grid;
const FormItem = Form.Item;

type TaxFilters = {
  keyword: string;
  taxType: string;
  status: string;
  paymentStatus: string;
  riskLevel: string;
  period: string;
};

type TaxFormValues = {
  name: string;
  period: string;
  taxType: string;
  taxableAmount: number;
  taxAmount: number;
  paidAmount?: number;
  deductibleAmount?: number;
  taxRate?: number;
  dueDate: string;
  status: string;
  filingStatus?: string;
  paymentStatus?: string;
  frequency?: string;
  declarationDate?: string | null;
  paymentDate?: string | null;
  responsiblePerson?: string | null;
  riskLevel?: string;
  policyBasis?: string | null;
  sourceType?: string;
  note?: string | null;
};

const initialFilters: TaxFilters = {
  keyword: "",
  taxType: "",
  status: "",
  paymentStatus: "",
  riskLevel: "",
  period: "",
};

const taxTypeLabels: Record<string, { label: string; color: string; icon: ReactNode }> = {
  vat: { label: "增值税", color: "arcoblue", icon: <IconFile /> },
  corporate_income_tax: { label: "企业所得税", color: "purple", icon: <IconSafe /> },
  personal_income_tax: { label: "个税代扣", color: "orange", icon: <IconCheckCircle /> },
  surcharge: { label: "附加税", color: "gold", icon: <IconExclamationCircle /> },
  stamp_duty: { label: "印花税", color: "magenta", icon: <IconFile /> },
};

const statusLabels: Record<string, { label: string; color: string }> = {
  estimated: { label: "预估", color: "arcoblue" },
  pending: { label: "待处理", color: "orange" },
  paid: { label: "已缴清", color: "green" },
  overdue: { label: "已逾期", color: "red" },
};

const filingLabels: Record<string, { label: string; color: string }> = {
  not_started: { label: "未开始", color: "gray" },
  prepared: { label: "已准备", color: "arcoblue" },
  submitted: { label: "已申报", color: "purple" },
  accepted: { label: "已受理", color: "green" },
  overdue: { label: "申报逾期", color: "red" },
};

const paymentLabels: Record<string, { label: string; color: string }> = {
  unpaid: { label: "未缴", color: "orange" },
  partial: { label: "部分缴纳", color: "gold" },
  paid: { label: "已缴", color: "green" },
};

const frequencyLabels: Record<string, string> = {
  monthly: "月度",
  quarterly: "季度",
  annual: "年度",
  one_time: "一次性",
};

const riskLabels: Record<string, { label: string; color: string }> = {
  low: { label: "低风险", color: "green" },
  medium: { label: "需关注", color: "orange" },
  high: { label: "高风险", color: "red" },
};

const sourceLabels: Record<string, string> = {
  manual: "手工录入",
  demo_estimate: "演示估算",
  transaction: "流水测算",
  receipt: "票据归集",
  payroll: "薪酬代扣",
  policy: "政策规则",
};

function today() {
  return new Date().toISOString().slice(0, 10);
}

function nextMonthDueDate() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 2).padStart(2, "0")}-15`;
}

function displayDate(value?: string | null) {
  return value ? value.slice(0, 10) : "--";
}

function unpaidAmount(item: TaxItem) {
  return Math.max(0, Number(item.taxAmount || 0) - Number(item.paidAmount || 0));
}

function daysUntil(value?: string | null) {
  if (!value) return null;
  const start = new Date(`${today()}T00:00:00`).getTime();
  const end = new Date(`${value.slice(0, 10)}T00:00:00`).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end)) return null;
  return Math.ceil((end - start) / 86400000);
}

function dueLabel(item: TaxItem) {
  const days = daysUntil(item.dueDate);
  if (days === null) return "日期待确认";
  if (unpaidAmount(item) <= 0) return "已缴清";
  if (days < 0) return `逾期 ${Math.abs(days)} 天`;
  if (days === 0) return "今日截止";
  return `${days} 天后截止`;
}

function taxBurdenRate(items: TaxItem[]) {
  const taxable = items.reduce((sum, item) => sum + Number(item.taxableAmount || 0), 0);
  const tax = items.reduce((sum, item) => sum + Number(item.taxAmount || 0), 0);
  if (taxable <= 0) return 0;
  return (tax / taxable) * 100;
}

function toPayload(values: TaxFormValues): TaxItemPayload {
  return {
    name: values.name,
    period: values.period,
    taxType: values.taxType,
    taxableAmount: Number(values.taxableAmount || 0),
    taxAmount: Number(values.taxAmount || 0),
    paidAmount: Number(values.paidAmount || 0),
    deductibleAmount: Number(values.deductibleAmount || 0),
    taxRate: Number(values.taxRate || 0),
    dueDate: values.dueDate,
    status: values.status,
    filingStatus: values.filingStatus,
    paymentStatus: values.paymentStatus,
    frequency: values.frequency,
    declarationDate: values.declarationDate || null,
    paymentDate: values.paymentDate || null,
    responsiblePerson: values.responsiblePerson || null,
    riskLevel: values.riskLevel,
    policyBasis: values.policyBasis || null,
    sourceType: values.sourceType,
    note: values.note || null,
  };
}

export default function TaxPage() {
  const router = useRouter();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [taxItems, setTaxItems] = useState<TaxItem[]>([]);
  const [filters, setFilters] = useState<TaxFilters>(initialFilters);
  const [initialLoading, setInitialLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingItem, setEditingItem] = useState<TaxItem | null>(null);
  const [form] = Form.useForm<TaxFormValues>();

  const loadData = async (quiet = false) => {
    if (quiet) {
      setRefreshing(true);
    } else {
      setInitialLoading(true);
    }
    try {
      const [summaryRes, taxRes] = await Promise.all([
        enterpriseApi.summary(),
        enterpriseApi.taxItems(),
      ]);
      setSummary(summaryRes.data);
      setTaxItems(taxRes.data);
    } catch {
      Message.error("税务数据加载失败");
    } finally {
      setInitialLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadData();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [activeCompanyId]);

  const filteredTaxItems = useMemo(() => {
    const keyword = filters.keyword.trim().toLowerCase();
    return taxItems.filter((item) => {
      const keywordMatched = !keyword
        || item.name.toLowerCase().includes(keyword)
        || item.period.toLowerCase().includes(keyword)
        || item.taxType.toLowerCase().includes(keyword)
        || (item.note || "").toLowerCase().includes(keyword)
        || (item.responsiblePerson || "").toLowerCase().includes(keyword)
        || (item.policyBasis || "").toLowerCase().includes(keyword);
      return keywordMatched
        && (!filters.taxType || item.taxType === filters.taxType)
        && (!filters.status || item.status === filters.status)
        && (!filters.paymentStatus || item.paymentStatus === filters.paymentStatus)
        && (!filters.riskLevel || item.riskLevel === filters.riskLevel)
        && (!filters.period || item.period.includes(filters.period));
    });
  }, [filters, taxItems]);

  const taxPagination = useClientPagination(filteredTaxItems, 10);

  const pendingItems = useMemo(() => taxItems.filter((item) => unpaidAmount(item) > 0), [taxItems]);
  const pendingAmount = useMemo(() => pendingItems.reduce((sum, item) => sum + unpaidAmount(item), 0), [pendingItems]);
  const paidAmount = useMemo(() => taxItems.reduce((sum, item) => sum + Math.min(Number(item.paidAmount || 0), Number(item.taxAmount || 0)), 0), [taxItems]);
  const expectedAmount = useMemo(() => taxItems.reduce((sum, item) => sum + Number(item.taxAmount || 0), 0), [taxItems]);
  const deductibleAmount = useMemo(() => taxItems.reduce((sum, item) => sum + Number(item.deductibleAmount || 0), 0), [taxItems]);
  const riskItems = useMemo(() => taxItems.filter((item) => item.riskLevel === "high" || item.status === "overdue"), [taxItems]);
  const dueSoonItems = useMemo(
    () => pendingItems.filter((item) => {
      const days = daysUntil(item.dueDate);
      return days !== null && days >= 0 && days <= 15;
    }),
    [pendingItems]
  );
  const upcomingItems = useMemo(
    () => [...pendingItems].sort((left, right) => left.dueDate.localeCompare(right.dueDate)).slice(0, 5),
    [pendingItems]
  );
  const typeStats = useMemo(() => {
    const grouped = new Map<string, { taxType: string; count: number; taxAmount: number; unpaid: number }>();
    taxItems.forEach((item) => {
      const row = grouped.get(item.taxType) || { taxType: item.taxType, count: 0, taxAmount: 0, unpaid: 0 };
      row.count += 1;
      row.taxAmount += Number(item.taxAmount || 0);
      row.unpaid += unpaidAmount(item);
      grouped.set(item.taxType, row);
    });
    return Array.from(grouped.values()).sort((left, right) => right.unpaid - left.unpaid);
  }, [taxItems]);

  const statusStats = useMemo(() => {
    return Object.keys(statusLabels).map((status) => {
      const items = taxItems.filter((item) => item.status === status);
      return {
        status,
        count: items.length,
        amount: items.reduce((sum, item) => sum + unpaidAmount(item), 0),
      };
    });
  }, [taxItems]);

  const updateFilter = (field: keyof TaxFilters, value: string) => {
    setFilters((current) => ({ ...current, [field]: value || "" }));
    taxPagination.resetPage();
  };

  const resetFilters = () => {
    setFilters(initialFilters);
    taxPagination.resetPage();
  };

  const openCreate = () => {
    setEditingItem(null);
    form.resetFields();
    form.setFieldsValue({
      name: "",
      period: today().slice(0, 7),
      taxType: "vat",
      taxableAmount: 0,
      taxAmount: 0,
      paidAmount: 0,
      deductibleAmount: 0,
      taxRate: 0,
      dueDate: nextMonthDueDate(),
      status: "estimated",
      filingStatus: "prepared",
      paymentStatus: "unpaid",
      frequency: "monthly",
      declarationDate: null,
      paymentDate: null,
      responsiblePerson: "财务负责人",
      riskLevel: "medium",
      policyBasis: summary?.company?.policyProfileKey || "CN-DEFAULT-DEMO-POLICY",
      sourceType: "manual",
      note: "",
    });
    setModalVisible(true);
  };

  const openEdit = (item: TaxItem) => {
    setEditingItem(item);
    form.resetFields();
    form.setFieldsValue({
      name: item.name,
      period: item.period,
      taxType: item.taxType,
      taxableAmount: Number(item.taxableAmount || 0),
      taxAmount: Number(item.taxAmount || 0),
      paidAmount: Number(item.paidAmount || 0),
      deductibleAmount: Number(item.deductibleAmount || 0),
      taxRate: Number(item.taxRate || 0),
      dueDate: displayDate(item.dueDate),
      status: item.status,
      filingStatus: item.filingStatus,
      paymentStatus: item.paymentStatus,
      frequency: item.frequency,
      declarationDate: item.declarationDate || null,
      paymentDate: item.paymentDate || null,
      responsiblePerson: item.responsiblePerson || "",
      riskLevel: item.riskLevel,
      policyBasis: item.policyBasis || "",
      sourceType: item.sourceType,
      note: item.note || "",
    });
    setModalVisible(true);
  };

  const handleSubmit = async (values: TaxFormValues) => {
    try {
      const payload = toPayload(values);
      if (editingItem) {
        await enterpriseApi.updateTaxItem(editingItem.id, payload);
        Message.success("税务事项已更新");
      } else {
        await enterpriseApi.createTaxItem(payload);
        Message.success("税务事项已新增");
      }
      setModalVisible(false);
      setEditingItem(null);
      await loadData(true);
    } catch {
      Message.error("税务事项保存失败");
    }
  };

  const handleDelete = (item: TaxItem) => {
    Modal.confirm({
      title: "删除税务事项",
      content: `确定删除「${item.name}」吗？`,
      okButtonProps: { status: "danger" },
      onOk: async () => {
        try {
          await enterpriseApi.deleteTaxItem(item.id);
          Message.success("税务事项已删除");
          await loadData(true);
        } catch {
          Message.error("税务事项删除失败");
        }
      },
    });
  };

  const summaryCards = [
    {
      label: "待缴税费",
      value: <AmountDisplay amount={pendingAmount} type={2} size="large" />,
      hint: `${pendingItems.length} 项待处理`,
      icon: <IconExclamationCircle />,
      color: "var(--color-warning)",
    },
    {
      label: "本期应缴",
      value: <AmountDisplay amount={expectedAmount} type={2} size="large" />,
      hint: `已缴 ${formatAmount(paidAmount)}`,
      icon: <IconFile />,
      color: "var(--color-primary)",
    },
    {
      label: "抵扣/减免",
      value: <AmountDisplay amount={deductibleAmount} type={1} size="large" />,
      hint: "来自当前税务事项",
      icon: <IconCheckCircle />,
      color: "var(--color-success)",
    },
    {
      label: "税务风险",
      value: <span className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{riskItems.length}</span>,
      hint: `${dueSoonItems.length} 项 15 天内截止`,
      icon: <IconCalendarClock />,
      color: "var(--color-danger-light-4)",
    },
  ];

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="税务管理"
        subtitle={summary?.company
          ? `${summary.company.name} · ${summary.company.taxpayerType} · ${summary.company.operatingRegion || "地区待完善"}`
          : "税期申报、税额估算、缴纳进度和合规风险"}
        icon={<IconFile />}
        extra={
          <div className="flex items-center gap-2">
            {refreshing && <Tag color="arcoblue">刷新中</Tag>}
            <Button icon={<IconRefresh />} onClick={() => loadData(true)}>
              刷新
            </Button>
            <Button icon={<IconFile />} onClick={() => router.push("/receipts")}>
              票据凭证
            </Button>
            <Button type="primary" icon={<IconPlus />} onClick={openCreate}>
              新增税务事项
            </Button>
          </div>
        }
      />

      <Row gutter={16} className="mb-6">
        {summaryCards.map((card) => (
          <Col key={card.label} xs={12} md={6}>
            <Card style={{ borderRadius: 12, minHeight: 148 }}>
              <div className="flex min-h-[108px] flex-col justify-between">
                <div className="flex items-center justify-between">
                  <span className="text-sm" style={{ color: "var(--text-color-3)" }}>{card.label}</span>
                  <span className="inline-flex text-xl" style={{ color: card.color }}>{card.icon}</span>
                </div>
                {initialLoading ? (
                  <Skeleton />
                ) : (
                  <>
                    <div>{card.value}</div>
                    <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{card.hint}</div>
                  </>
                )}
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <div className="mb-6 grid grid-cols-1 items-start gap-4 xl:grid-cols-[minmax(0,1.4fr)_minmax(360px,0.6fr)]">
        <Card style={{ borderRadius: 12 }} title="税务画像与申报节奏">
          {initialLoading ? (
            <Skeleton />
          ) : (
            <>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
                {[
                  ["主体类型", summary?.company?.entityType === "household" ? "家庭主体" : "公司主体"],
                  ["纳税人类型", summary?.company?.taxpayerType || "--"],
                  ["主管区域", summary?.company?.taxAuthority || summary?.company?.operatingRegion || "--"],
                  ["政策画像", summary?.company?.policyProfileKey || "--"],
                ].map(([label, value]) => (
                  <div
                    key={label}
                    className="rounded-lg border px-3 py-3"
                    style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
                  >
                    <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{label}</div>
                    <div className="mt-2 truncate font-semibold" style={{ color: "var(--text-color-1)" }}>{value}</div>
                  </div>
                ))}
              </div>
              <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-4">
                {statusStats.map((row) => {
                  const meta = statusLabels[row.status];
                  return (
                    <div key={row.status} className="flex min-h-[72px] items-center justify-between rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
                      <div>
                        <Tag color={meta.color}>{meta.label}</Tag>
                        <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{row.count} 项</div>
                      </div>
                      <div className="text-right text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>
                        {formatAmount(row.amount)}
                      </div>
                    </div>
                  );
                })}
              </div>
              <div className="mt-4 text-xs" style={{ color: "var(--text-color-3)" }}>
                税负率 {taxBurdenRate(taxItems).toFixed(2)}% · 财年起始月 {summary?.company?.fiscalYearStartMonth || 1} 月
              </div>
            </>
          )}
        </Card>

        <Card style={{ borderRadius: 12 }} title="近期税期">
          {initialLoading ? (
            <Skeleton />
          ) : upcomingItems.length === 0 ? (
            <Empty description="暂无待处理税期" />
          ) : (
            <div className="space-y-3">
              {upcomingItems.map((item) => {
                const typeMeta = taxTypeLabels[item.taxType] || { label: item.taxType, color: "gray", icon: <IconFile /> };
                const risk = riskLabels[item.riskLevel] || riskLabels.medium;
                return (
                  <div key={item.id} className="flex items-start gap-3">
                    <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg" style={{ backgroundColor: "var(--color-fill-1)", color: "var(--color-primary)" }}>
                      {typeMeta.icon}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="truncate text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>{item.name}</span>
                        <Tag size="small" color={risk.color}>{risk.label}</Tag>
                      </div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        {typeMeta.label} · {displayDate(item.dueDate)} · {dueLabel(item)}
                      </div>
                      <div className="mt-1 text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>
                        {formatAmount(unpaidAmount(item))}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </Card>
      </div>

      <Card className="mb-4" style={{ borderRadius: 12 }}>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3 xl:grid-cols-[repeat(6,minmax(0,1fr))_120px]">
          <Input
            allowClear
            prefix={<IconSearch />}
            placeholder="搜索税种、期间、负责人..."
            value={filters.keyword}
            onChange={(value) => updateFilter("keyword", value)}
          />
          <Select allowClear placeholder="税种" value={filters.taxType || undefined} onChange={(value) => updateFilter("taxType", value)}>
            {Object.entries(taxTypeLabels).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select allowClear placeholder="处理状态" value={filters.status || undefined} onChange={(value) => updateFilter("status", value)}>
            {Object.entries(statusLabels).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select allowClear placeholder="缴纳状态" value={filters.paymentStatus || undefined} onChange={(value) => updateFilter("paymentStatus", value)}>
            {Object.entries(paymentLabels).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select allowClear placeholder="风险等级" value={filters.riskLevel || undefined} onChange={(value) => updateFilter("riskLevel", value)}>
            {Object.entries(riskLabels).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Input allowClear placeholder="税期，例如 2026-06" value={filters.period} onChange={(value) => updateFilter("period", value)} />
          <Button onClick={resetFilters}>重置</Button>
        </div>
      </Card>

      <Card className="mb-6" style={{ borderRadius: 12 }} title="税种结构">
        {initialLoading ? (
          <Skeleton />
        ) : typeStats.length === 0 ? (
          <Empty description="暂无税种结构" />
        ) : (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
            {typeStats.map((row) => {
              const meta = taxTypeLabels[row.taxType] || { label: row.taxType, color: "gray", icon: <IconFile /> };
              return (
                <div
                  key={row.taxType}
                  className="flex min-h-[104px] flex-col justify-between rounded-lg border px-4 py-3"
                  style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
                >
                  <div className="flex items-start justify-between">
                    <span className="grid h-9 w-9 place-items-center rounded-lg" style={{ backgroundColor: "var(--color-fill-1)", color: "var(--color-primary)" }}>
                      {meta.icon}
                    </span>
                    <Tag color={meta.color}>{row.count} 项</Tag>
                  </div>
                  <div>
                    <div className="text-sm font-medium" style={{ color: "var(--text-color-2)" }}>{meta.label}</div>
                    <div className="mt-1 text-base font-semibold" style={{ color: "var(--text-color-1)" }}>{formatAmount(row.taxAmount)}</div>
                    <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>待缴 {formatAmount(row.unpaid)}</div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Card>

      <Card style={{ borderRadius: 12 }} title="税费台账">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1040px] table-fixed border-collapse text-sm">
            <colgroup>
              <col style={{ width: "21%" }} />
              <col style={{ width: "12%" }} />
              <col style={{ width: "14%" }} />
              <col style={{ width: "16%" }} />
              <col style={{ width: "14%" }} />
              <col style={{ width: "12%" }} />
              <col style={{ width: "6%" }} />
              <col style={{ width: "5%" }} />
            </colgroup>
            <thead>
              <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                <th className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>事项</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>税期/税种</th>
                <th className="px-4 py-3 text-right font-medium" style={{ color: "var(--text-color-2)" }}>计税口径</th>
                <th className="px-4 py-3 text-right font-medium" style={{ color: "var(--text-color-2)" }}>应缴/已缴/待缴</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>申报与缴纳</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>截止/负责人</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>风险</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {initialLoading ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12"><Skeleton /></td>
                </tr>
              ) : filteredTaxItems.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12">
                    <Empty description="暂无匹配税务事项" />
                  </td>
                </tr>
              ) : taxPagination.pagedData.map((item) => {
                const typeMeta = taxTypeLabels[item.taxType] || { label: item.taxType, color: "gray", icon: <IconFile /> };
                const status = statusLabels[item.status] || { label: item.status, color: "gray" };
                const filing = filingLabels[item.filingStatus] || { label: item.filingStatus, color: "gray" };
                const payment = paymentLabels[item.paymentStatus] || { label: item.paymentStatus, color: "gray" };
                const risk = riskLabels[item.riskLevel] || riskLabels.medium;
                const unpaid = unpaidAmount(item);
                return (
                  <tr
                    key={item.id}
                    className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                    style={{ borderColor: "var(--border-color-light)" }}
                  >
                    <td className="px-4 py-4 align-middle">
                      <div className="flex items-start gap-3">
                        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg" style={{ backgroundColor: "var(--color-fill-1)", color: "var(--color-primary)" }}>
                          {typeMeta.icon}
                        </span>
                        <div className="min-w-0">
                          <div className="truncate font-semibold" style={{ color: "var(--text-color-1)" }}>{item.name}</div>
                          <div className="mt-1 flex items-center gap-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                            <Tag size="small" color={typeMeta.color}>{typeMeta.label}</Tag>
                            <span>{sourceLabels[item.sourceType] || item.sourceType}</span>
                          </div>
                          <div className="mt-1 line-clamp-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                            {item.note || "未填写备注"}
                          </div>
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{item.period}</div>
                      <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{frequencyLabels[item.frequency] || item.frequency}</div>
                    </td>
                    <td className="px-4 py-4 text-right align-middle whitespace-nowrap">
                      <div>{formatAmount(Number(item.taxableAmount || 0))}</div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>税率 {Number(item.taxRate || 0).toFixed(2)}%</div>
                      <div className="mt-1 text-xs" style={{ color: "var(--color-success)" }}>抵扣 {formatAmount(Number(item.deductibleAmount || 0))}</div>
                    </td>
                    <td className="px-4 py-4 text-right align-middle whitespace-nowrap">
                      <div><AmountDisplay amount={Number(item.taxAmount || 0)} type={2} size="small" /></div>
                      <div className="mt-1"><AmountDisplay amount={Number(item.paidAmount || 0)} type={1} size="small" /></div>
                      <div className="mt-1"><AmountDisplay amount={unpaid} type={unpaid > 0 ? 2 : 1} size="small" /></div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="flex flex-col items-center gap-2">
                        <Tag color={status.color}>{status.label}</Tag>
                        <Tag color={filing.color}>{filing.label}</Tag>
                        <Tag color={payment.color}>{payment.label}</Tag>
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="font-medium" style={{ color: item.riskLevel === "high" ? "var(--color-danger-light-4)" : "var(--text-color-1)" }}>
                        {displayDate(item.dueDate)}
                      </div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{dueLabel(item)}</div>
                      <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                        {item.responsiblePerson || "负责人待补充"}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <Tag color={risk.color}>{risk.label}</Tag>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="flex justify-center gap-1">
                        <Button type="text" size="mini" title="编辑" icon={<IconEdit />} onClick={() => openEdit(item)} />
                        <Button type="text" size="mini" title="删除" status="danger" icon={<IconDelete />} onClick={() => handleDelete(item)} />
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <AppPagination
          current={taxPagination.page}
          pageSize={taxPagination.pageSize}
          total={taxPagination.total}
          pageSizeOptions={[10, 20, 50, 100]}
          onChange={taxPagination.handleChange}
        />
      </Card>

      <Modal
        title={editingItem ? "编辑税务事项" : "新增税务事项"}
        visible={modalVisible}
        onCancel={() => {
          setModalVisible(false);
          setEditingItem(null);
        }}
        onOk={() => form.submit()}
        style={{ width: 820 }}
        unmountOnExit
      >
        <Form form={form} layout="vertical" onSubmit={handleSubmit}>
          <div className="grid grid-cols-1 gap-x-4 md:grid-cols-2">
            <FormItem label="事项名称" field="name" rules={[{ required: true, message: "请输入事项名称" }]}>
              <Input placeholder="例如：2026-06 增值税申报" />
            </FormItem>
            <FormItem label="税种" field="taxType" rules={[{ required: true, message: "请选择税种" }]}>
              <Select>
                {Object.entries(taxTypeLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="税期" field="period" rules={[{ required: true, message: "请输入税期" }]}>
              <Input placeholder="yyyy-MM 或 yyyy-Q1" />
            </FormItem>
            <FormItem label="申报频率" field="frequency">
              <Select>
                {Object.entries(frequencyLabels).map(([value, label]) => (
                  <Select.Option key={value} value={value}>{label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="计税金额" field="taxableAmount" rules={[{ required: true, message: "请输入计税金额" }]}>
              <InputNumber min={0} precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="应缴税额" field="taxAmount" rules={[{ required: true, message: "请输入应缴税额" }]}>
              <InputNumber min={0} precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="已缴税额" field="paidAmount">
              <InputNumber min={0} precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="抵扣/减免" field="deductibleAmount">
              <InputNumber min={0} precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="税率 %" field="taxRate">
              <InputNumber min={0} precision={2} placeholder="例如：3.00" />
            </FormItem>
            <FormItem label="申报截止日" field="dueDate" rules={[{ required: true, message: "请输入申报截止日" }]}>
              <Input placeholder="yyyy-MM-dd" />
            </FormItem>
            <FormItem label="申报日期" field="declarationDate">
              <Input placeholder="yyyy-MM-dd" />
            </FormItem>
            <FormItem label="缴纳日期" field="paymentDate">
              <Input placeholder="yyyy-MM-dd" />
            </FormItem>
            <FormItem label="处理状态" field="status">
              <Select>
                {Object.entries(statusLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="申报状态" field="filingStatus">
              <Select>
                {Object.entries(filingLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="缴纳状态" field="paymentStatus">
              <Select>
                {Object.entries(paymentLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="风险等级" field="riskLevel">
              <Select>
                {Object.entries(riskLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="负责人" field="responsiblePerson">
              <Input placeholder="财务负责人" />
            </FormItem>
            <FormItem label="来源" field="sourceType">
              <Select>
                {Object.entries(sourceLabels).map(([value, label]) => (
                  <Select.Option key={value} value={value}>{label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="政策依据" field="policyBasis">
              <Input placeholder="例如：CN-GD-SZ-DEMO-POLICY" />
            </FormItem>
          </div>
          <FormItem label="备注" field="note">
            <Input.TextArea rows={3} placeholder="补充申报口径、票据缺口、测算来源或风险说明" />
          </FormItem>
        </Form>
      </Modal>
    </div>
  );
}
