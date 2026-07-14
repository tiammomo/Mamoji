"use client";

import { useEffect, useState } from "react";
import { Button, DatePicker, Empty, Form, Input, InputNumber, Message, Modal, Skeleton, Tag } from "@arco-design/web-react";
import { IconCheckCircle, IconExclamationCircle } from "@arco-design/web-react/icon";
import { accountApi } from "@/lib/api/accounts";
import { formatAmount, formatDate, formatDateTime } from "@/lib/utils/format";
import type { Account, AccountReconciliation } from "@/lib/types";

type Props = {
  account: Account | null;
  onClose: () => void;
  onSuccess: () => void;
};

type Values = {
  statementDate: string;
  statementBalance: number;
  note?: string;
};

const today = () => new Date().toISOString().slice(0, 10);

export default function AccountReconciliationModal({ account, onClose, onSuccess }: Props) {
  const [form] = Form.useForm<Values>();
  const [history, setHistory] = useState<AccountReconciliation[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [latest, setLatest] = useState<AccountReconciliation | null>(null);

  useEffect(() => {
    if (!account) return;
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      setLatest(null);
      form.setFieldsValue({ statementDate: today(), statementBalance: account.balance, note: "" });
      try {
        const response = await accountApi.reconciliations(account.id);
        if (!cancelled) setHistory(response.data);
      } catch {
        if (!cancelled) Message.error("对账历史加载失败");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [account, form]);

  const submit = async (values: Values) => {
    if (!account) return;
    setSubmitting(true);
    try {
      const response = await accountApi.reconcile(account.id, {
        statementDate: values.statementDate,
        statementBalance: Number(values.statementBalance),
        note: values.note?.trim() || undefined,
      });
      setLatest(response.data);
      setHistory((current) => [response.data, ...current]);
      onSuccess();
      if (response.data.status === "reconciled") Message.success("账面余额与银行余额一致，对账完成");
      else Message.warning(`发现差异 ${formatAmount(response.data.difference)}，账户已标记为异常`);
    } catch {
      Message.error("对账记录保存失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={account ? `${account.name} · 余额对账` : "余额对账"}
      visible={Boolean(account)}
      style={{ width: "min(860px, calc(100vw - 24px))" }}
      footer={null}
      maskClosable={!submitting}
      closable={!submitting}
      onCancel={onClose}
    >
      {account ? (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[320px_minmax(0,1fr)]">
          <div>
            <div className="rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}>
              <div className="text-xs" style={{ color: "var(--text-color-3)" }}>系统当前余额</div>
              <div className="mt-2 text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{formatAmount(account.balance)}</div>
              <div className="mt-2 text-xs" style={{ color: "var(--text-color-4)" }}>录入银行或支付平台在同一日期显示的期末余额，系统会保存差额快照。</div>
            </div>

            <Form form={form} layout="vertical" className="mt-4" onSubmit={submit}>
              <Form.Item label="对账日期" field="statementDate" rules={[{ required: true, message: "请选择对账日期" }]}>
                <DatePicker format="YYYY-MM-DD" className="w-full" disabledDate={(date) => date.isAfter(new Date(), "day")} />
              </Form.Item>
              <Form.Item label="银行/平台余额" field="statementBalance" rules={[{ required: true, message: "请输入外部余额" }]}>
                <InputNumber precision={2} className="w-full" placeholder="0.00" />
              </Form.Item>
              <Form.Item label="对账说明" field="note">
                <Input.TextArea maxLength={200} showWordLimit rows={3} placeholder="例如：银行月末对账单余额" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={submitting} long>保存对账快照</Button>
            </Form>

            {latest ? (
              <div className="mt-4 rounded-xl border p-3" style={{ borderColor: latest.status === "reconciled" ? "rgba(16, 185, 129, 0.28)" : "rgba(239, 68, 68, 0.28)", backgroundColor: latest.status === "reconciled" ? "rgba(16, 185, 129, 0.06)" : "rgba(239, 68, 68, 0.05)" }}>
                <div className="flex items-start gap-2">
                  {latest.status === "reconciled" ? <IconCheckCircle style={{ color: "var(--color-success)" }} /> : <IconExclamationCircle style={{ color: "var(--color-danger)" }} />}
                  <div>
                    <div className="text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>{latest.status === "reconciled" ? "余额一致" : "发现账实差异"}</div>
                    <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>差额 {formatAmount(latest.difference)}</div>
                  </div>
                </div>
              </div>
            ) : null}
          </div>

          <div className="min-w-0 rounded-xl border" style={{ borderColor: "var(--border-color-light)" }}>
            <div className="border-b px-4 py-3 text-sm font-semibold" style={{ borderColor: "var(--border-color-light)", color: "var(--text-color-1)" }}>最近 50 次对账记录</div>
            {loading ? <div className="p-4"><Skeleton /></div> : history.length === 0 ? <div className="py-10"><Empty description="暂无对账历史" /></div> : (
              <div className="max-h-[500px] overflow-y-auto">
                {history.map((item) => (
                  <div key={item.id} className="border-b p-4 last:border-0" style={{ borderColor: "var(--border-color-light)" }}>
                    <div className="flex items-center justify-between gap-3">
                      <div className="text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{formatDate(item.statementDate)}</div>
                      <Tag color={item.status === "reconciled" ? "green" : "red"}>{item.status === "reconciled" ? "余额一致" : "存在差异"}</Tag>
                    </div>
                    <div className="mt-2 grid grid-cols-3 gap-2 text-xs">
                      <div><span style={{ color: "var(--text-color-4)" }}>外部</span><div className="mt-1" style={{ color: "var(--text-color-2)" }}>{formatAmount(item.statementBalance)}</div></div>
                      <div><span style={{ color: "var(--text-color-4)" }}>系统</span><div className="mt-1" style={{ color: "var(--text-color-2)" }}>{formatAmount(item.systemBalance)}</div></div>
                      <div><span style={{ color: "var(--text-color-4)" }}>差额</span><div className="mt-1" style={{ color: item.difference === 0 ? "var(--color-success)" : "var(--color-danger)" }}>{formatAmount(item.difference)}</div></div>
                    </div>
                    {item.note ? <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{item.note}</div> : null}
                    <div className="mt-2 text-[11px]" style={{ color: "var(--text-color-4)" }}>{formatDateTime(item.createdAt)}</div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      ) : null}
    </Modal>
  );
}
