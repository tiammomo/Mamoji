"use client";
import { useState, useEffect } from "react";
import { Form, Input, Button, DatePicker, Message, Alert, Card, Grid, Select } from "@arco-design/web-react";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { transactionApi } from "@/lib/api/transactions";
import { accountApi } from "@/lib/api/accounts";
import { useCategoryStore } from "@/lib/stores/categoryStore";
import PageHeader from "@/components/common/PageHeader";
import { formatAmount } from "@/lib/utils/format";
import type { CreateTransactionDTO, RiskAssessment, Account } from "@/lib/types";

const FormItem = Form.Item;
const { Row, Col } = Grid;

export default function NewTransactionPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const t = useTranslations("transaction");
  const { fetchCategories, getByType } = useCategoryStore();
  const [form] = Form.useForm();
  const [type, setType] = useState<1 | 2>(2);
  const [loading, setLoading] = useState(false);
  const [risk, setRisk] = useState<RiskAssessment | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedCategory, setSelectedCategory] = useState<number | null>(null);

  const editId = searchParams.get("edit");
  const refundId = searchParams.get("refund");

  useEffect(() => {
    fetchCategories();
    accountApi.list().then((r) => setAccounts(r.data));
  }, [fetchCategories]);

  useEffect(() => {
    const id = editId || refundId;
    if (!id) return;

    transactionApi.get(Number(id)).then((res) => {
      const tx = res.data;
      setType(tx.type === 1 ? 1 : 2);
      setSelectedCategory(tx.categoryId);
      form.setFieldsValue({
        amount: refundId ? undefined : tx.amount,
        categoryId: tx.categoryId,
        accountId: tx.accountId,
        date: new Date().toISOString().split("T")[0],
        note: refundId ? `Refund for #${tx.id}` : tx.note,
      });
    });
  }, [editId, refundId, form]);

  const filteredCategories = getByType(type === 1 ? "income" : "expense");

  const changeType = (nextType: 1 | 2) => {
    setType(nextType);
    setSelectedCategory(null);
    form.setFieldValue("categoryId", undefined);
  };

  const handleSubmit = async (values: CreateTransactionDTO) => {
    setLoading(true);
    try {
      const payload = {
        ...values,
        amount: Number(values.amount),
        accountId: Number(values.accountId),
        type,
        categoryId: selectedCategory || values.categoryId,
      };
      const res = refundId
        ? await transactionApi.refund(Number(refundId), {
            amount: Number(values.amount),
            date: values.date,
            note: values.note,
          })
        : editId
          ? { data: { transaction: await transactionApi.update(Number(editId), payload).then((r) => r.data), risk: null } }
          : await transactionApi.create(payload);
      if (res.data.risk) {
        setRisk(res.data.risk);
      }
      Message.success(refundId ? "退款成功" : editId ? "更新成功" : "流水已录入");
      if (!refundId && !editId) {
        form.resetFields();
        setSelectedCategory(null);
      } else {
        router.push("/transactions");
      }
    } catch {
      Message.error(refundId ? "退款失败" : editId ? "更新失败" : "流水录入失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="w-full max-w-[780px] mx-auto animate-fade-in">
      <PageHeader
        title={refundId ? t("refund") : editId ? "编辑流水" : t("new")}
        icon={refundId ? "↩️" : "✏️"}
        back
      />

      <Card className="transaction-form-card" style={{ borderRadius: 16 }} bodyStyle={{ padding: 28 }}>
        {/* Type selector */}
        <div className="flex mb-6 rounded-xl overflow-hidden" style={{ backgroundColor: "var(--bg-color-page)" }}>
          <button
            className="flex-1 py-4 text-center font-medium transition-all"
            style={{
              backgroundColor: type === 2 ? "var(--color-danger)" : "transparent",
              color: type === 2 ? "#fff" : "var(--text-color-3)",
              borderRadius: type === 2 ? 12 : 0,
            }}
            onClick={() => {
              changeType(2);
            }}
          >
            <span className="text-lg mr-2">💸</span>
            {t("expense")}
          </button>
          <button
            className="flex-1 py-4 text-center font-medium transition-all"
            style={{
              backgroundColor: type === 1 ? "var(--color-success)" : "transparent",
              color: type === 1 ? "#fff" : "var(--text-color-3)",
              borderRadius: type === 1 ? 12 : 0,
            }}
            onClick={() => {
              changeType(1);
            }}
          >
            <span className="text-lg mr-2">💰</span>
            {t("income")}
          </button>
        </div>

        <Form
          form={form}
          layout="vertical"
          onSubmit={handleSubmit}
          initialValues={{ date: new Date().toISOString().split("T")[0] }}
        >
          {/* Amount */}
          <FormItem
            label={t("amount")}
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
                  style={{ color: type === 1 ? "var(--color-success)" : "var(--color-danger)" }}
                >
                  {type === 1 ? "+" : "-"}
                </span>
              }
              style={{
                height: 64,
                fontSize: 28,
                fontWeight: 700,
                borderRadius: 16,
              }}
            />
          </FormItem>

          {/* Category selector */}
          <FormItem label={t("category")} field="categoryId" rules={[{ required: true, message: "请选择分类" }]}>
            <div className="grid grid-cols-4 gap-3">
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
                    className="flex flex-col items-center gap-2 p-3 rounded-xl cursor-pointer transition-all"
                    style={{
                      backgroundColor: isSelected ? cat.color + "20" : "var(--bg-color-page)",
                      border: isSelected
                        ? `2px solid ${cat.color}`
                        : "2px solid transparent",
                      transform: isSelected ? "scale(1.05)" : "scale(1)",
                    }}
                  >
                    <span className="text-2xl">{cat.icon || "📦"}</span>
                    <span
                      className="text-xs font-medium"
                      style={{ color: isSelected ? cat.color : "var(--text-color-2)" }}
                    >
                      {cat.name}
                    </span>
                  </button>
                );
              })}
            </div>
          </FormItem>

          {/* Account and Date */}
          <Row gutter={[20, 0]}>
            <Col xs={24} md={12}>
              <FormItem label={t("account")} field="accountId" rules={[{ required: true, message: "请选择账户" }]}>
                <Select
                  placeholder={t("selectAccount")}
                  style={{
                    width: "100%",
                    height: 48,
                    borderRadius: 12,
                  }}
                >
                  {accounts.map((acc) => (
                    <Select.Option key={acc.id} value={acc.id}>
                      {acc.name} ({formatAmount(acc.balance)})
                    </Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label={t("date")} field="date" rules={[{ required: true, message: "请选择日期" }]}>
                <DatePicker className="w-full" style={{ height: 48, borderRadius: 12 }} />
              </FormItem>
            </Col>
          </Row>

          {/* Note */}
          <FormItem label={t("note")} field="note">
            <Input.TextArea
              placeholder="添加备注（可选）"
              maxLength={200}
              showWordLimit
              style={{ borderRadius: 12 }}
            />
          </FormItem>

          {/* Submit button */}
          <FormItem>
            <Button
              type="primary"
              htmlType="submit"
              long
              size="large"
              loading={loading}
              style={{
                height: 56,
                borderRadius: 16,
                fontSize: 18,
                fontWeight: 600,
                background: type === 1
                  ? "linear-gradient(135deg, #10b981 0%, #059669 100%)"
                  : "linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)",
                border: "none",
              }}
            >
              {refundId ? "↩️ 确认退款" : editId ? "保存修改" : type === 1 ? "💰 记录收入" : "💸 记录成本"}
            </Button>
          </FormItem>
        </Form>

        {/* Risk assessment */}
        {risk && (
          <Alert
            type={risk.level === "low" ? "success" : risk.level === "medium" ? "warning" : "error"}
            title={
              <div className="flex items-center gap-2">
                <span className="text-lg">
                  {risk.level === "low" ? "✅" : risk.level === "medium" ? "⚠️" : "🚨"}
                </span>
                <span>风险评估: {risk.level}</span>
              </div>
            }
            content={
              <div>
                <p className="mb-2">{risk.message}</p>
                {risk.flags.length > 0 && (
                  <div className="flex flex-wrap gap-2">
                    {risk.flags.map((flag) => (
                      <span
                        key={flag}
                        className="inline-block px-3 py-1 rounded-full text-xs"
                        style={{
                          backgroundColor: "var(--bg-color-page)",
                          color: "var(--text-color-2)",
                        }}
                      >
                        {flag}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            }
            className="mt-6"
            style={{ borderRadius: 12 }}
          />
        )}
      </Card>
    </div>
  );
}
