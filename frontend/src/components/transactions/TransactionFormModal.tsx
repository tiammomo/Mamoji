"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Button, DatePicker, Form, Grid, Input, Message, Modal, Select, Tag } from "@arco-design/web-react";
import { IconExclamationCircle, IconRefresh } from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import { transactionApi } from "@/lib/api/transactions";
import { accountApi } from "@/lib/api/accounts";
import { useAppStore } from "@/lib/stores/appStore";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import { formatAmount } from "@/lib/utils/format";
import type { Account, CreateTransactionDTO, Transaction, UpdateTransactionDTO } from "@/lib/types";

const FormItem = Form.Item;
const { Row, Col } = Grid;

export type TransactionFormMode = "create" | "edit" | "refund";

interface TransactionFormModalProps {
  visible: boolean;
  mode: TransactionFormMode;
  transactionId?: number | null;
  onClose: () => void;
  onSuccess: () => void;
}

type TransactionFormValues = {
  amount: number;
  categoryId?: number;
  accountId?: number;
  date: string;
  note?: string;
};

const localToday = () => {
  const date = new Date();
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

const errorMessage = (error: unknown, fallback: string) => {
  if (!error || typeof error !== "object") return fallback;
  const response = "response" in error ? (error as { response?: { data?: unknown } }).response : undefined;
  const data = response?.data;
  if (data && typeof data === "object") {
    const problem = data as Record<string, unknown>;
    for (const key of ["message", "detail", "error"]) {
      if (typeof problem[key] === "string" && problem[key]) return problem[key] as string;
    }
  }
  return fallback;
};

export default function TransactionFormModal({
  visible,
  mode,
  transactionId,
  onClose,
  onSuccess,
}: TransactionFormModalProps) {
  const router = useRouter();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const { fetchCategories, getByType, loaded: categoriesLoaded } = useCategoryStore();
  const [form] = Form.useForm<TransactionFormValues>();
  const [type, setType] = useState<1 | 2>(2);
  const [submitting, setSubmitting] = useState(false);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailReloadToken, setDetailReloadToken] = useState(0);
  const [sourceTransaction, setSourceTransaction] = useState<Transaction | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loadingAccounts, setLoadingAccounts] = useState(false);
  const [accountError, setAccountError] = useState<string | null>(null);
  const [loadingCategories, setLoadingCategories] = useState(false);
  const [categoryError, setCategoryError] = useState<string | null>(null);
  const [resourceReloadToken, setResourceReloadToken] = useState(0);
  const [selectedCategory, setSelectedCategory] = useState<number | null>(null);

  const isCreate = mode === "create";
  const isEdit = mode === "edit";
  const isRefund = mode === "refund";

  useEffect(() => {
    if (!visible || isRefund) return;
    let cancelled = false;

    const loadResources = async () => {
      setAccounts([]);
      setAccountError(null);
      setCategoryError(null);
      setLoadingAccounts(true);
      setLoadingCategories(true);

      const [accountResult] = await Promise.allSettled([
        accountApi.list(),
        fetchCategories(),
      ]);
      if (cancelled) return;

      if (accountResult.status === "fulfilled") {
        setAccounts(accountResult.value.data);
      } else {
        setAccountError("资金账户加载失败，请重试或前往账户管理检查。");
      }
      if (!useCategoryStore.getState().loaded) {
        setCategoryError("交易分类加载失败，请稍后重试。");
      }
      setLoadingAccounts(false);
      setLoadingCategories(false);
    };

    void loadResources();
    return () => {
      cancelled = true;
    };
  }, [activeCompanyId, fetchCategories, isRefund, resourceReloadToken, visible]);

  useEffect(() => {
    if (!visible) return;
    let cancelled = false;

    const timer = window.setTimeout(() => {
      const prepareForm = async () => {
        setSelectedCategory(null);
        setType(2);
        setSourceTransaction(null);
        setDetailError(null);
        setLoadingDetail(false);
        form.resetFields();
        form.setFieldsValue({ date: localToday() });

        if (isCreate) return;
        if (!transactionId) {
          setDetailError("缺少有效的流水编号，无法继续操作。");
          return;
        }

        setLoadingDetail(true);
        try {
          const response = await transactionApi.get(transactionId);
          if (cancelled) return;
          const transaction = response.data;
          const nextType = transaction.type === 1 ? 1 : 2;
          const remainingRefundable = Math.max(0, Number(transaction.amount) - Number(transaction.refundedAmount || 0));

          if (isRefund && (transaction.type !== 2 || !transaction.isRefundable || remainingRefundable <= 0)) {
            setDetailError("这笔流水当前没有可退金额，无法发起退款。");
            setSourceTransaction(transaction);
            return;
          }

          setSourceTransaction(transaction);
          setType(nextType);
          setSelectedCategory(transaction.categoryId);
          form.setFieldsValue({
            amount: isRefund ? undefined : transaction.amount,
            categoryId: transaction.categoryId,
            accountId: transaction.accountId,
            date: isRefund ? localToday() : transaction.date,
            note: isRefund
              ? `退款：${transaction.note || transaction.categoryName || `流水 #${transaction.id}`}`
              : transaction.note,
          });
        } catch (error) {
          if (!cancelled) setDetailError(errorMessage(error, "流水详情加载失败，请重试。"));
        } finally {
          if (!cancelled) setLoadingDetail(false);
        }
      };

      void prepareForm();
    }, 0);

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [detailReloadToken, form, isCreate, isRefund, transactionId, visible]);

  const filteredCategories = getByType(type === 1 ? "income" : "expense");
  const remainingRefundable = useMemo(() => {
    if (!sourceTransaction) return null;
    return Math.max(0, Number(sourceTransaction.amount) - Number(sourceTransaction.refundedAmount || 0));
  }, [sourceTransaction]);

  const title = isRefund ? "发起退款" : isEdit ? "编辑流水" : "录入流水";
  const quickNotes = isRefund
    ? ["供应商退款：原成本支出返还", "订阅退款：未使用服务退回", "采购退款：退货返款"]
    : type === 1
      ? ["项目交付待回款：预计 YYYY-MM-DD 到账", "分期回款：第 1/3 期", "尾款待回款：验收后支付"]
      : ["客户退款：冲减收入", "离职补偿：N+1 经济补偿", "裁员补偿：解除劳动关系补偿"];

  const missingAccounts = !isRefund && !loadingAccounts && accounts.length === 0;
  const missingCategories = !isRefund && !loadingCategories && categoriesLoaded && filteredCategories.length === 0;
  const resourcesLoading = !isRefund && (loadingAccounts || loadingCategories);
  const submitBlocked = Boolean(
    loadingDetail
    || detailError
    || resourcesLoading
    || missingAccounts
    || missingCategories
    || (!isRefund && (accountError || categoryError))
    || (isRefund && (!sourceTransaction || !remainingRefundable))
  );

  const requestClose = () => {
    if (!submitting) onClose();
  };

  const changeType = (nextType: 1 | 2) => {
    if (!isCreate) return;
    setType(nextType);
    setSelectedCategory(null);
    form.setFieldValue("categoryId", undefined);
  };

  const handleSubmit = async (values: TransactionFormValues) => {
    if (submitBlocked || submitting) return;
    setSubmitting(true);
    try {
      if (isRefund && transactionId) {
        const response = await transactionApi.refund(transactionId, {
          amount: Number(values.amount),
          date: values.date,
          note: values.note?.trim() || undefined,
        });
        Message.success("退款流水已创建");
        if (response.data.risk && response.data.risk.level !== "low") {
          Message.warning(`记录已保存；风险提示：${response.data.risk.message}`);
        }
      } else {
        const basePayload: UpdateTransactionDTO = {
          amount: Number(values.amount),
          accountId: Number(values.accountId),
          categoryId: Number(selectedCategory ?? values.categoryId),
          date: values.date,
          note: isEdit ? (values.note?.trim() ?? "") : (values.note?.trim() || undefined),
        };

        if (isEdit && transactionId) {
          await transactionApi.update(transactionId, basePayload);
          Message.success("流水已更新");
        } else {
          const createPayload: CreateTransactionDTO = {
            ...basePayload,
            amount: Number(basePayload.amount),
            accountId: Number(basePayload.accountId),
            categoryId: Number(basePayload.categoryId),
            date: String(basePayload.date),
            type,
          };
          const response = await transactionApi.create(createPayload);
          Message.success("流水已录入");
          if (response.data.risk && response.data.risk.level !== "low") {
            Message.warning(`记录已保存；风险提示：${response.data.risk.message}`);
          }
        }
      }
      onSuccess();
      onClose();
    } catch (error) {
      const fallback = isRefund ? "退款失败" : isEdit ? "流水更新失败" : "流水录入失败";
      Message.error(errorMessage(error, fallback));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      className="transaction-entry-modal"
      title={title}
      visible={visible}
      onCancel={requestClose}
      footer={null}
      maskClosable={!submitting}
      escToExit={!submitting}
      style={{ width: 760, maxWidth: "calc(100vw - 24px)" }}
      unmountOnExit
    >
      {detailError ? (
        <ResourceNotice
          tone="danger"
          message={detailError}
          action={transactionId ? (
            <Button size="small" icon={<IconRefresh />} onClick={() => setDetailReloadToken((value) => value + 1)}>
              重新加载
            </Button>
          ) : undefined}
        />
      ) : null}

      {!isRefund && accountError ? (
        <ResourceNotice
          tone="danger"
          message={accountError}
          action={<Button size="small" icon={<IconRefresh />} onClick={() => setResourceReloadToken((value) => value + 1)}>重试</Button>}
        />
      ) : null}
      {!isRefund && categoryError ? (
        <ResourceNotice
          tone="danger"
          message={categoryError}
          action={<Button size="small" icon={<IconRefresh />} onClick={() => setResourceReloadToken((value) => value + 1)}>重试</Button>}
        />
      ) : null}

      {!isRefund && !loadingAccounts && !accountError && accounts.length === 0 ? (
        <ResourceNotice
          tone="warning"
          message="还没有可用资金账户。请先创建账户，再录入流水。"
          action={
            <Button
              size="small"
              onClick={() => router.push("/accounts")}
            >
              前往账户管理
            </Button>
          }
        />
      ) : null}

      {!isRefund && !loadingCategories && categoriesLoaded && filteredCategories.length === 0 ? (
        <ResourceNotice tone="warning" message={`当前没有可用的${type === 1 ? "收入" : "支出"}分类，请先初始化或维护分类。`} />
      ) : null}

      {isRefund && sourceTransaction ? (
        <div
          className="mb-4 rounded-xl border p-4"
          style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
          aria-live="polite"
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="font-medium" style={{ color: "var(--text-color-1)" }}>
              原流水 #{sourceTransaction.id} · {sourceTransaction.note || sourceTransaction.categoryName || "未命名"}
            </div>
            <Tag color={sourceTransaction.isRefundable ? "purple" : "gray"}>退款依据</Tag>
          </div>
          <div className="mt-3 grid grid-cols-3 gap-2 text-sm">
            <RefundMetric label="原金额" value={Number(sourceTransaction.amount)} />
            <RefundMetric label="已退款" value={Number(sourceTransaction.refundedAmount || 0)} />
            <RefundMetric label="剩余可退" value={remainingRefundable || 0} emphasis />
          </div>
        </div>
      ) : null}

      <Form
        form={form}
        layout="vertical"
        onSubmit={handleSubmit}
        initialValues={{ date: localToday() }}
        disabled={loadingDetail || Boolean(detailError)}
      >
        {isCreate ? (
          <div
            className="transaction-type-switch mb-5 flex"
            role="group"
            aria-label="流水类型"
          >
            <button
              type="button"
              aria-pressed={type === 2}
              data-active={type === 2}
              data-tone="expense"
              className="transaction-type-option flex-1 py-3 text-center font-medium transition-all"
              style={{
                backgroundColor: type === 2 ? "var(--color-danger)" : "transparent",
                color: type === 2 ? "#fff" : "var(--text-color-3)",
                border: "none",
              }}
              onClick={() => changeType(2)}
            >
              <span aria-hidden="true" className="mr-2 text-lg">💸</span>
              成本支出
            </button>
            <button
              type="button"
              aria-pressed={type === 1}
              data-active={type === 1}
              data-tone="income"
              className="transaction-type-option flex-1 py-3 text-center font-medium transition-all"
              style={{
                backgroundColor: type === 1 ? "var(--color-success)" : "transparent",
                color: type === 1 ? "#fff" : "var(--text-color-3)",
                border: "none",
              }}
              onClick={() => changeType(1)}
            >
              <span aria-hidden="true" className="mr-2 text-lg">💰</span>
              收入
            </button>
          </div>
        ) : !isRefund && sourceTransaction ? (
          <div className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-xl border px-3 py-2" style={{ borderColor: "var(--border-color-light)" }}>
            <span className="text-sm" style={{ color: "var(--text-color-2)" }}>流水类型</span>
            <Tag color={type === 1 ? "green" : "red"}>{type === 1 ? "收入" : "成本支出"} · 编辑时不可更改</Tag>
          </div>
        ) : null}

        <FormItem
          label={isRefund ? "退款金额" : "金额"}
          field="amount"
          rules={[
            { required: true, message: "请输入金额" },
            {
              validator: (value: unknown) => {
                const number = Number(value);
                if (!number || number <= 0) return Promise.reject("金额必须大于 0");
                if (number > 10000000) return Promise.reject("金额不能超过 10,000,000");
                if (isRefund && remainingRefundable !== null && number > remainingRefundable) {
                  return Promise.reject(`退款金额不能超过剩余可退 ${formatAmount(remainingRefundable)}`);
                }
                return Promise.resolve();
              },
            },
          ]}
        >
          <Input
            aria-label={isRefund ? "退款金额" : "流水金额"}
            type="number"
            min={0.01}
            step={0.01}
            placeholder="0.00"
            prefix={
              <span
                aria-hidden="true"
                className="text-2xl font-bold"
                style={{ color: type === 1 && !isRefund ? "var(--color-success)" : "var(--color-danger)" }}
              >
                {type === 1 && !isRefund ? "+" : "-"}
              </span>
            }
            style={{ height: 56, fontSize: 24, fontWeight: 700, borderRadius: 14 }}
          />
        </FormItem>

        {!isRefund ? (
          <FormItem label="分类" field="categoryId" rules={[{ required: true, message: "请选择分类" }]}>
            {loadingCategories ? (
              <div className="rounded-xl border p-4 text-center text-sm" style={{ borderColor: "var(--border-color-light)", color: "var(--text-color-3)" }} role="status">
                正在加载分类…
              </div>
            ) : (
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-4">
                {filteredCategories.map((category) => {
                  const selected = selectedCategory === category.id;
                  return (
                    <button
                      key={category.id}
                      type="button"
                      aria-pressed={selected}
                      aria-label={`分类：${category.name}`}
                      onClick={() => {
                        setSelectedCategory(category.id);
                        form.setFieldValue("categoryId", category.id);
                      }}
                      data-active={selected}
                      className="transaction-category-option flex cursor-pointer flex-col items-center gap-2 p-3 transition-all"
                      style={{
                        backgroundColor: selected ? `${category.color}20` : "var(--bg-color-page)",
                        borderColor: selected ? category.color : "transparent",
                        boxShadow: selected ? `0 7px 18px ${category.color}20` : "none",
                      }}
                    >
                      <span aria-hidden="true" className="text-2xl">{category.icon || "📦"}</span>
                      <span className="text-xs font-medium" style={{ color: selected ? category.color : "var(--text-color-2)" }}>
                        {category.name}
                      </span>
                    </button>
                  );
                })}
              </div>
            )}
          </FormItem>
        ) : null}

        <Row gutter={[20, 0]}>
          {!isRefund ? (
            <Col xs={24} md={12}>
              <FormItem label="账户" field="accountId" rules={[{ required: true, message: "请选择账户" }]}>
                <Select aria-label="资金账户" loading={loadingAccounts} placeholder="选择账户" style={{ width: "100%" }}>
                  {accounts.map((account) => (
                    <Select.Option key={account.id} value={account.id}>
                      {account.name} ({formatAmount(account.balance)})
                    </Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
          ) : null}
          <Col xs={24} md={isRefund ? 24 : 12}>
            <FormItem label={isRefund ? "退款日期" : "日期"} field="date" rules={[{ required: true, message: "请选择日期" }]}>
              <DatePicker aria-label={isRefund ? "退款日期" : "流水日期"} format="YYYY-MM-DD" className="w-full" style={{ height: 40 }} />
            </FormItem>
          </Col>
        </Row>

        <FormItem label="备注" field="note">
          <Input.TextArea aria-label="流水备注" placeholder="添加备注（可选）" maxLength={200} showWordLimit style={{ borderRadius: 12 }} />
        </FormItem>
        <div className="transaction-note-list -mt-3 mb-5 flex flex-wrap gap-2" aria-label="备注模板">
          {quickNotes.map((note) => (
            <Button className="transaction-note-chip" key={note} htmlType="button" size="mini" type="outline" onClick={() => form.setFieldValue("note", note)}>
              {note}
            </Button>
          ))}
        </div>

        <div className="transaction-form-actions flex flex-col-reverse gap-3 sm:flex-row">
          <Button
            htmlType="button"
            long
            disabled={submitting}
            onClick={requestClose}
            style={{ height: 48, borderRadius: 14, fontWeight: 600 }}
          >
            取消
          </Button>
          <Button
            type="primary"
            htmlType="submit"
            long
            disabled={submitBlocked}
            loading={submitting}
            style={{
              height: 48,
              borderRadius: 14,
              fontWeight: 600,
              background: type === 1 && !isRefund
                ? "linear-gradient(135deg, #10b981 0%, #059669 100%)"
                : "linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)",
              border: "none",
            }}
          >
            {isRefund ? "确认退款" : isEdit ? "保存修改" : type === 1 ? "记录收入" : "记录成本"}
          </Button>
        </div>
      </Form>
    </Modal>
  );
}

function ResourceNotice({
  message,
  action,
  tone,
}: {
  message: string;
  action?: ReactNode;
  tone: "warning" | "danger";
}) {
  const danger = tone === "danger";
  return (
    <div
      className="mb-4 flex flex-col gap-3 rounded-xl border p-3 sm:flex-row sm:items-center sm:justify-between"
      style={{
        borderColor: danger ? "rgba(239, 68, 68, 0.28)" : "var(--color-warning-border)",
        backgroundColor: danger ? "rgba(239, 68, 68, 0.06)" : "var(--color-warning-soft)",
      }}
      role={danger ? "alert" : "status"}
    >
      <div className="flex items-start gap-2 text-sm" style={{ color: danger ? "rgb(var(--red-6))" : "var(--color-warning)" }}>
        <IconExclamationCircle className="mt-0.5 shrink-0" />
        <span>{message}</span>
      </div>
      {action}
    </div>
  );
}

function RefundMetric({ label, value, emphasis = false }: { label: string; value: number; emphasis?: boolean }) {
  return (
    <div className="min-w-0 rounded-lg px-2 py-2 text-center" style={{ backgroundColor: "var(--color-fill-1)" }}>
      <div className="text-[11px]" style={{ color: "var(--text-color-3)" }}>{label}</div>
      <div className="mt-1 truncate text-sm font-semibold" style={{ color: emphasis ? "var(--color-primary)" : "var(--text-color-1)" }}>
        {formatAmount(value)}
      </div>
    </div>
  );
}
