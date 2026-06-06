"use client";
import { useEffect, useState } from "react";
import { Button, DatePicker, Form, Grid, Input, Message, Modal, Select } from "@arco-design/web-react";
import { transactionApi } from "@/lib/api/transactions";
import { accountApi } from "@/lib/api/accounts";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import { formatAmount } from "@/lib/utils/format";
import type { Account, CreateTransactionDTO } from "@/lib/types";

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

type TransactionFormValues = CreateTransactionDTO;

const today = () => new Date().toISOString().split("T")[0];

export default function TransactionFormModal({
  visible,
  mode,
  transactionId,
  onClose,
  onSuccess,
}: TransactionFormModalProps) {
  const { fetchCategories, getByType } = useCategoryStore();
  const [form] = Form.useForm<TransactionFormValues>();
  const [type, setType] = useState<1 | 2>(2);
  const [submitting, setSubmitting] = useState(false);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedCategory, setSelectedCategory] = useState<number | null>(null);

  useEffect(() => {
    if (!visible) return;

    void fetchCategories();
    void accountApi.list().then((res) => setAccounts(res.data)).catch(() => {
      Message.error("资金账户加载失败");
    });
  }, [fetchCategories, visible]);

  useEffect(() => {
    if (!visible) return;

    const timer = window.setTimeout(() => {
      setSelectedCategory(null);
      setType(2);
      form.resetFields();
      form.setFieldsValue({ date: today() });

      if (mode === "create" || !transactionId) {
        return;
      }

      setLoadingDetail(true);
      transactionApi.get(transactionId)
        .then((res) => {
          const tx = res.data;
          const nextType = tx.type === 1 ? 1 : 2;
          setType(nextType);
          setSelectedCategory(tx.categoryId);
          form.setFieldsValue({
            amount: mode === "refund" ? undefined : tx.amount,
            categoryId: tx.categoryId,
            accountId: tx.accountId,
            date: mode === "refund" ? today() : tx.date,
            note: mode === "refund" ? `退款：${tx.note || tx.categoryName || `流水 #${tx.id}`}` : tx.note,
          });
        })
        .catch(() => Message.error("流水详情加载失败"))
        .finally(() => setLoadingDetail(false));
    }, 0);

    return () => window.clearTimeout(timer);
  }, [form, mode, transactionId, visible]);

  const filteredCategories = getByType(type === 1 ? "income" : "expense");
  const isRefund = mode === "refund";

  const title = mode === "refund" ? "发起退款" : mode === "edit" ? "编辑流水" : "录入流水";

  const changeType = (nextType: 1 | 2) => {
    setType(nextType);
    setSelectedCategory(null);
    form.setFieldValue("categoryId", undefined);
  };

  const handleSubmit = async (values: TransactionFormValues) => {
    setSubmitting(true);
    try {
      if (isRefund && transactionId) {
        const res = await transactionApi.refund(transactionId, {
          amount: Number(values.amount),
          date: values.date,
          note: values.note,
        });
        if (res.data.risk && res.data.risk.level !== "low") {
          Message.warning(res.data.risk.message);
        } else {
          Message.success("退款成功");
        }
      } else {
        const payload = {
          ...values,
          amount: Number(values.amount),
          accountId: Number(values.accountId),
          categoryId: selectedCategory || values.categoryId,
          type,
        };

        if (mode === "edit" && transactionId) {
          await transactionApi.update(transactionId, payload);
          Message.success("流水已更新");
        } else {
          const res = await transactionApi.create(payload);
          if (res.data.risk && res.data.risk.level !== "low") {
            Message.warning(res.data.risk.message);
          } else {
            Message.success("流水已录入");
          }
        }
      }
      onSuccess();
      onClose();
    } catch {
      Message.error(isRefund ? "退款失败" : mode === "edit" ? "流水更新失败" : "流水录入失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={title}
      visible={visible}
      onCancel={onClose}
      footer={null}
      style={{ width: 760 }}
      unmountOnExit
    >
      <Form
        form={form}
        layout="vertical"
        onSubmit={handleSubmit}
        initialValues={{ date: today() }}
        disabled={loadingDetail}
      >
        {!isRefund && (
          <div className="mb-5 flex overflow-hidden rounded-xl" style={{ backgroundColor: "var(--bg-color-page)" }}>
            <button
              type="button"
              className="flex-1 py-3 text-center font-medium transition-all"
              style={{
                backgroundColor: type === 2 ? "var(--color-danger)" : "transparent",
                color: type === 2 ? "#fff" : "var(--text-color-3)",
                borderRadius: type === 2 ? 12 : 0,
              }}
              onClick={() => changeType(2)}
            >
              <span className="mr-2 text-lg">💸</span>
              成本支出
            </button>
            <button
              type="button"
              className="flex-1 py-3 text-center font-medium transition-all"
              style={{
                backgroundColor: type === 1 ? "var(--color-success)" : "transparent",
                color: type === 1 ? "#fff" : "var(--text-color-3)",
                borderRadius: type === 1 ? 12 : 0,
              }}
              onClick={() => changeType(1)}
            >
              <span className="mr-2 text-lg">💰</span>
              收入
            </button>
          </div>
        )}

        <FormItem
          label={isRefund ? "退款金额" : "金额"}
          field="amount"
          rules={[
            { required: true, message: "请输入金额" },
            {
              validator: (value: unknown) => {
                const num = Number(value);
                if (!num || num <= 0) return Promise.reject("金额必须大于0");
                if (num > 10000000) return Promise.reject("金额不能超过10,000,000");
                return Promise.resolve();
              },
            },
          ]}
        >
          <Input
            type="number"
            placeholder="0.00"
            prefix={
              <span
                className="text-2xl font-bold"
                style={{ color: type === 1 && !isRefund ? "var(--color-success)" : "var(--color-danger)" }}
              >
                {type === 1 && !isRefund ? "+" : "-"}
              </span>
            }
            style={{ height: 56, fontSize: 24, fontWeight: 700, borderRadius: 14 }}
          />
        </FormItem>

        {!isRefund && (
          <FormItem label="分类" field="categoryId" rules={[{ required: true, message: "请选择分类" }]}>
            <div className="grid grid-cols-3 gap-3 md:grid-cols-4">
              {filteredCategories.map((cat) => {
                const isSelected = selectedCategory === cat.id;
                return (
                  <button
                    key={cat.id}
                    type="button"
                    onClick={() => {
                      setSelectedCategory(cat.id);
                      form.setFieldValue("categoryId", cat.id);
                    }}
                    className="flex cursor-pointer flex-col items-center gap-2 rounded-xl p-3 transition-all"
                    style={{
                      backgroundColor: isSelected ? `${cat.color}20` : "var(--bg-color-page)",
                      border: isSelected ? `2px solid ${cat.color}` : "2px solid transparent",
                    }}
                  >
                    <span className="text-2xl">{cat.icon || "📦"}</span>
                    <span className="text-xs font-medium" style={{ color: isSelected ? cat.color : "var(--text-color-2)" }}>
                      {cat.name}
                    </span>
                  </button>
                );
              })}
            </div>
          </FormItem>
        )}

        <Row gutter={[20, 0]}>
          {!isRefund && (
            <Col xs={24} md={12}>
              <FormItem label="账户" field="accountId" rules={[{ required: true, message: "请选择账户" }]}>
                <Select placeholder="选择账户" style={{ width: "100%" }}>
                  {accounts.map((acc) => (
                    <Select.Option key={acc.id} value={acc.id}>
                      {acc.name} ({formatAmount(acc.balance)})
                    </Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
          )}
          <Col xs={24} md={isRefund ? 24 : 12}>
            <FormItem label={isRefund ? "退款日期" : "日期"} field="date" rules={[{ required: true, message: "请选择日期" }]}>
              <DatePicker className="w-full" style={{ height: 40 }} />
            </FormItem>
          </Col>
        </Row>

        <FormItem label="备注" field="note">
          <Input.TextArea placeholder="添加备注（可选）" maxLength={200} showWordLimit style={{ borderRadius: 12 }} />
        </FormItem>

        <div className="flex gap-3">
          <Button
            long
            onClick={onClose}
            style={{ height: 48, borderRadius: 14, fontWeight: 600 }}
          >
            取消
          </Button>
          <Button
            type="primary"
            htmlType="submit"
            long
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
            {isRefund ? "确认退款" : mode === "edit" ? "保存修改" : type === 1 ? "记录收入" : "记录成本"}
          </Button>
        </div>
      </Form>
    </Modal>
  );
}
