"use client";
import { useEffect, useState } from "react";
import { Card, Button, Grid, Modal, Form, Input, Select, Message, Tag, Empty } from "@arco-design/web-react";
import { IconPlus, IconDelete, IconEdit } from "@arco-design/web-react/icon";
import { useTranslations } from "next-intl";
import { accountApi } from "@/lib/api/accounts";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { formatAmount } from "@/lib/utils/format";
import { ACCOUNT_TYPE_LABELS } from "@/lib/utils/constants";
import type { Account, AccountSummary, CreateAccountDTO } from "@/lib/types";

const { Row, Col } = Grid;
const FormItem = Form.Item;

const accountIcons: Record<string, string> = {
  cash: "💵",
  bank: "🏦",
  credit: "💳",
  digital: "📱",
  investment: "📈",
  debt: "📉",
};

export default function AccountsPage() {
  const t = useTranslations("account");
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [summary, setSummary] = useState<AccountSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form] = Form.useForm();

  const fetchData = async () => {
    try {
      const [accRes, sumRes] = await Promise.all([
        accountApi.list(),
        accountApi.summary(),
      ]);
      setAccounts(accRes.data);
      setSummary(sumRes.data);
    } catch {
      // silent
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let cancelled = false;

    const loadAccounts = async () => {
      try {
        const [accRes, sumRes] = await Promise.all([
          accountApi.list(),
          accountApi.summary(),
        ]);
        if (cancelled) return;
        setAccounts(accRes.data);
        setSummary(sumRes.data);
      } catch {
        // silent
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void loadAccounts();

    return () => {
      cancelled = true;
    };
  }, []);

  const accountsPagination = useClientPagination(accounts, 12);

  const handleSubmit = async (values: CreateAccountDTO) => {
    try {
      if (editingId) {
        await accountApi.update(editingId, values);
        Message.success("更新成功");
      } else {
        await accountApi.create(values);
        Message.success("创建成功");
      }
      setModalVisible(false);
      form.resetFields();
      setEditingId(null);
      fetchData();
    } catch {
      Message.error("操作失败");
    }
  };

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: "确认删除",
      content: "确定要删除这个账户吗？",
      onOk: async () => {
        try {
          await accountApi.delete(id);
          Message.success("删除成功");
          fetchData();
        } catch {
          Message.error("删除失败");
        }
      },
    });
  };

  const openEdit = (account: Account) => {
    setEditingId(account.id);
    form.setFieldsValue(account);
    setModalVisible(true);
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title={t("title")}
        icon="🏦"
        extra={
          <Button
            type="primary"
            icon={<IconPlus />}
            onClick={() => {
              setEditingId(null);
              form.resetFields();
              setModalVisible(true);
            }}
          >
            {t("new")}
          </Button>
        }
      />

      {/* Summary cards */}
      {summary && (
        <Row gutter={16} className="mb-6">
          <Col span={8}>
            <div className="stat-card income">
              <div className="flex items-center gap-3 mb-2">
                <span className="text-2xl">📈</span>
                <span className="text-sm" style={{ color: "var(--text-color-3)" }}>{t("totalAssets")}</span>
              </div>
              <div className="text-2xl font-bold amount-income">{formatAmount(summary.totalAssets)}</div>
            </div>
          </Col>
          <Col span={8}>
            <div className="stat-card expense">
              <div className="flex items-center gap-3 mb-2">
                <span className="text-2xl">📉</span>
                <span className="text-sm" style={{ color: "var(--text-color-3)" }}>{t("totalLiabilities")}</span>
              </div>
              <div className="text-2xl font-bold amount-expense">{formatAmount(summary.totalLiabilities)}</div>
            </div>
          </Col>
          <Col span={8}>
            <div className="stat-card balance">
              <div className="flex items-center gap-3 mb-2">
                <span className="text-2xl">💎</span>
                <span className="text-sm" style={{ color: "var(--text-color-3)" }}>{t("netWorth")}</span>
              </div>
              <div className="text-2xl font-bold" style={{ color: "var(--color-primary)" }}>{formatAmount(summary.netWorth)}</div>
            </div>
          </Col>
        </Row>
      )}

      {/* Account list */}
      {accounts.length === 0 && !loading ? (
        <Card style={{ borderRadius: 16 }}>
          <Empty
            icon="🏦"
            description="暂无账户，点击上方按钮添加"
          />
        </Card>
      ) : (
        <>
          <Row gutter={16}>
            {accountsPagination.pagedData.map((acc, index) => (
              <Col key={acc.id} xs={24} sm={12} md={8} lg={6}>
                <div
                  className="stat-card mb-4 animate-fade-in hover-lift cursor-pointer"
                  style={{ animationDelay: `${index * 100}ms` }}
                >
                  <div className="flex justify-between items-start mb-3">
                    <div className="flex items-center gap-2">
                      <span className="text-2xl">{accountIcons[acc.type] || "💰"}</span>
                      <div>
                        <div className="font-medium">{acc.name}</div>
                        <Tag
                          color="blue"
                          className="mt-1"
                          style={{ borderRadius: 6, fontSize: 11 }}
                        >
                          {ACCOUNT_TYPE_LABELS[acc.type] || acc.type}
                        </Tag>
                      </div>
                    </div>
                    <div className="flex gap-1">
                      <Button
                        type="text"
                        size="mini"
                        icon={<IconEdit />}
                        onClick={() => openEdit(acc)}
                        style={{ color: "var(--text-color-3)" }}
                      />
                      <Button
                        type="text"
                        size="mini"
                        status="danger"
                        icon={<IconDelete />}
                        onClick={() => handleDelete(acc.id)}
                      />
                    </div>
                  </div>
                  <div className="text-xl font-bold mt-2">
                    <AmountDisplay amount={acc.balance} type={acc.balance >= 0 ? 1 : 2} />
                  </div>
                  {acc.bank && (
                    <div className="text-xs mt-2" style={{ color: "var(--text-color-4)" }}>
                      {acc.bank}
                    </div>
                  )}
                </div>
              </Col>
            ))}
          </Row>
          <AppPagination
            current={accountsPagination.page}
            pageSize={accountsPagination.pageSize}
            total={accountsPagination.total}
            pageSizeOptions={[8, 12, 24, 48]}
            onChange={accountsPagination.handleChange}
          />
        </>
      )}

      {/* Modal */}
      <Modal
        title={editingId ? "编辑账户" : t("new")}
        visible={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={() => form.submit()}
        style={{ borderRadius: 16 }}
      >
        <Form form={form} layout="vertical" onSubmit={handleSubmit}>
          <FormItem label={t("name")} field="name" rules={[{ required: true, message: "请输入名称" }]}>
            <Input placeholder="账户名称" style={{ borderRadius: 12 }} />
          </FormItem>
          <FormItem label={t("type")} field="type" rules={[{ required: true, message: "请选择类型" }]}>
            <Select placeholder="选择类型" style={{ borderRadius: 12 }}>
              {Object.entries(ACCOUNT_TYPE_LABELS).map(([key, label]) => (
                <Select.Option key={key} value={key}>
                  {accountIcons[key]} {label}
                </Select.Option>
              ))}
            </Select>
          </FormItem>
          <FormItem label={t("balance")} field="balance" rules={[{ required: true, message: "请输入余额" }]}>
            <Input type="number" placeholder="0.00" style={{ borderRadius: 12 }} />
          </FormItem>
          <FormItem label="银行" field="bank">
            <Input placeholder="银行名称（可选）" style={{ borderRadius: 12 }} />
          </FormItem>
        </Form>
      </Modal>
    </div>
  );
}
