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
import { receiptApi } from "@/lib/api/receipts";
import { useAsyncAction } from "@/lib/hooks/useAsyncAction";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount } from "@/lib/utils/format";
import type { EnterpriseSummary, ReceiptSummary, ReceiptVoucher, TaxComplianceReport, TaxItem, TaxItemPayload } from "@/lib/types";

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

type ComplianceIssue = {
  key: string;
  title: string;
  description: string;
  severity: "high" | "medium" | "low";
  item?: TaxItem;
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

function voucherTypeName(value: string) {
  return ({
    sales_invoice: "销项发票",
    purchase_invoice: "进项发票",
    receipt: "收据",
    bank_slip: "银行回单",
    contract: "合同付款",
    reimbursement: "报销凭证",
    tax_receipt: "税务回执",
  } as Record<string, string>)[value] || value;
}

const severityLabels: Record<ComplianceIssue["severity"], { label: string; color: string; weight: number }> = {
  high: { label: "高风险", color: "red", weight: 3 },
  medium: { label: "需关注", color: "orange", weight: 2 },
  low: { label: "提示", color: "arcoblue", weight: 1 },
};

function normalizeSeverity(value?: string): ComplianceIssue["severity"] {
  if (value === "high" || value === "medium" || value === "low") {
    return value;
  }
  return "low";
}

const taxMaterialChecklist: Record<string, string[]> = {
  vat: ["销项发票", "进项/成本票据", "收入流水", "免税或减征依据"],
  corporate_income_tax: ["利润表", "成本费用票据", "税会差异台账", "优惠政策依据"],
  personal_income_tax: ["薪酬表", "社保公积金明细", "专项附加扣除", "个税扣缴申报表"],
  surcharge: ["增值税申报结果", "附加税费申报表", "减免政策口径"],
  stamp_duty: ["合同台账", "应税凭证", "计税金额", "减免政策口径"],
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

function dueDateLabel(value?: string | null) {
  const days = daysUntil(value);
  if (days === null) return "日期待确认";
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

function readinessChecks(item: TaxItem) {
  return [
    { label: "责任人", done: Boolean(item.responsiblePerson) },
    { label: "计税口径", done: Number(item.taxableAmount || 0) >= 0 && Number(item.taxAmount || 0) >= 0 },
    { label: "政策依据", done: Boolean(item.policyBasis) },
    { label: "申报状态", done: ["prepared", "submitted", "accepted"].includes(item.filingStatus) },
    { label: "缴纳进度", done: item.paymentStatus === "paid" || unpaidAmount(item) > 0 },
  ];
}

function readinessPercent(item: TaxItem) {
  const checks = readinessChecks(item);
  return Math.round((checks.filter((check) => check.done).length / checks.length) * 100);
}

function complianceIssuesFor(items: TaxItem[]): ComplianceIssue[] {
  const issues: ComplianceIssue[] = [];
  items.forEach((item) => {
    const days = daysUntil(item.dueDate);
    const unpaid = unpaidAmount(item);
    if (unpaid > 0 && days !== null && days < 0) {
      issues.push({
        key: `${item.id}-overdue`,
        title: "税款已逾期",
        description: `${item.name} 已逾期 ${Math.abs(days)} 天，待缴 ${formatAmount(unpaid)}`,
        severity: "high",
        item,
      });
    } else if (unpaid > 0 && days !== null && days <= 15) {
      issues.push({
        key: `${item.id}-due-soon`,
        title: "临近申报缴纳截止日",
        description: `${item.name} ${days === 0 ? "今日截止" : `${days} 天后截止`}`,
        severity: "medium",
        item,
      });
    }
    if (!["submitted", "accepted"].includes(item.filingStatus) && unpaid > 0) {
      issues.push({
        key: `${item.id}-filing`,
        title: "申报状态未闭环",
        description: `${item.name} 当前为「${filingLabels[item.filingStatus]?.label || item.filingStatus}」`,
        severity: days !== null && days <= 7 ? "high" : "medium",
        item,
      });
    }
    if (!item.responsiblePerson) {
      issues.push({
        key: `${item.id}-owner`,
        title: "负责人缺失",
        description: `${item.name} 未设置税务责任人`,
        severity: "medium",
        item,
      });
    }
    if (!item.policyBasis) {
      issues.push({
        key: `${item.id}-policy`,
        title: "政策依据缺失",
        description: `${item.name} 未设置政策画像或填报依据`,
        severity: "low",
        item,
      });
    }
  });
  return issues.sort((left, right) => severityLabels[right.severity].weight - severityLabels[left.severity].weight);
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
  const [taxCompliance, setTaxCompliance] = useState<TaxComplianceReport | null>(null);
  const [receiptSummary, setReceiptSummary] = useState<ReceiptSummary | null>(null);
  const [receiptVouchers, setReceiptVouchers] = useState<ReceiptVoucher[]>([]);
  const [filters, setFilters] = useState<TaxFilters>(initialFilters);
  const [initialLoading, setInitialLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingItem, setEditingItem] = useState<TaxItem | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [form] = Form.useForm<TaxFormValues>();
  const action = useAsyncAction<string>();
  const saving = action.isRunning("save");

  const loadData = async (quiet = false) => {
    if (quiet) {
      setRefreshing(true);
    } else {
      setInitialLoading(true);
    }
    try {
      const [summaryRes, taxRes, taxComplianceRes, receiptSummaryRes, receiptListRes] = await Promise.all([
        enterpriseApi.summary(),
        enterpriseApi.taxItems(),
        enterpriseApi.taxCompliance().catch(() => ({ data: null as TaxComplianceReport | null })),
        receiptApi.summary(),
        receiptApi.list({ page: 0, size: 1000 }),
      ]);
      setSummary(summaryRes.data);
      setTaxItems(taxRes.data);
      setTaxCompliance(taxComplianceRes.data);
      setReceiptSummary(receiptSummaryRes.data);
      setReceiptVouchers(receiptListRes.data.content);
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
  const openItems = useMemo(() => taxItems.filter((item) =>
    unpaidAmount(item) > 0
    || item.status !== "paid"
    || !["submitted", "accepted"].includes(item.filingStatus)
  ), [taxItems]);
  const pendingAmount = useMemo(() => pendingItems.reduce((sum, item) => sum + unpaidAmount(item), 0), [pendingItems]);
  const paidAmount = useMemo(() => taxItems.reduce((sum, item) => sum + Math.min(Number(item.paidAmount || 0), Number(item.taxAmount || 0)), 0), [taxItems]);
  const deductibleAmount = useMemo(() => taxItems.reduce((sum, item) => sum + Number(item.deductibleAmount || 0), 0), [taxItems]);
  const riskItems = useMemo(() => taxItems.filter((item) => item.riskLevel === "high" || item.status === "overdue"), [taxItems]);
  const dueSoonItems = useMemo(
    () => openItems.filter((item) => {
      const days = daysUntil(item.dueDate);
      return days !== null && days >= 0 && days <= 15;
    }),
    [openItems]
  );
  const upcomingItems = useMemo(
    () => [...openItems].sort((left, right) => left.dueDate.localeCompare(right.dueDate)).slice(0, 5),
    [openItems]
  );
  const priorityTaxItems = useMemo(() => {
    const seen = new Set<number>();
    return [...riskItems, ...dueSoonItems, ...openItems]
      .filter((item) => {
        if (seen.has(item.id)) return false;
        seen.add(item.id);
        return true;
      })
      .sort((left, right) => {
        const leftDays = daysUntil(left.dueDate) ?? 9999;
        const rightDays = daysUntil(right.dueDate) ?? 9999;
        return leftDays - rightDays;
      })
      .slice(0, 6);
  }, [dueSoonItems, openItems, riskItems]);
  const calendarItems = useMemo(() => {
    if (taxCompliance?.filingCalendar?.length) {
      return taxCompliance.filingCalendar
        .map((item) => ({
          key: item.key,
          name: `${item.taxTypeName} ${item.period}`,
          taxType: item.taxType,
          taxTypeName: item.taxTypeName,
          period: item.period,
          dueDate: item.dueDate,
          filingStatus: item.filingStatus,
          riskLevel: item.riskLevel,
          zeroDeclarationRequired: item.zeroDeclarationRequired,
        }))
        .slice(0, 8);
    }
    return [...taxItems]
      .sort((left, right) => left.dueDate.localeCompare(right.dueDate))
      .slice(0, 8)
      .map((item) => ({
        key: String(item.id),
        name: item.name,
        taxType: item.taxType,
        taxTypeName: taxTypeLabels[item.taxType]?.label || item.taxType,
        period: item.period,
        dueDate: item.dueDate,
        filingStatus: item.filingStatus,
        riskLevel: item.riskLevel,
        zeroDeclarationRequired: Number(item.taxAmount || 0) === 0,
      }));
  }, [taxCompliance, taxItems]);
  const complianceIssues = useMemo(() => {
    if (taxCompliance?.riskItems?.length) {
      return taxCompliance.riskItems.slice(0, 6).map((item) => ({
        key: item.key,
        title: item.title,
        description: item.action ? `${item.description}；建议：${item.action}` : item.description,
        severity: normalizeSeverity(item.severity),
      }));
    }
    return complianceIssuesFor(taxItems).slice(0, 6);
  }, [taxCompliance, taxItems]);
  const averageReadiness = useMemo(() => {
    if (taxItems.length === 0) return 0;
    return Math.round(taxItems.reduce((sum, item) => sum + readinessPercent(item), 0) / taxItems.length);
  }, [taxItems]);
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

  const vatItems = useMemo(() => taxItems.filter((item) => item.taxType === "vat"), [taxItems]);
  const vatLedger = useMemo(() => {
    const declaredTaxableAmount = vatItems.reduce((sum, item) => sum + Number(item.taxableAmount || 0), 0);
    const declaredTaxAmount = vatItems.reduce((sum, item) => sum + Number(item.taxAmount || 0), 0);
    const paidVatAmount = vatItems.reduce((sum, item) => sum + Number(item.paidAmount || 0), 0);
    const outputTaxAmount = Number(receiptSummary?.outputTaxAmount || 0);
    const inputTaxAmount = Number(receiptSummary?.deductibleTaxAmount || 0);
    const salesInvoiceAmount = Number(receiptSummary?.salesInvoiceAmount || 0);
    const purchaseInvoiceAmount = Number(receiptSummary?.purchaseInvoiceAmount || 0);
    const payableByReceipts = Math.max(0, outputTaxAmount - inputTaxAmount);
    return {
      declaredTaxableAmount,
      declaredTaxAmount,
      paidVatAmount,
      unpaidVatAmount: Math.max(0, declaredTaxAmount - paidVatAmount),
      outputTaxAmount,
      inputTaxAmount,
      salesInvoiceAmount,
      purchaseInvoiceAmount,
      payableByReceipts,
    };
  }, [receiptSummary, vatItems]);

  const receiptComplianceIssues = useMemo(() => {
    const issues: Array<{ title: string; description: string; severity: ComplianceIssue["severity"] }> = [];
    if ((receiptSummary?.uncheckedInvoiceCount || 0) > 0) {
      issues.push({
        title: "发票待查验",
        description: `${receiptSummary?.uncheckedInvoiceCount || 0} 张发票尚未完成查验，影响入账和税务归档。`,
        severity: "high",
      });
    }
    if ((receiptSummary?.pendingDeductionCount || 0) > 0) {
      issues.push({
        title: "进项抵扣未闭环",
        description: `${receiptSummary?.pendingDeductionCount || 0} 张进项发票处于待确认或可抵扣状态。`,
        severity: "medium",
      });
    }
    if ((receiptSummary?.reimbursementPendingAmount || 0) > 0) {
      issues.push({
        title: "报销流程待处理",
        description: `报销待审批/待付款 ${formatAmount(receiptSummary?.reimbursementPendingAmount || 0)}。`,
        severity: "medium",
      });
    }
    if ((receiptSummary?.missingTransactionCount || 0) > 0) {
      issues.push({
        title: "凭证未关联流水",
        description: `${receiptSummary?.missingTransactionCount || 0} 张凭证未关联经营流水或资金流水。`,
        severity: "medium",
      });
    }
    if ((receiptSummary?.missingAttachmentCount || 0) > 0) {
      issues.push({
        title: "附件缺口",
        description: `${receiptSummary?.missingAttachmentCount || 0} 张凭证缺少附件原件或扫描件。`,
        severity: "low",
      });
    }
    if ((receiptSummary?.missingTaxPeriodCount || 0) > 0) {
      issues.push({
        title: "税期缺失",
        description: `${receiptSummary?.missingTaxPeriodCount || 0} 张税务凭证未设置税期。`,
        severity: "low",
      });
    }
    return issues;
  }, [receiptSummary]);
  const receiptGapCount = useMemo(() => {
    const counts = [
      receiptSummary?.uncheckedInvoiceCount,
      receiptSummary?.pendingDeductionCount,
      receiptSummary?.missingTransactionCount,
      receiptSummary?.missingAttachmentCount,
      receiptSummary?.missingTaxPeriodCount,
    ];
    return counts.reduce<number>((sum, count) => sum + Number(count || 0), 0);
  }, [receiptSummary]);
  const priorityComplianceRisks = useMemo(
    () => (taxCompliance?.riskItems || []).slice(0, 6),
    [taxCompliance]
  );
  const nextMissingCalendarItem = useMemo(
    () => taxCompliance?.filingCalendar.find((item) => !item.matchedTaxItemId) || null,
    [taxCompliance]
  );
  const priorityVouchers = useMemo(() => {
    return receiptVouchers
      .filter((voucher) =>
        voucher.riskLevel === "high"
        || voucher.riskLevel === "critical"
        || (voucher.invoiceCheckStatus !== "not_required" && voucher.invoiceCheckStatus !== "verified")
        || voucher.deductionStatus === "pending"
        || voucher.deductionStatus === "deductible"
        || (voucher.voucherType === "reimbursement" && !["paid", "archived"].includes(voucher.reimbursementStatus))
      )
      .slice(0, 5);
  }, [receiptVouchers]);
  const policyProfile = taxCompliance?.policyProfile;
  const isSmallScaleTaxpayer = policyProfile
    ? policyProfile.vatFrequency === "quarterly" || policyProfile.taxpayerType.includes("小规模")
    : summary?.company?.taxpayerType?.includes("小规模") ?? true;
  const startupProfileRows = [
    ["所在地", policyProfile?.region || summary?.company?.operatingRegion || "中国/广东省/深圳市"],
    ["纳税人模板", policyProfile ? `${policyProfile.taxpayerType} · ${frequencyLabels[policyProfile.vatFrequency] || policyProfile.vatFrequency}增值税` : isSmallScaleTaxpayer ? "小规模 · 季度增值税" : "一般纳税人 · 月度增值税"],
    ["核心税种", policyProfile?.coreTaxes?.join("、") || "增值税、企业所得税、个税、附加税费、印花税"],
    ["发票口径", policyProfile?.vatMode || (isSmallScaleTaxpayer ? "成本归档为主" : "进项抵扣闭环")],
    ["申报责任", "代理记账 / 财务负责人"],
    ["政策画像", policyProfile?.key || summary?.company?.policyProfileKey || "CN-GD-SZ-STARTUP-LITE"],
  ];

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
      period: nextMissingCalendarItem?.period || (isSmallScaleTaxpayer ? `${today().slice(0, 4)}-Q${Math.floor(new Date().getMonth() / 3) + 1}` : today().slice(0, 7)),
      taxType: nextMissingCalendarItem?.taxType || "vat",
      taxableAmount: 0,
      taxAmount: 0,
      paidAmount: 0,
      deductibleAmount: 0,
      taxRate: 0,
      dueDate: nextMissingCalendarItem?.dueDate || nextMonthDueDate(),
      status: "estimated",
      filingStatus: "prepared",
      paymentStatus: "unpaid",
      frequency: nextMissingCalendarItem?.frequency || (isSmallScaleTaxpayer ? "quarterly" : "monthly"),
      declarationDate: null,
      paymentDate: null,
      responsiblePerson: "代理记账/财务负责人",
      riskLevel: "medium",
      policyBasis: nextMissingCalendarItem?.policyBasis || policyProfile?.key || summary?.company?.policyProfileKey || "CN-GD-SZ-STARTUP-LITE",
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
    await action.run("save", async () => {
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
    });
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

  const handleMarkSubmitted = async (item: TaxItem) => {
    await action.run(`quick:${item.id}`, async () => {
      try {
        await enterpriseApi.updateTaxItem(item.id, {
          status: item.status === "estimated" ? "pending" : item.status,
          filingStatus: "submitted",
          declarationDate: today(),
        });
        Message.success("已标记为已申报");
        await loadData(true);
      } catch {
        Message.error("申报状态更新失败");
      }
    });
  };

  const handleMarkPaid = async (item: TaxItem) => {
    await action.run(`quick:${item.id}`, async () => {
      try {
        await enterpriseApi.updateTaxItem(item.id, {
          status: "paid",
          filingStatus: "accepted",
          paymentStatus: "paid",
          paidAmount: Number(item.taxAmount || 0),
          paymentDate: today(),
        });
        Message.success("已标记为已缴纳");
        await loadData(true);
      } catch {
        Message.error("缴纳状态更新失败");
      }
    });
  };

  const nextDueItem = upcomingItems[0] || null;
  const nextComplianceDueItem = calendarItems.find((item) => item.filingStatus !== "accepted") || null;
  const nextDisplayDueItem = nextComplianceDueItem || nextDueItem;
  const complianceReceiptGapCount = taxCompliance?.metrics.receiptGapCount ?? receiptGapCount;
  const filingCompletionRate = taxCompliance?.metrics.filingCompletionRate ?? averageReadiness;
  const summaryCards = [
    {
      label: "待缴税费",
      value: <AmountDisplay amount={pendingAmount} type={2} size="large" />,
      hint: `${openItems.length} 个待办 · 合规风险 ${taxCompliance?.metrics.riskCount ?? complianceIssues.length} 项`,
      icon: <IconExclamationCircle />,
      color: "var(--color-warning)",
    },
    {
      label: "最近截止日",
      value: (
        <span className="text-xl font-semibold" style={{ color: "var(--text-color-1)" }}>
          {nextDisplayDueItem ? displayDate(nextDisplayDueItem.dueDate) : "--"}
        </span>
      ),
      hint: nextDisplayDueItem ? dueDateLabel(nextDisplayDueItem.dueDate) : "暂无待处理税期",
      icon: <IconCalendarClock />,
      color: "var(--color-primary)",
    },
    {
      label: "票据缺口",
      value: <span className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{complianceReceiptGapCount}</span>,
      hint: `票据与税期缺口 · 待建档 ${taxCompliance?.metrics.missingPeriodCount ?? 0}`,
      icon: <IconFile />,
      color: "var(--color-danger-light-4)",
    },
    {
      label: "缴纳闭环",
      value: <AmountDisplay amount={paidAmount} type={1} size="large" />,
      hint: `申报闭环 ${filingCompletionRate}% · 抵扣/减免 ${formatAmount(deductibleAmount)}`,
      icon: <IconCheckCircle />,
      color: "var(--color-success)",
    },
  ];

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="税务合规"
        subtitle={summary?.company
          ? `${summary.company.name} · 深圳初创公司轻税务模板`
          : "深圳初创公司轻税务模板"}
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

      <Row gutter={16} className="metric-grid">
        {summaryCards.map((card) => (
          <Col key={card.label} xs={12} md={6}>
            <Card className="metric-card" style={{ borderRadius: 12, minHeight: 132 }}>
              <div className="flex min-h-[92px] flex-col justify-between">
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

      <div className="mb-6 grid grid-cols-1 items-start gap-4 xl:grid-cols-[0.9fr_1.1fr]">
        <div className="tax-insight-stack contents xl:flex xl:min-w-0 xl:flex-col">
          <Card className="tax-stack-section order-1 xl:order-none" style={{ borderRadius: 12 }} title="深圳轻税务配置">
            {initialLoading ? (
              <Skeleton />
            ) : (
              <div className="tax-profile-grid grid grid-cols-1 sm:grid-cols-2">
                {startupProfileRows.map(([label, value]) => (
                  <div
                    key={label}
                    className="min-h-[76px] rounded-2xl border px-4 py-3"
                    style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
                  >
                    <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{label}</div>
                    <div className="mt-2 text-sm font-semibold leading-5" style={{ color: "var(--text-color-1)" }}>{value}</div>
                  </div>
                ))}
              </div>
            )}
          </Card>

          <Card className="tax-stack-section order-3 xl:order-none" style={{ borderRadius: 12 }} title="票据缺口">
            {initialLoading ? (
              <Skeleton />
            ) : receiptComplianceIssues.length === 0 ? (
              <Empty description="暂无票据缺口" />
            ) : (
              <div className="space-y-3">
                {receiptComplianceIssues.map((issue) => {
                  const severity = severityLabels[issue.severity];
                  return (
                    <div key={issue.title} className="flex items-start gap-3 rounded-2xl px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
                      <Tag color={severity.color}>{severity.label}</Tag>
                      <div className="min-w-0 flex-1">
                        <div className="text-sm font-medium">{issue.title}</div>
                        <div className="mt-1 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{issue.description}</div>
                      </div>
                    </div>
                  );
                })}
                <Button size="small" icon={<IconFile />} onClick={() => router.push("/receipts")}>
                  处理票据凭证
                </Button>
              </div>
            )}
          </Card>

          <Card className="tax-stack-section order-4 xl:order-none" style={{ borderRadius: 12 }} title="待处理凭证">
            {initialLoading ? (
              <Skeleton />
            ) : priorityVouchers.length === 0 ? (
              <Empty description="暂无待处理凭证" />
            ) : (
              <div className="space-y-3">
                {priorityVouchers.map((voucher) => {
                  const type = voucherTypeName(voucher.voucherType);
                  return (
                    <div key={voucher.id} className="rounded-2xl border p-3" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}>
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <div className="truncate text-sm font-semibold">{voucher.title}</div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{type} · {voucher.taxPeriod || "税期待补"}</div>
                        </div>
                        <Tag color={voucher.riskLevel === "high" || voucher.riskLevel === "critical" ? "red" : "orange"}>
                          {formatAmount(voucher.amount)}
                        </Tag>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </Card>
        </div>

        <div className="contents xl:flex xl:min-w-0 xl:flex-col xl:gap-4">
          <Card className="order-2 xl:order-none" style={{ borderRadius: 12 }} title="本期待办">
            {initialLoading ? (
              <Skeleton />
            ) : priorityComplianceRisks.length === 0 && priorityTaxItems.length === 0 ? (
              <Empty description="暂无税务待办" />
            ) : (
              <div className="space-y-3">
                {priorityComplianceRisks.length > 0 ? priorityComplianceRisks.map((issue) => {
                  const typeMeta = taxTypeLabels[issue.taxType] || { label: issue.taxTypeName || issue.taxType, color: "gray", icon: <IconFile /> };
                  const severity = severityLabels[normalizeSeverity(issue.severity)];
                  return (
                    <div
                      key={issue.key}
                      className="rounded-2xl border p-3"
                      style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <Tag color={typeMeta.color}>{issue.taxTypeName || typeMeta.label}</Tag>
                            <Tag color={severity.color}>{severity.label}</Tag>
                          </div>
                          <div className="mt-2 line-clamp-1 text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>{issue.title}</div>
                          <div className="mt-1 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
                            {issue.period || "税期待确认"} · {issue.dueDate ? `${displayDate(issue.dueDate)} · ${dueDateLabel(issue.dueDate)}` : "截止日待确认"}
                          </div>
                        </div>
                        <div className="max-w-[180px] text-right text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
                          {issue.action}
                        </div>
                      </div>
                    </div>
                  );
                }) : priorityTaxItems.map((item) => {
                  const typeMeta = taxTypeLabels[item.taxType] || { label: item.taxType, color: "gray", icon: <IconFile /> };
                  const risk = riskLabels[item.riskLevel] || riskLabels.medium;
                  const unpaid = unpaidAmount(item);
                  return (
                    <div
                      key={item.id}
                      className="rounded-2xl border p-3"
                      style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <Tag color={typeMeta.color}>{typeMeta.label}</Tag>
                            <Tag color={risk.color}>{risk.label}</Tag>
                          </div>
                          <div className="mt-2 truncate text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>{item.name}</div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                            {item.period} · {displayDate(item.dueDate)} · {dueLabel(item)}
                          </div>
                        </div>
                        <div className="whitespace-nowrap text-right">
                          <AmountDisplay amount={unpaid} type={unpaid > 0 ? 2 : 1} size="small" />
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{item.responsiblePerson || "负责人待补"}</div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </Card>

        </div>
      </div>

      <Card className="filter-card mb-4" style={{ borderRadius: 12 }}>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-[1.4fr_repeat(3,minmax(0,1fr))_120px]">
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
          <Input allowClear placeholder="税期，例如 2026-06" value={filters.period} onChange={(value) => updateFilter("period", value)} />
          <Button onClick={resetFilters}>重置</Button>
        </div>
      </Card>

      <Card style={{ borderRadius: 12 }} title="税费台账">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[920px] table-fixed border-collapse text-sm">
            <colgroup>
              <col style={{ width: "28%" }} />
              <col style={{ width: "16%" }} />
              <col style={{ width: "18%" }} />
              <col style={{ width: "16%" }} />
              <col style={{ width: "12%" }} />
              <col style={{ width: "10%" }} />
            </colgroup>
            <thead>
              <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                <th className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>税务事项</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>税期与截止</th>
                <th className="px-4 py-3 text-right font-medium" style={{ color: "var(--text-color-2)" }}>应缴/已缴/待缴</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>状态</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>负责人</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {initialLoading ? (
                <tr>
                  <td colSpan={6} className="px-4 py-12"><Skeleton /></td>
                </tr>
              ) : filteredTaxItems.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-12">
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
                          <div className="mt-1 line-clamp-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                            {item.note || "未填写备注"}
                          </div>
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{item.period}</div>
                      <div className="mt-1 text-xs" style={{ color: item.riskLevel === "high" ? "var(--color-danger-light-4)" : "var(--text-color-3)" }}>
                        {displayDate(item.dueDate)} · {dueLabel(item)}
                      </div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{frequencyLabels[item.frequency] || item.frequency}</div>
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
                      <div className="truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>
                        {item.responsiblePerson || "待补充"}
                      </div>
                      <div className="mt-1">
                        <Tag size="small" color={risk.color}>{risk.label}</Tag>
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="flex justify-center gap-1">
                        {!["submitted", "accepted"].includes(item.filingStatus) && (
                          <Button
                            type="text"
                            size="mini"
                            title="标记已申报"
                            loading={action.isRunning(`quick:${item.id}`)}
                            icon={<IconCheckCircle />}
                            onClick={() => handleMarkSubmitted(item)}
                          />
                        )}
                        {unpaid > 0 && (
                          <Button
                            type="text"
                            size="mini"
                            title="标记已缴纳"
                            loading={action.isRunning(`quick:${item.id}`)}
                            icon={<IconSafe />}
                            onClick={() => handleMarkPaid(item)}
                          />
                        )}
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

      <div className="my-4 flex justify-end">
        <Button type="outline" icon={<IconSafe />} onClick={() => setShowAdvanced((value) => !value)}>
          {showAdvanced ? "收起高级口径" : "展开高级口径"}
        </Button>
      </div>

      {showAdvanced && (
        <>
          <div className="mb-6 grid grid-cols-1 gap-4 xl:grid-cols-[1.05fr_0.95fr_1fr]">
            <Card style={{ borderRadius: 12 }} title="增值税与发票底账">
              {initialLoading ? (
                <Skeleton />
              ) : (
                <div>
                  <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                    {[
                      ["销项发票价税合计", formatAmount(vatLedger.salesInvoiceAmount), `销项税额 ${formatAmount(vatLedger.outputTaxAmount)}`],
                      ["进项发票价税合计", formatAmount(vatLedger.purchaseInvoiceAmount), `进项税额 ${formatAmount(vatLedger.inputTaxAmount)}`],
                      ["申报计税销售额", formatAmount(vatLedger.declaredTaxableAmount), `申报税额 ${formatAmount(vatLedger.declaredTaxAmount)}`],
                      ["待缴增值税", formatAmount(vatLedger.unpaidVatAmount), `已缴 ${formatAmount(vatLedger.paidVatAmount)}`],
                    ].map(([label, value, hint]) => (
                      <div
                        key={label}
                        className="min-h-[96px] rounded-lg border px-4 py-3"
                        style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
                      >
                        <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{label}</div>
                        <div className="mt-3 text-lg font-semibold" style={{ color: "var(--text-color-1)" }}>{value}</div>
                        <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{hint}</div>
                      </div>
                    ))}
                  </div>
                  <div className="mt-4 text-xs" style={{ color: "var(--text-color-3)" }}>
                    税负率 {taxBurdenRate(taxItems).toFixed(2)}% · 财年起始月 {summary?.company?.fiscalYearStartMonth || 1} 月
                  </div>
                </div>
              )}
            </Card>

            <Card style={{ borderRadius: 12 }} title="合规检查">
              {initialLoading ? (
                <Skeleton />
              ) : (
                <div>
                  <div
                    className="mb-4 rounded-lg border p-4"
                    style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
                  >
                    <div className="text-sm" style={{ color: "var(--text-color-3)" }}>资料完备度</div>
                    <div className="mt-2 flex items-end justify-between gap-3">
                      <span className="text-3xl font-bold">{filingCompletionRate}%</span>
                      <span className="text-xs" style={{ color: "var(--text-color-3)" }}>
                        {taxCompliance
                          ? `${taxCompliance.metrics.riskCount} 项风险 · ${taxCompliance.metrics.missingPeriodCount} 个税期待建档`
                          : `${complianceIssues.length} 项待检查`}
                      </span>
                    </div>
                  </div>
                  {complianceIssues.length === 0 ? (
                    <Empty description="暂无合规风险" />
                  ) : (
                    <div className="space-y-3">
                      {complianceIssues.map((issue) => {
                        const severity = severityLabels[issue.severity];
                        return (
                          <div key={issue.key} className="flex items-start gap-3">
                            <Tag color={severity.color}>{severity.label}</Tag>
                            <div className="min-w-0 flex-1">
                              <div className="text-sm font-medium">{issue.title}</div>
                              <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{issue.description}</div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}
            </Card>

            <Card style={{ borderRadius: 12 }} title="申报资料清单">
              {initialLoading ? (
                <Skeleton />
              ) : (
                <div className="space-y-3">
                  {Object.entries(taxMaterialChecklist).map(([taxType, materials]) => {
                    const meta = taxTypeLabels[taxType] || { label: taxType, color: "gray", icon: <IconFile /> };
                    const relatedCount = taxItems.filter((item) => item.taxType === taxType).length;
                    return (
                      <div
                        key={taxType}
                        className="rounded-lg border p-3"
                        style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
                      >
                        <div className="mb-2 flex items-center justify-between gap-3">
                          <Tag color={meta.color}>{meta.label}</Tag>
                          <span className="text-xs" style={{ color: "var(--text-color-3)" }}>{relatedCount} 项</span>
                        </div>
                        <div className="flex flex-wrap gap-1.5">
                          {materials.map((material) => (
                            <Tag key={material} color="gray">{material}</Tag>
                          ))}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </Card>
          </div>

          <div className="mb-6 grid grid-cols-1 gap-4 xl:grid-cols-[1fr_1fr]">
            <Card style={{ borderRadius: 12 }} title="申报日历">
              {initialLoading ? (
                <Skeleton />
              ) : calendarItems.length === 0 ? (
                <Empty description="暂无申报日历" />
              ) : (
                <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                  {calendarItems.map((item) => {
                    const typeMeta = taxTypeLabels[item.taxType] || { label: item.taxType, color: "gray", icon: <IconFile /> };
                    const readiness = item.filingStatus === "accepted"
                      ? 100
                      : item.filingStatus === "submitted"
                        ? 80
                        : item.riskLevel === "high"
                          ? 35
                          : item.riskLevel === "medium"
                            ? 60
                            : 70;
                    const days = daysUntil(item.dueDate);
                    return (
                      <div
                        key={item.key}
                        className="rounded-lg border p-3"
                        style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
                      >
                        <div className="flex items-center justify-between gap-3">
                          <div className="min-w-0">
                            <div className="truncate text-sm font-semibold">{item.name}</div>
                            <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                              {item.taxTypeName || typeMeta.label} · {item.period}{item.zeroDeclarationRequired ? " · 含零申报" : ""}
                            </div>
                          </div>
                          <Tag color={days !== null && days < 0 ? "red" : days !== null && days <= 15 ? "orange" : "arcoblue"}>
                            {displayDate(item.dueDate)}
                          </Tag>
                        </div>
                        <div className="mt-3 flex items-center justify-between gap-3 text-xs" style={{ color: "var(--text-color-3)" }}>
                          <span>{dueDateLabel(item.dueDate)}</span>
                          <span>{readiness}%</span>
                        </div>
                        <div className="mt-2 h-2 overflow-hidden rounded-full" style={{ backgroundColor: "var(--color-fill-2)" }}>
                          <div
                            className="h-full rounded-full"
                            style={{
                              width: `${readiness}%`,
                              background: readiness >= 80 ? "var(--gradient-income)" : readiness >= 60 ? "var(--gradient-warning)" : "var(--gradient-expense)",
                            }}
                          />
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </Card>

            <Card style={{ borderRadius: 12 }} title="税种结构">
              {initialLoading ? (
                <Skeleton />
              ) : typeStats.length === 0 ? (
                <Empty description="暂无税种结构" />
              ) : (
                <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                  {typeStats.map((row) => {
                    const meta = taxTypeLabels[row.taxType] || { label: row.taxType, color: "gray", icon: <IconFile /> };
                    return (
                      <div
                        key={row.taxType}
                        className="flex min-h-[96px] flex-col justify-between rounded-lg border px-4 py-3"
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
          </div>

          <Card className="mb-6" style={{ borderRadius: 12 }} title="处理状态">
            {initialLoading ? (
              <Skeleton />
            ) : (
              <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
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
            )}
          </Card>
        </>
      )}

      <Modal
        title={editingItem ? "编辑税务事项" : "新增税务事项"}
        visible={modalVisible}
        onCancel={() => {
          if (saving) return;
          setModalVisible(false);
          setEditingItem(null);
        }}
        onOk={() => form.submit()}
        confirmLoading={saving}
        maskClosable={!saving}
        closable={!saving}
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
