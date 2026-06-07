"use client";

import { useEffect, useMemo, useRef, useState } from "react";
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
  IconCheckCircle,
  IconDownload,
  IconEdit,
  IconExclamationCircle,
  IconFile,
  IconRefresh,
  IconSearch,
  IconUpload,
} from "@arco-design/web-react/icon";
import type { ReactNode } from "react";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { receiptApi } from "@/lib/api/receipts";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount, formatDate } from "@/lib/utils/format";
import type { ReceiptAuditLog, ReceiptPayload, ReceiptQuery, ReceiptSummary, ReceiptVoucher } from "@/lib/types";

const { Row, Col } = Grid;
const FormItem = Form.Item;

const voucherTypeLabels: Record<string, { label: string; color: string }> = {
  sales_invoice: { label: "销项发票", color: "green" },
  purchase_invoice: { label: "进项发票", color: "arcoblue" },
  receipt: { label: "收据", color: "cyan" },
  bank_slip: { label: "银行回单", color: "purple" },
  contract: { label: "合同付款", color: "orangered" },
  reimbursement: { label: "报销凭证", color: "orange" },
  tax_receipt: { label: "税务回执", color: "red" },
};

const statusLabels: Record<string, { label: string; color: string }> = {
  pending_review: { label: "待核验", color: "orange" },
  verified: { label: "已核验", color: "green" },
  linked: { label: "已关联", color: "arcoblue" },
  archived: { label: "已归档", color: "gray" },
  rejected: { label: "已驳回", color: "red" },
};

const riskLabels: Record<string, { label: string; color: string }> = {
  low: { label: "低", color: "green" },
  medium: { label: "中", color: "orange" },
  high: { label: "高", color: "red" },
  critical: { label: "严重", color: "red" },
};

const invoiceCheckLabels: Record<string, { label: string; color: string }> = {
  not_required: { label: "无需查验", color: "gray" },
  pending: { label: "待查验", color: "orange" },
  verified: { label: "已查验", color: "green" },
  failed: { label: "查验异常", color: "red" },
};

const deductionLabels: Record<string, { label: string; color: string }> = {
  not_applicable: { label: "不适用", color: "gray" },
  pending: { label: "待确认", color: "orange" },
  deductible: { label: "可抵扣", color: "arcoblue" },
  deducted: { label: "已抵扣", color: "green" },
  transferred_out: { label: "进项转出", color: "red" },
};

const reimbursementLabels: Record<string, { label: string; color: string }> = {
  not_applicable: { label: "不适用", color: "gray" },
  submitted: { label: "已提交", color: "orange" },
  approved: { label: "已审批", color: "arcoblue" },
  paid: { label: "已付款", color: "green" },
  archived: { label: "已归档", color: "gray" },
  rejected: { label: "已驳回", color: "red" },
};

const approvalLabels: Record<string, { label: string; color: string }> = {
  not_required: { label: "无需审批", color: "gray" },
  pending: { label: "待审批", color: "orange" },
  approved: { label: "已审批", color: "green" },
  rejected: { label: "审批驳回", color: "red" },
};

const accountingLabels: Record<string, { label: string; color: string }> = {
  not_started: { label: "未制证", color: "gray" },
  draft: { label: "凭证草稿", color: "arcoblue" },
  posted: { label: "已过账", color: "green" },
  reversed: { label: "已冲销", color: "red" },
};

const directionLabels: Record<string, { label: string; type: 1 | 2 }> = {
  income: { label: "收入", type: 1 },
  expense: { label: "支出", type: 2 },
};

const today = () => new Date().toISOString().slice(0, 10);

const initialFilters: ReceiptQuery = {
  keyword: "",
  voucherType: "",
  status: "",
  direction: "",
  invoiceCheckStatus: "",
  deductionStatus: "",
  reimbursementStatus: "",
  taxPeriod: "",
  linkState: "",
  startDate: "",
  endDate: "",
  page: 0,
  size: 10,
};

type ReceiptView = {
  summary: ReceiptSummary | null;
  vouchers: ReceiptVoucher[];
  total: number;
  page: number;
  pageSize: number;
};

export default function ReceiptsPage() {
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [filters, setFilters] = useState<ReceiptQuery>(initialFilters);
  const [view, setView] = useState<ReceiptView>({
    summary: null,
    vouchers: [],
    total: 0,
    page: 1,
    pageSize: 10,
  });
  const [initialLoading, setInitialLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingVoucher, setEditingVoucher] = useState<ReceiptVoucher | null>(null);
  const [auditLogs, setAuditLogs] = useState<ReceiptAuditLog[]>([]);
  const [workflowUpdatingId, setWorkflowUpdatingId] = useState<number | null>(null);
  const [attachmentOpeningId, setAttachmentOpeningId] = useState<number | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [form] = Form.useForm();
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const loadData = async (nextFilters = filters, quiet = false) => {
    if (quiet) {
      setRefreshing(true);
    }
    try {
      const [summaryRes, listRes] = await Promise.all([
        receiptApi.summary(),
        receiptApi.list(nextFilters),
      ]);
      setView({
        summary: summaryRes.data,
        vouchers: listRes.data.content,
        total: listRes.data.totalElements,
        page: listRes.data.number + 1,
        pageSize: listRes.data.size,
      });
    } catch {
      Message.error("票据凭证数据加载失败");
    } finally {
      setInitialLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadData({ ...filters, page: 0 });
    }, 0);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeCompanyId]);

  const summaryCards = useMemo<Array<{ label: string; value: string; hint: string; icon: ReactNode }>>(() => {
    const summary = view.summary;
    return [
      {
        label: "票据价税合计",
        value: summary ? formatAmount(summary.totalAmount) : "--",
        hint: `销项 ${formatAmount(summary?.salesInvoiceAmount || 0)}`,
        icon: <IconFile />,
      },
      {
        label: "待核验/待补票",
        value: summary ? formatAmount(summary.pendingAmount) : "--",
        hint: `${summary?.pendingReviewCount || 0} 张待核验 · ${summary?.uncheckedInvoiceCount || 0} 张发票待查验`,
        icon: <IconRefresh />,
      },
      {
        label: "进项可抵扣",
        value: summary ? formatAmount(summary.deductibleTaxAmount) : "--",
        hint: `${summary?.pendingDeductionCount || 0} 张待抵扣确认`,
        icon: <IconCheckCircle />,
      },
      {
        label: "流程待处理",
        value: String((summary?.pendingApprovalCount || 0) + (summary?.pendingAccountingCount || 0)),
        hint: `${summary?.pendingApprovalCount || 0} 张待审批 · ${summary?.pendingAccountingCount || 0} 张待制证`,
        icon: <IconExclamationCircle />,
      },
    ];
  }, [view.summary]);

  const updateFilter = (key: keyof ReceiptQuery, value: string | number | undefined) => {
    setFilters((current) => ({ ...current, [key]: value ?? "", page: 0 }));
  };

  const handleSearch = () => {
    const nextFilters = { ...filters, page: 0 };
    setFilters(nextFilters);
    void loadData(nextFilters, true);
  };

  const handleReset = () => {
    const nextFilters = { ...initialFilters, size: filters.size || 10 };
    setFilters(nextFilters);
    void loadData(nextFilters, true);
  };

  const handlePageChange = (page: number, pageSize: number) => {
    const nextFilters = { ...filters, page: page - 1, size: pageSize };
    setFilters(nextFilters);
    void loadData(nextFilters, true);
  };

  const openCreate = () => {
    setEditingVoucher(null);
    setAuditLogs([]);
    setSelectedFile(null);
    form.resetFields();
    form.setFieldsValue({
      voucherType: "purchase_invoice",
      direction: "expense",
      status: "pending_review",
      issueDate: today(),
      amount: 0,
      taxAmount: 0,
      taxRate: 0,
      taxPeriod: today().slice(0, 7),
      invoiceCheckStatus: "pending",
      deductionStatus: "pending",
      reimbursementStatus: "not_applicable",
      approvalStatus: "not_required",
      accountingStatus: "not_started",
      accountingVoucherNo: "",
      accountingEntry: "",
      businessPurpose: "",
      expenseOwner: "",
    });
    setModalVisible(true);
  };

  const openEdit = async (voucher: ReceiptVoucher) => {
    setEditingVoucher(voucher);
    setSelectedFile(null);
    setAuditLogs([]);
    form.setFieldsValue({
      ...voucher,
      transactionId: voucher.transactionId || undefined,
      dueDate: voucher.dueDate || undefined,
      note: voucher.note || undefined,
    });
    setModalVisible(true);
    try {
      const res = await receiptApi.auditLogs(voucher.id);
      setAuditLogs(res.data);
    } catch {
      setAuditLogs([]);
    }
  };

  const handleSubmit = async (values: ReceiptPayload) => {
    try {
      const payload: ReceiptPayload = {
        ...values,
        amount: Number(values.amount || 0),
        taxAmount: Number(values.taxAmount || 0),
        transactionId: values.transactionId ? Number(values.transactionId) : null,
      };
      if (editingVoucher) {
        await receiptApi.update(editingVoucher.id, {
          ...payload,
          fileName: selectedFile?.name || payload.fileName,
          fileSize: selectedFile?.size || payload.fileSize,
          fileType: selectedFile?.type || payload.fileType,
        });
        Message.success("凭证已更新");
      } else if (selectedFile) {
        await receiptApi.upload(selectedFile, payload);
        Message.success("凭证已上传");
      } else {
        await receiptApi.create(payload);
        Message.success("凭证已创建");
      }
      setModalVisible(false);
      form.resetFields();
      setSelectedFile(null);
      await loadData(filters, true);
    } catch {
      Message.error("凭证保存失败");
    }
  };

  const updateStatus = async (voucher: ReceiptVoucher, status: string) => {
    try {
      setWorkflowUpdatingId(voucher.id);
      await receiptApi.update(voucher.id, {
        status,
        invoiceCheckStatus: ["sales_invoice", "purchase_invoice"].includes(voucher.voucherType) ? "verified" : voucher.invoiceCheckStatus,
        deductionStatus: voucher.voucherType === "purchase_invoice" && voucher.deductionStatus === "pending" ? "deductible" : voucher.deductionStatus,
      });
      Message.success("状态已更新");
      await loadData(filters, true);
    } catch {
      Message.error("状态更新失败");
    } finally {
      setWorkflowUpdatingId(null);
    }
  };

  const updateWorkflow = async (voucher: ReceiptVoucher, payload: Partial<ReceiptPayload>, successMessage: string) => {
    try {
      setWorkflowUpdatingId(voucher.id);
      await receiptApi.update(voucher.id, payload);
      Message.success(successMessage);
      await loadData(filters, true);
    } catch {
      Message.error("流程状态更新失败");
    } finally {
      setWorkflowUpdatingId(null);
    }
  };

  const openAttachment = async (voucher: ReceiptVoucher) => {
    if (voucher.fileStorageProvider !== "minio") {
      Message.info(voucher.fileName ? "当前附件只完成元数据归档，未写入对象存储" : "当前凭证没有可打开的附件");
      return;
    }
    try {
      setAttachmentOpeningId(voucher.id);
      const res = await receiptApi.downloadFile(voucher.id);
      const blob = res.data instanceof Blob ? res.data : new Blob([res.data], { type: voucher.fileType || "application/octet-stream" });
      const url = URL.createObjectURL(blob);
      window.open(url, "_blank", "noopener,noreferrer");
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch {
      Message.error("附件下载失败");
    } finally {
      setAttachmentOpeningId(null);
    }
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title="票据与报销中心"
        subtitle="发票、报销单、银行回单、合同付款与税务回执统一归档"
        icon={<IconFile />}
        extra={
          <div className="flex items-center gap-2">
            {refreshing && <Tag color="arcoblue">刷新中</Tag>}
            <Button icon={<IconRefresh />} onClick={() => loadData(filters, true)}>
              刷新
            </Button>
            <Button type="primary" icon={<IconUpload />} onClick={openCreate}>
              新增凭证
            </Button>
          </div>
        }
      />

      <Row gutter={16} className="mb-6">
        {summaryCards.map((card) => (
          <Col key={card.label} xs={12} md={6}>
            <Card style={{ borderRadius: 12, minHeight: 148 }}>
              <div className="flex h-full min-h-[108px] flex-col justify-between">
                <div className="flex items-center justify-between">
                  <span className="text-sm" style={{ color: "var(--text-color-3)" }}>{card.label}</span>
                  <span className="inline-flex text-xl" style={{ color: "var(--color-primary)" }}>{card.icon}</span>
                </div>
                {initialLoading ? (
                  <Skeleton />
                ) : (
                  <>
                    <div className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{card.value}</div>
                    <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{card.hint}</div>
                  </>
                )}
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <div className="mb-6 grid grid-cols-1 gap-4 lg:grid-cols-[1.15fr_0.85fr]">
        <Card style={{ borderRadius: 12 }} title="发票与税务证据链">
          {initialLoading ? (
            <Skeleton />
          ) : (
            <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
              {[
                ["销项发票", formatAmount(view.summary?.salesInvoiceAmount || 0), `销项税额 ${formatAmount(view.summary?.outputTaxAmount || 0)}`],
                ["进项发票", formatAmount(view.summary?.purchaseInvoiceAmount || 0), `可抵扣 ${formatAmount(view.summary?.deductibleTaxAmount || 0)}`],
                ["流水关联缺口", `${view.summary?.missingTransactionCount || 0} 张`, "发票、报销和银行回单需关联流水"],
                ["税期缺口", `${view.summary?.missingTaxPeriodCount || 0} 张`, "影响增值税和申报归档"],
              ].map(([label, value, hint]) => (
                <div
                  key={label}
                  className="min-h-[104px] rounded-lg border px-4 py-3"
                  style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
                >
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{label}</div>
                  <div className="mt-3 text-lg font-semibold" style={{ color: "var(--text-color-1)" }}>{value}</div>
                  <div className="mt-2 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{hint}</div>
                </div>
              ))}
            </div>
          )}
        </Card>
        <Card style={{ borderRadius: 12 }} title="报销流程">
          {initialLoading ? (
            <Skeleton />
          ) : (
            <div className="space-y-3">
              <div className="flex items-center justify-between rounded-lg px-4 py-3" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <span style={{ color: "var(--text-color-2)" }}>报销总额</span>
                <strong>{formatAmount(view.summary?.reimbursementAmount || 0)}</strong>
              </div>
              <div className="flex items-center justify-between rounded-lg px-4 py-3" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <span style={{ color: "var(--text-color-2)" }}>待审批/待付款</span>
                <strong style={{ color: "var(--color-warning)" }}>{formatAmount(view.summary?.reimbursementPendingAmount || 0)}</strong>
              </div>
              <div className="flex items-center justify-between rounded-lg px-4 py-3" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <span style={{ color: "var(--text-color-2)" }}>待制证/过账</span>
                <strong>{view.summary?.pendingAccountingCount || 0} 张</strong>
              </div>
              <div className="flex items-center justify-between rounded-lg px-4 py-3" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <span style={{ color: "var(--text-color-2)" }}>附件缺口</span>
                <strong>{view.summary?.missingAttachmentCount || 0} 张</strong>
              </div>
            </div>
          )}
        </Card>
      </div>

      <Card className="mb-6" style={{ borderRadius: 12 }}>
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-4">
          <Input
            allowClear
            prefix={<IconSearch />}
            placeholder="搜索凭证号、标题、对方..."
            value={filters.keyword}
            onChange={(value) => updateFilter("keyword", value)}
          />
          <Select
            allowClear
            placeholder="全部类型"
            value={filters.voucherType || undefined}
            onChange={(value) => updateFilter("voucherType", value)}
          >
            {Object.entries(voucherTypeLabels).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select
            allowClear
            placeholder="全部状态"
            value={filters.status || undefined}
            onChange={(value) => updateFilter("status", value)}
          >
            {Object.entries(statusLabels).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select
            allowClear
            placeholder="收支方向"
            value={filters.direction || undefined}
            onChange={(value) => updateFilter("direction", value)}
          >
            <Select.Option value="income">收入</Select.Option>
            <Select.Option value="expense">支出</Select.Option>
          </Select>
        </div>
        <div className="mt-3 grid grid-cols-1 gap-3 xl:grid-cols-[minmax(430px,1.45fr)_minmax(340px,1fr)_minmax(180px,0.55fr)]">
          <div className="grid grid-cols-[minmax(0,1fr)_24px_minmax(0,1fr)] items-center gap-2">
            <Input
              placeholder="起始日期 yyyy-MM-dd"
              value={filters.startDate}
              onChange={(value) => updateFilter("startDate", value)}
            />
            <span className="text-center text-base font-medium" style={{ color: "var(--text-color-3)" }}>-</span>
            <Input
              placeholder="结束日期 yyyy-MM-dd"
              value={filters.endDate}
              onChange={(value) => updateFilter("endDate", value)}
            />
          </div>
          <div className="grid grid-cols-[minmax(0,1fr)_24px_minmax(0,1fr)] items-center gap-2">
            <InputNumber
              min={0}
              placeholder="最小金额"
              value={filters.minAmount}
              onChange={(value) => updateFilter("minAmount", value)}
            />
            <span className="text-center text-base font-medium" style={{ color: "var(--text-color-3)" }}>-</span>
            <InputNumber
              min={0}
              placeholder="最大金额"
              value={filters.maxAmount}
              onChange={(value) => updateFilter("maxAmount", value)}
            />
          </div>
          <Select
            allowClear
            placeholder="关联状态"
            value={filters.linkState || undefined}
            onChange={(value) => updateFilter("linkState", value)}
          >
            <Select.Option value="linked">已关联流水</Select.Option>
            <Select.Option value="missing">未关联流水</Select.Option>
          </Select>
        </div>
        <div className="mt-3 grid grid-cols-1 gap-3 xl:grid-cols-[repeat(4,minmax(0,1fr))_180px]">
          <Input
            allowClear
            placeholder="税期，例如 2026-06"
            value={filters.taxPeriod}
            onChange={(value) => updateFilter("taxPeriod", value)}
          />
          <Select
            allowClear
            placeholder="发票查验"
            value={filters.invoiceCheckStatus || undefined}
            onChange={(value) => updateFilter("invoiceCheckStatus", value)}
          >
            {Object.entries(invoiceCheckLabels).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select
            allowClear
            placeholder="进项抵扣"
            value={filters.deductionStatus || undefined}
            onChange={(value) => updateFilter("deductionStatus", value)}
          >
            {Object.entries(deductionLabels).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select
            allowClear
            placeholder="报销状态"
            value={filters.reimbursementStatus || undefined}
            onChange={(value) => updateFilter("reimbursementStatus", value)}
          >
            {Object.entries(reimbursementLabels).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <div className="grid grid-cols-2 gap-3">
            <Button type="primary" icon={<IconSearch />} onClick={handleSearch}>搜索</Button>
            <Button onClick={handleReset}>重置</Button>
          </div>
        </div>
      </Card>

      <Card style={{ borderRadius: 12 }} title="税务凭证台账">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1360px] table-fixed border-collapse text-sm">
            <colgroup>
              <col style={{ width: "23%" }} />
              <col style={{ width: "10%" }} />
              <col style={{ width: "14%" }} />
              <col style={{ width: "13%" }} />
              <col style={{ width: "15%" }} />
              <col style={{ width: "11%" }} />
              <col style={{ width: "9%" }} />
              <col style={{ width: "5%" }} />
            </colgroup>
            <thead>
              <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                <th className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>凭证</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>类型</th>
                <th className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>对方/用途</th>
                <th className="px-4 py-3 text-right font-medium" style={{ color: "var(--text-color-2)" }}>金额</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>税务口径</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>日期/税期</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>状态/风险</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {initialLoading ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12"><Skeleton /></td>
                </tr>
              ) : view.vouchers.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12">
                    <Empty description="暂无票据凭证" />
                  </td>
                </tr>
              ) : view.vouchers.map((voucher) => {
                const type = voucherTypeLabels[voucher.voucherType] || { label: voucher.voucherType, color: "gray" };
                const status = statusLabels[voucher.status] || { label: voucher.status, color: "gray" };
                const risk = riskLabels[voucher.riskLevel] || { label: voucher.riskLevel, color: "gray" };
                const direction = directionLabels[voucher.direction] || directionLabels.expense;
                const invoiceCheck = invoiceCheckLabels[voucher.invoiceCheckStatus] || invoiceCheckLabels.not_required;
                const deduction = deductionLabels[voucher.deductionStatus] || deductionLabels.not_applicable;
                const reimbursement = reimbursementLabels[voucher.reimbursementStatus] || reimbursementLabels.not_applicable;
                const approval = approvalLabels[voucher.approvalStatus] || approvalLabels.not_required;
                const accounting = accountingLabels[voucher.accountingStatus] || accountingLabels.not_started;
                return (
                  <tr
                    key={voucher.id}
                    className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                    style={{ borderColor: "var(--border-color-light)" }}
                  >
                    <td className="px-4 py-4 align-middle">
                      <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{voucher.title}</div>
                      <div className="mt-1 flex items-center gap-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                        <span>{voucher.voucherNo}</span>
                        {voucher.transactionId ? (
                          <Tag size="small" color="arcoblue">流水 #{voucher.transactionId}</Tag>
                        ) : (
                          <Tag size="small" color="orange">未关联流水</Tag>
                        )}
                      </div>
                      {voucher.note && (
                        <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>{voucher.note}</div>
                      )}
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <Tag color={type.color}>{type.label}</Tag>
                      <div className="mt-2">
                        <Tag size="small" color={direction.type === 1 ? "green" : "red"}>{direction.label}</Tag>
                      </div>
                    </td>
                    <td className="px-4 py-4 align-middle">
                      <div className="truncate" style={{ color: "var(--text-color-2)" }}>{voucher.counterparty}</div>
                      <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                        {voucher.businessPurpose || "用途待补充"}
                      </div>
                      <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                        经办 {voucher.expenseOwner || "--"}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-right align-middle whitespace-nowrap">
                      <AmountDisplay amount={voucher.amount} type={direction.type} size="small" />
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        税额 {formatAmount(voucher.taxAmount || 0)}
                      </div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        税率 {Number(voucher.taxRate || 0).toFixed(2)}%
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="flex flex-wrap justify-center gap-1.5">
                        <Tag size="small" color={invoiceCheck.color}>{invoiceCheck.label}</Tag>
                        <Tag size="small" color={deduction.color}>{deduction.label}</Tag>
                        <Tag size="small" color={reimbursement.color}>{reimbursement.label}</Tag>
                        <Tag size="small" color={approval.color}>{approval.label}</Tag>
                        <Tag size="small" color={accounting.color}>{accounting.label}</Tag>
                      </div>
                      <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                        {voucher.accountingVoucherNo
                          || (voucher.fileStorageProvider === "minio" ? "MinIO 已归档" : voucher.fileName ? "元数据归档" : "附件缺失")}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle whitespace-nowrap">
                      <div>{formatDate(voucher.issueDate)}</div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        税期 {voucher.taxPeriod || "--"}
                      </div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        {voucher.dueDate ? `截止 ${formatDate(voucher.dueDate)}` : "无截止日"}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <Tag color={status.color}>{status.label}</Tag>
                      <div className="mt-2">
                        <Tag size="small" color={risk.color}>风险 {risk.label}</Tag>
                      </div>
                      <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                        {voucher.fileStorageProvider === "minio" ? voucher.fileObjectKey : voucher.fileName || "缺附件"}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="flex justify-center gap-1">
                        {voucher.status === "pending_review" && (
                          <Button
                            type="text"
                            size="mini"
                            title="核验"
                            loading={workflowUpdatingId === voucher.id}
                            icon={<IconCheckCircle />}
                            onClick={() => updateStatus(voucher, voucher.transactionId ? "linked" : "verified")}
                          />
                        )}
                        {voucher.approvalStatus === "pending" && (
                          <Button
                            type="text"
                            size="mini"
                            title="审批通过"
                            loading={workflowUpdatingId === voucher.id}
                            icon={<IconCheckCircle />}
                            onClick={() => updateWorkflow(voucher, { approvalStatus: "approved" }, "审批已通过")}
                          />
                        )}
                        {voucher.accountingStatus !== "posted" && voucher.status !== "pending_review" && (
                          <Button
                            type="text"
                            size="mini"
                            title="会计过账"
                            loading={workflowUpdatingId === voucher.id}
                            icon={<IconFile />}
                            onClick={() => updateWorkflow(voucher, { accountingStatus: "posted" }, "会计凭证已过账")}
                          />
                        )}
                        <Button
                          type="text"
                          size="mini"
                          title="打开附件"
                          disabled={!voucher.fileName}
                          loading={attachmentOpeningId === voucher.id}
                          icon={<IconDownload />}
                          onClick={() => openAttachment(voucher)}
                        />
                        <Button
                          type="text"
                          size="mini"
                          title="编辑"
                          icon={<IconEdit />}
                          onClick={() => openEdit(voucher)}
                        />
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <AppPagination
          current={view.page}
          pageSize={view.pageSize}
          total={view.total}
          pageSizeOptions={[10, 20, 50, 100]}
          onChange={handlePageChange}
        />
      </Card>

      <Modal
        title={editingVoucher ? "编辑凭证" : "新增凭证"}
        visible={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={() => form.submit()}
        style={{ width: 720 }}
      >
        <Form form={form} layout="vertical" onSubmit={handleSubmit}>
          <div className="grid grid-cols-1 gap-x-4 md:grid-cols-2">
            <FormItem label="凭证标题" field="title" rules={[{ required: true, message: "请输入凭证标题" }]}>
              <Input placeholder="例如：办公采购进项发票" />
            </FormItem>
            <FormItem label="凭证编号" field="voucherNo">
              <Input placeholder="系统可自动生成" />
            </FormItem>
            <FormItem label="凭证类型" field="voucherType" rules={[{ required: true, message: "请选择凭证类型" }]}>
              <Select>
                {Object.entries(voucherTypeLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="收支方向" field="direction" rules={[{ required: true, message: "请选择收支方向" }]}>
              <Select>
                <Select.Option value="income">收入</Select.Option>
                <Select.Option value="expense">支出</Select.Option>
              </Select>
            </FormItem>
            <FormItem label="对方主体" field="counterparty" rules={[{ required: true, message: "请输入对方主体" }]}>
              <Input placeholder="客户、供应商、员工、税务机关" />
            </FormItem>
            <FormItem label="凭证状态" field="status">
              <Select>
                {Object.entries(statusLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="金额" field="amount" rules={[{ required: true, message: "请输入金额" }]}>
              <InputNumber min={0} precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="税额" field="taxAmount">
              <InputNumber min={0} precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="税率 %" field="taxRate">
              <InputNumber min={0} precision={2} placeholder="例如：3.00" />
            </FormItem>
            <FormItem label="税期" field="taxPeriod">
              <Input placeholder="例如：2026-06" />
            </FormItem>
            <FormItem label="发票查验" field="invoiceCheckStatus">
              <Select>
                {Object.entries(invoiceCheckLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="进项抵扣" field="deductionStatus">
              <Select>
                {Object.entries(deductionLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="报销状态" field="reimbursementStatus">
              <Select>
                {Object.entries(reimbursementLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="审批状态" field="approvalStatus">
              <Select>
                {Object.entries(approvalLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="会计状态" field="accountingStatus">
              <Select>
                {Object.entries(accountingLabels).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="会计凭证号" field="accountingVoucherNo">
              <Input placeholder="系统可生成，例如 JV-202606-0001" />
            </FormItem>
            <FormItem label="业务用途" field="businessPurpose">
              <Input placeholder="例如：办公采购、差旅、客户项目交付" />
            </FormItem>
            <FormItem label="经办人/报销人" field="expenseOwner">
              <Input placeholder="员工、部门或负责人" />
            </FormItem>
            <FormItem label="开具日期" field="issueDate" rules={[{ required: true, message: "请输入开具日期" }]}>
              <Input placeholder="yyyy-MM-dd" />
            </FormItem>
            <FormItem label="截止日期" field="dueDate">
              <Input placeholder="yyyy-MM-dd" />
            </FormItem>
            <FormItem label="关联流水 ID" field="transactionId">
              <InputNumber min={1} precision={0} placeholder="可选" />
            </FormItem>
            <FormItem label="附件">
              <input
                ref={fileInputRef}
                type="file"
                className="hidden"
                accept="image/*,.pdf"
                onChange={(event) => setSelectedFile(event.target.files?.[0] || null)}
              />
              <Button icon={<IconUpload />} onClick={() => fileInputRef.current?.click()}>
                选择文件
              </Button>
              <span className="ml-3 text-xs" style={{ color: "var(--text-color-3)" }}>
                {selectedFile?.name || editingVoucher?.fileName || "未选择"}
              </span>
              {editingVoucher && (
                <Button
                  className="ml-2"
                  type="text"
                  size="small"
                  icon={<IconDownload />}
                  loading={attachmentOpeningId === editingVoucher.id}
                  disabled={!editingVoucher.fileName}
                  onClick={() => openAttachment(editingVoucher)}
                >
                  打开附件
                </Button>
              )}
            </FormItem>
          </div>
          <FormItem label="备注" field="note">
            <Input.TextArea rows={3} placeholder="补充核验、归档或税务说明" />
          </FormItem>
          <FormItem label="会计分录草稿" field="accountingEntry">
            <Input.TextArea rows={3} placeholder="例如：借：管理费用；贷：银行存款" />
          </FormItem>
          {editingVoucher && (
            <div className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
              <div className="mb-2 text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>审计留痕</div>
              {auditLogs.length === 0 ? (
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>暂无操作记录</div>
              ) : (
                <div className="space-y-2">
                  {auditLogs.slice(0, 6).map((log) => (
                    <div key={log.id} className="flex items-start justify-between gap-3 text-xs">
                      <div className="min-w-0">
                        <div className="truncate font-medium" style={{ color: "var(--text-color-2)" }}>{log.summary}</div>
                        <div className="mt-1" style={{ color: "var(--text-color-3)" }}>{log.actorName} · {log.action}</div>
                      </div>
                      <span className="shrink-0" style={{ color: "var(--text-color-3)" }}>{formatDate(log.createdAt)}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </Form>
      </Modal>
    </div>
  );
}
