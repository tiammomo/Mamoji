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
import type { ReceiptPayload, ReceiptQuery, ReceiptSummary, ReceiptVoucher } from "@/lib/types";

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
        label: "凭证总额",
        value: summary ? formatAmount(summary.totalAmount) : "--",
        hint: `${summary?.totalCount || 0} 张凭证`,
        icon: <IconFile />,
      },
      {
        label: "待核验金额",
        value: summary ? formatAmount(summary.pendingAmount) : "--",
        hint: `${summary?.pendingReviewCount || 0} 张待核验`,
        icon: <IconRefresh />,
      },
      {
        label: "可抵扣税额",
        value: summary ? formatAmount(summary.deductibleTaxAmount) : "--",
        hint: "来自进项发票",
        icon: <IconCheckCircle />,
      },
      {
        label: "风险缺口",
        value: String((summary?.missingAttachmentCount || 0) + (summary?.missingTransactionCount || 0)),
        hint: `${summary?.highRiskCount || 0} 张高风险`,
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
    setSelectedFile(null);
    form.resetFields();
    form.setFieldsValue({
      voucherType: "purchase_invoice",
      direction: "expense",
      status: "pending_review",
      issueDate: today(),
      amount: 0,
      taxAmount: 0,
    });
    setModalVisible(true);
  };

  const openEdit = (voucher: ReceiptVoucher) => {
    setEditingVoucher(voucher);
    setSelectedFile(null);
    form.setFieldsValue({
      ...voucher,
      transactionId: voucher.transactionId || undefined,
      dueDate: voucher.dueDate || undefined,
      note: voucher.note || undefined,
    });
    setModalVisible(true);
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
      await receiptApi.update(voucher.id, { status });
      Message.success("状态已更新");
      await loadData(filters, true);
    } catch {
      Message.error("状态更新失败");
    }
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title="票据凭证"
        subtitle="发票、收据、银行回单、合同付款与税务回执"
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
        <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-6">
          <Input
            placeholder="起始日期 yyyy-MM-dd"
            value={filters.startDate}
            onChange={(value) => updateFilter("startDate", value)}
          />
          <Input
            placeholder="结束日期 yyyy-MM-dd"
            value={filters.endDate}
            onChange={(value) => updateFilter("endDate", value)}
          />
          <InputNumber
            min={0}
            placeholder="最小金额"
            value={filters.minAmount}
            onChange={(value) => updateFilter("minAmount", value)}
          />
          <InputNumber
            min={0}
            placeholder="最大金额"
            value={filters.maxAmount}
            onChange={(value) => updateFilter("maxAmount", value)}
          />
          <Select
            allowClear
            placeholder="关联状态"
            value={filters.linkState || undefined}
            onChange={(value) => updateFilter("linkState", value)}
          >
            <Select.Option value="linked">已关联流水</Select.Option>
            <Select.Option value="missing">未关联流水</Select.Option>
          </Select>
          <div className="grid grid-cols-2 gap-3">
            <Button type="primary" icon={<IconSearch />} onClick={handleSearch}>搜索</Button>
            <Button onClick={handleReset}>重置</Button>
          </div>
        </div>
      </Card>

      <Card style={{ borderRadius: 12 }} title="凭证台账">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1180px] table-fixed border-collapse text-sm">
            <colgroup>
              <col style={{ width: "25%" }} />
              <col style={{ width: "12%" }} />
              <col style={{ width: "14%" }} />
              <col style={{ width: "13%" }} />
              <col style={{ width: "12%" }} />
              <col style={{ width: "11%" }} />
              <col style={{ width: "7%" }} />
              <col style={{ width: "6%" }} />
            </colgroup>
            <thead>
              <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                <th className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>凭证</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>类型</th>
                <th className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>对方</th>
                <th className="px-4 py-3 text-right font-medium" style={{ color: "var(--text-color-2)" }}>金额</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>日期</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>状态</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>附件</th>
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
                    </td>
                    <td className="px-4 py-4 text-right align-middle whitespace-nowrap">
                      <AmountDisplay amount={voucher.amount} type={direction.type} size="small" />
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        税额 {formatAmount(voucher.taxAmount || 0)}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle whitespace-nowrap">
                      <div>{formatDate(voucher.issueDate)}</div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        {voucher.dueDate ? `截止 ${formatDate(voucher.dueDate)}` : "无截止日"}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <Tag color={status.color}>{status.label}</Tag>
                      <div className="mt-2">
                        <Tag size="small" color={risk.color}>风险 {risk.label}</Tag>
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      {voucher.fileName ? (
                        <Tag color="green">已上传</Tag>
                      ) : (
                        <Tag color="orange">缺附件</Tag>
                      )}
                      <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                        {voucher.fileName || "--"}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="flex justify-center gap-1">
                        {voucher.status === "pending_review" && (
                          <Button
                            type="text"
                            size="mini"
                            title="核验"
                            icon={<IconCheckCircle />}
                            onClick={() => updateStatus(voucher, voucher.transactionId ? "linked" : "verified")}
                          />
                        )}
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
            </FormItem>
          </div>
          <FormItem label="备注" field="note">
            <Input.TextArea rows={3} placeholder="补充核验、归档或税务说明" />
          </FormItem>
        </Form>
      </Modal>
    </div>
  );
}
