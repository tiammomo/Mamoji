"use client";

import { useEffect, useMemo, useState } from "react";
import {
  Button,
  Card,
  DatePicker,
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
  IconDelete,
  IconEdit,
  IconExclamationCircle,
  IconPlus,
  IconRefresh,
  IconSafe,
  IconSearch,
  IconStorage,
  IconSwap,
} from "@arco-design/web-react/icon";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import RiskBadge from "@/components/common/RiskBadge";
import AccountReconciliationModal from "@/components/accounts/AccountReconciliationModal";
import { useAsyncAction } from "@/lib/hooks/useAsyncAction";
import { accountApi } from "@/lib/api/accounts";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { useAppStore } from "@/lib/stores/appStore";
import { formatAmount, formatDate } from "@/lib/utils/format";
import type { Account, AccountRiskLevel, AccountSummary, CreateAccountDTO } from "@/lib/types";

const { Row, Col } = Grid;
const FormItem = Form.Item;

type AccountViewData = {
  accounts: Account[];
  summary: AccountSummary | null;
};

type AccountFilters = {
  keyword: string;
  type: string;
  status: string;
  reconciliationStatus: string;
  riskLevel: string;
};

type AccountFormValues = Omit<CreateAccountDTO, "includeInNetWorth"> & {
  includeInNetWorth?: boolean | string;
};

const initialFilters: AccountFilters = {
  keyword: "",
  type: "",
  status: "",
  reconciliationStatus: "",
  riskLevel: "",
};

const accountTypeMeta: Record<string, { label: string; icon: React.ReactNode; color: string }> = {
  cash: { label: "现金", icon: <IconStorage />, color: "green" },
  bank: { label: "银行账户", icon: <IconSafe />, color: "arcoblue" },
  credit: { label: "信用账户", icon: <IconSwap />, color: "orange" },
  digital: { label: "数字钱包", icon: <IconStorage />, color: "cyan" },
  investment: { label: "理财账户", icon: <IconSafe />, color: "purple" },
  debt: { label: "负债账户", icon: <IconExclamationCircle />, color: "red" },
};

const statusMeta: Record<number, { label: string; color: string }> = {
  0: { label: "停用", color: "gray" },
  1: { label: "启用", color: "green" },
  2: { label: "冻结", color: "orange" },
};

const reconciliationMeta: Record<string, { label: string; color: string }> = {
  reconciled: { label: "已对账", color: "green" },
  pending: { label: "待对账", color: "orange" },
  exception: { label: "有异常", color: "red" },
};

const riskText: Record<string, string> = {
  low: "风险低",
  medium: "需关注",
  high: "高风险",
  critical: "严重",
};

const today = () => new Date().toISOString().slice(0, 10);

const loadAccountView = async (): Promise<AccountViewData> => {
  const [accRes, sumRes] = await Promise.all([
    accountApi.list(),
    accountApi.summary(),
  ]);
  return {
    accounts: accRes.data,
    summary: sumRes.data,
  };
};

export default function AccountsPage() {
  const activeSubjectType = useAppStore((state) => state.activeSubjectType);
  const isHousehold = activeSubjectType === "household";
  const [viewData, setViewData] = useState<AccountViewData>({ accounts: [], summary: null });
  const [filters, setFilters] = useState<AccountFilters>(initialFilters);
  const [initialLoading, setInitialLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [reconcilingAccount, setReconcilingAccount] = useState<Account | null>(null);
  const [form] = Form.useForm();
  const saveAction = useAsyncAction<"save">();
  const saving = saveAction.isRunning("save");
  const { accounts, summary } = viewData;

  const refreshData = async (quiet = false) => {
    setRefreshing(true);
    try {
      setViewData(await loadAccountView());
      if (!quiet) {
        Message.success("账户数据已刷新");
      }
    } catch {
      Message.error("账户数据刷新失败");
    } finally {
      setRefreshing(false);
      setInitialLoading(false);
    }
  };

  useEffect(() => {
    let cancelled = false;

    const loadAccounts = async () => {
      try {
        const nextView = await loadAccountView();
        if (cancelled) return;
        setViewData(nextView);
      } catch {
        // Login guard handles authentication redirects globally.
      } finally {
        if (!cancelled) {
          setInitialLoading(false);
        }
      }
    };

    void loadAccounts();

    return () => {
      cancelled = true;
    };
  }, []);

  const filteredAccounts = useMemo(() => {
    const keyword = filters.keyword.trim().toLowerCase();
    return accounts.filter((account) => {
      if (keyword
        && !account.name.toLowerCase().includes(keyword)
        && !(account.bank || "").toLowerCase().includes(keyword)
        && !(account.openingBank || "").toLowerCase().includes(keyword)
        && !(account.ownerName || "").toLowerCase().includes(keyword)
        && !(account.purpose || "").toLowerCase().includes(keyword)) {
        return false;
      }
      if (filters.type && account.type !== filters.type) return false;
      if (filters.status && String(account.status) !== filters.status) return false;
      if (filters.reconciliationStatus && account.reconciliationStatus !== filters.reconciliationStatus) return false;
      return !filters.riskLevel || account.riskLevel === filters.riskLevel;
    });
  }, [accounts, filters]);

  const accountsPagination = useClientPagination(filteredAccounts, 10);

  const summaryCards = useMemo(() => [
    {
      label: "可用资金",
      value: summary ? formatAmount(summary.availableBalance || 0) : "--",
      hint: `${summary?.activeAccountCount || 0} 个启用账户`,
      icon: <IconSafe />,
    },
    {
      label: "净资产",
      value: summary ? formatAmount(summary.netWorth || 0) : "--",
      hint: `资产 ${summary ? formatAmount(summary.totalAssets || 0) : "--"}`,
      icon: <IconCheckCircle />,
    },
    {
      label: "本月净流",
      value: summary ? formatAmount((summary.currentMonthIncome || 0) - (summary.currentMonthExpense || 0)) : "--",
      hint: `流入 ${summary ? formatAmount(summary.currentMonthIncome || 0) : "--"}`,
      icon: <IconSwap />,
    },
    {
      label: "对账风险",
      value: String((summary?.pendingReconciliationCount || 0) + (summary?.highRiskCount || 0)),
      hint: `${summary?.pendingReconciliationCount || 0} 个待对账`,
      icon: <IconExclamationCircle />,
    },
  ], [summary]);

  const accountTypeStats = useMemo(() => {
    const rows = Object.keys(accountTypeMeta).map((type) => {
      const typeAccounts = accounts.filter((account) => account.type === type);
      const balance = typeAccounts.reduce((sum, account) => sum + (account.balance || 0), 0);
      return { type, count: typeAccounts.length, balance };
    });
    return rows.filter((row) => row.count > 0);
  }, [accounts]);

  const updateFilter = (key: keyof AccountFilters, value: string | undefined) => {
    setFilters((current) => ({ ...current, [key]: value || "" }));
  };

  const resetFilters = () => setFilters(initialFilters);

  const openCreate = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({
      type: "bank",
      currency: "CNY",
      balance: 0,
      availableBalance: 0,
      creditLimit: 0,
      frozenAmount: 0,
      includeInNetWorth: "true",
      status: 1,
      openedAt: today(),
      ownerName: isHousehold ? "家庭成员" : "财务负责人",
    });
    setModalVisible(true);
  };

  const openEdit = (account: Account) => {
    setEditingId(account.id);
    form.setFieldsValue({
      ...account,
      includeInNetWorth: String(account.includeInNetWorth),
      accountNo: account.accountNo || undefined,
      openingBank: account.openingBank || undefined,
      ownerName: account.ownerName || undefined,
      purpose: account.purpose || undefined,
      openedAt: account.openedAt || undefined,
    });
    setModalVisible(true);
  };

  const handleSubmit = async (values: AccountFormValues) => {
    await saveAction.run("save", async () => {
      try {
        const payload: CreateAccountDTO = {
          ...values,
          balance: Number(values.balance || 0),
          availableBalance: Number(values.availableBalance ?? values.balance ?? 0),
          creditLimit: Number(values.creditLimit || 0),
          frozenAmount: Number(values.frozenAmount || 0),
          status: Number(values.status ?? 1),
          includeInNetWorth: values.includeInNetWorth !== false && values.includeInNetWorth !== "false",
        };
        if (editingId) {
          await accountApi.update(editingId, payload);
          Message.success("账户已更新");
        } else {
          await accountApi.create(payload);
          Message.success("账户已创建");
        }
        setModalVisible(false);
        form.resetFields();
        setEditingId(null);
        await refreshData(true);
      } catch {
        Message.error("账户保存失败");
      }
    });
  };

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: "确认删除账户",
      content: "只有没有流水的账户才能删除。确定继续吗？",
      onOk: async () => {
        try {
          await accountApi.delete(id);
          Message.success("账户已删除");
          await refreshData(true);
        } catch {
          Message.error("删除失败，账户可能已有流水");
        }
      },
    });
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title={isHousehold ? "家庭账户" : "资金账户"}
        subtitle={isHousehold ? "现金、银行卡、数字钱包、理财、负债和家庭可用资金集中管理" : "现金、银行、信用、负债和可用资金集中管理"}
        icon={<IconSafe />}
        extra={
          <div className="flex items-center gap-2">
            {refreshing && <Tag color="arcoblue">刷新中</Tag>}
            <Button icon={<IconRefresh />} onClick={() => refreshData()}>
              刷新
            </Button>
            <Button type="primary" icon={<IconPlus />} onClick={openCreate}>
              {isHousehold ? "新增家庭账户" : "新增账户"}
            </Button>
          </div>
        }
      />

      <Row gutter={16} className="metric-grid">
        {summaryCards.map((card) => (
          <Col key={card.label} xs={12} md={6}>
            <Card className="metric-card" style={{ borderRadius: 12, minHeight: 132 }}>
              <div className="flex h-full min-h-[92px] flex-col justify-between">
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

      <Card className="filter-card mb-4" style={{ borderRadius: 12 }}>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3 xl:grid-cols-6">
          <Input
            allowClear
            prefix={<IconSearch />}
            placeholder="搜索账户、银行、负责人..."
            value={filters.keyword}
            onChange={(value) => updateFilter("keyword", value)}
          />
          <Select
            allowClear
            placeholder="账户类型"
            value={filters.type || undefined}
            onChange={(value) => updateFilter("type", value)}
          >
            {Object.entries(accountTypeMeta).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select
            allowClear
            placeholder="账户状态"
            value={filters.status || undefined}
            onChange={(value) => updateFilter("status", value)}
          >
            {Object.entries(statusMeta).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select
            allowClear
            placeholder="对账状态"
            value={filters.reconciliationStatus || undefined}
            onChange={(value) => updateFilter("reconciliationStatus", value)}
          >
            {Object.entries(reconciliationMeta).map(([value, meta]) => (
              <Select.Option key={value} value={value}>{meta.label}</Select.Option>
            ))}
          </Select>
          <Select
            allowClear
            placeholder="风险等级"
            value={filters.riskLevel || undefined}
            onChange={(value) => updateFilter("riskLevel", value)}
          >
            {Object.entries(riskText).map(([value, label]) => (
              <Select.Option key={value} value={value}>{label}</Select.Option>
            ))}
          </Select>
          <Button onClick={resetFilters}>重置</Button>
        </div>
      </Card>

      <Card className="mb-6" style={{ borderRadius: 12 }} title="账户结构">
        {initialLoading ? (
          <Skeleton />
        ) : accountTypeStats.length === 0 ? (
          <div className="text-sm" style={{ color: "var(--text-color-3)" }}>暂无账户结构</div>
        ) : (
          <div className="bi-segment-grid bi-segment-accounts grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3">
            {accountTypeStats.map((row) => {
              const meta = accountTypeMeta[row.type] || { label: row.type, icon: <IconSafe />, color: "gray" };
              return (
                <div
                  key={row.type}
                  className="flex min-h-[104px] flex-col justify-between rounded-lg border px-4 py-3"
                  style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
                >
                  <div className="flex items-start justify-between gap-3">
                    <span
                      className="grid h-9 w-9 shrink-0 place-items-center rounded-lg"
                      style={{ backgroundColor: "var(--color-fill-1)", color: "var(--color-primary)" }}
                    >
                      {meta.icon}
                    </span>
                    <Tag size="small" color={meta.color}>{row.count}</Tag>
                  </div>
                  <div className="mt-3 min-w-0">
                    <div className="truncate text-sm font-medium" style={{ color: "var(--text-color-2)" }}>
                      {meta.label}
                    </div>
                    <div className="mt-1 truncate text-base font-semibold" style={{ color: "var(--text-color-1)" }}>
                      {formatAmount(row.balance)}
                    </div>
                    <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>账户余额</div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Card>

      <Card style={{ borderRadius: 12 }} title="账户台账">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1080px] table-fixed border-collapse text-sm">
            <colgroup>
              <col style={{ width: "22%" }} />
              <col style={{ width: "10%" }} />
              <col style={{ width: "14%" }} />
              <col style={{ width: "15%" }} />
              <col style={{ width: "15%" }} />
              <col style={{ width: "10%" }} />
              <col style={{ width: "7%" }} />
              <col style={{ width: "7%" }} />
            </colgroup>
            <thead>
              <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                <th className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>账户</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>类型</th>
                <th className="px-4 py-3 text-right font-medium" style={{ color: "var(--text-color-2)" }}>余额/可用</th>
                <th className="px-4 py-3 text-right font-medium" style={{ color: "var(--text-color-2)" }}>本月流入/流出</th>
                <th className="px-4 py-3 text-left font-medium" style={{ color: "var(--text-color-2)" }}>用途</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>对账</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>风险</th>
                <th className="px-4 py-3 text-center font-medium" style={{ color: "var(--text-color-2)" }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {initialLoading ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12"><Skeleton /></td>
                </tr>
              ) : filteredAccounts.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12">
                    <Empty description="暂无匹配账户" />
                  </td>
                </tr>
              ) : accountsPagination.pagedData.map((account) => {
                const typeMeta = accountTypeMeta[account.type] || { label: account.type, icon: <IconSafe />, color: "gray" };
                const status = statusMeta[account.status] || { label: "未知", color: "gray" };
                const reconciliation = reconciliationMeta[account.reconciliationStatus] || { label: account.reconciliationStatus, color: "gray" };
                const balanceType = account.balance >= 0 && account.type !== "credit" && account.type !== "debt" ? 1 : 2;
                return (
                  <tr
                    key={account.id}
                    className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                    style={{ borderColor: "var(--border-color-light)" }}
                  >
                    <td className="px-4 py-4 align-middle">
                      <div className="flex items-start gap-3">
                        <span
                          className="grid h-9 w-9 shrink-0 place-items-center rounded-lg"
                          style={{ backgroundColor: "var(--color-fill-1)", color: "var(--color-primary)" }}
                        >
                          {typeMeta.icon}
                        </span>
                        <div className="min-w-0">
                          <div className="font-semibold truncate" style={{ color: "var(--text-color-1)" }}>{account.name}</div>
                          <div className="mt-1 flex items-center gap-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                            <span>{account.bank || account.openingBank || "未设置开户行"}</span>
                            <Tag size="small" color={status.color}>{status.label}</Tag>
                          </div>
                          <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                            {account.accountNo ? `尾号 ${account.accountNo}` : "账号待补充"} · {account.ownerName || "负责人待补充"}
                          </div>
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <Tag color={typeMeta.color}>{typeMeta.label}</Tag>
                      <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{account.currency || "CNY"}</div>
                    </td>
                    <td className="px-4 py-4 text-right align-middle whitespace-nowrap">
                      <AmountDisplay amount={account.balance} type={balanceType} size="small" />
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        可用 {formatAmount(account.availableBalance || 0)}
                      </div>
                      {account.frozenAmount > 0 && (
                        <div className="mt-1 text-xs" style={{ color: "var(--color-warning)" }}>
                          冻结 {formatAmount(account.frozenAmount)}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-4 text-right align-middle whitespace-nowrap">
                      <div><AmountDisplay amount={account.monthlyIncome || 0} type={1} size="small" /></div>
                      <div className="mt-1"><AmountDisplay amount={account.monthlyExpense || 0} type={2} size="small" /></div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        {account.transactionCount || 0} 笔 · {account.lastTransactionDate ? formatDate(account.lastTransactionDate) : "无流水"}
                      </div>
                    </td>
                    <td className="px-4 py-4 align-middle">
                      <div className="line-clamp-2" style={{ color: "var(--text-color-2)" }}>{account.purpose || "用途待补充"}</div>
                      {account.creditLimit > 0 && (
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                          授信 {formatAmount(account.creditLimit)}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <Tag color={reconciliation.color}>{reconciliation.label}</Tag>
                      <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                        {account.lastReconciledAt ? formatDate(account.lastReconciledAt) : "未对账"}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <RiskBadge level={(account.riskLevel || "low") as AccountRiskLevel} text={riskText[account.riskLevel] || "风险低"} />
                    </td>
                    <td className="px-4 py-4 text-center align-middle">
                      <div className="flex justify-center gap-1">
                        <Button type="text" size="mini" title="余额对账" icon={<IconCheckCircle />} onClick={() => setReconcilingAccount(account)} />
                        <Button type="text" size="mini" title="编辑" icon={<IconEdit />} onClick={() => openEdit(account)} />
                        <Button type="text" size="mini" title="删除" status="danger" icon={<IconDelete />} onClick={() => handleDelete(account.id)} />
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <AppPagination
          current={accountsPagination.page}
          pageSize={accountsPagination.pageSize}
          total={accountsPagination.total}
          pageSizeOptions={[10, 20, 50, 100]}
          onChange={accountsPagination.handleChange}
        />
      </Card>

      <Modal
        title={editingId ? "编辑资金账户" : "新增资金账户"}
        visible={modalVisible}
        onCancel={() => {
          if (!saving) setModalVisible(false);
        }}
        onOk={() => form.submit()}
        confirmLoading={saving}
        maskClosable={!saving}
        closable={!saving}
        style={{ width: 760 }}
      >
        <Form form={form} layout="vertical" onSubmit={handleSubmit}>
          <div className="grid grid-cols-1 gap-x-4 md:grid-cols-2">
            <FormItem label="账户名称" field="name" rules={[{ required: true, message: "请输入账户名称" }]}>
              <Input placeholder="例如：公司基本户" />
            </FormItem>
            <FormItem label="账户类型" field="type" rules={[{ required: true, message: "请选择账户类型" }]}>
              <Select placeholder="选择类型">
                {Object.entries(accountTypeMeta).map(([value, meta]) => (
                  <Select.Option key={value} value={value}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="账户子类" field="subType">
              <Input placeholder="对公账户、备用金、信用卡..." />
            </FormItem>
            <FormItem label="币种" field="currency">
              <Select>
                <Select.Option value="CNY">CNY</Select.Option>
                <Select.Option value="USD">USD</Select.Option>
                <Select.Option value="HKD">HKD</Select.Option>
              </Select>
            </FormItem>
            <FormItem label="银行/机构" field="bank">
              <Input placeholder="招商银行、支付宝、现金..." />
            </FormItem>
            <FormItem label="开户地址" field="openingBank">
              <Input placeholder="开户地址或平台主体" />
            </FormItem>
            <FormItem label="账号尾号" field="accountNo">
              <Input placeholder="只建议记录尾号" />
            </FormItem>
            <FormItem label="负责人" field="ownerName">
              <Input placeholder="财务负责人" />
            </FormItem>
            <FormItem label="账面余额" field="balance" rules={[{ required: true, message: "请输入余额" }]}>
              <InputNumber precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="可用余额" field="availableBalance">
              <InputNumber precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="授信额度" field="creditLimit">
              <InputNumber min={0} precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="冻结金额" field="frozenAmount">
              <InputNumber min={0} precision={2} placeholder="0.00" />
            </FormItem>
            <FormItem label="开户日期" field="openedAt">
              <DatePicker format="YYYY-MM-DD" className="w-full" allowClear />
            </FormItem>
            <FormItem label="账户状态" field="status">
              <Select>
                {Object.entries(statusMeta).map(([value, meta]) => (
                  <Select.Option key={value} value={Number(value)}>{meta.label}</Select.Option>
                ))}
              </Select>
            </FormItem>
            <FormItem label="纳入净资产" field="includeInNetWorth">
              <Select>
                <Select.Option value="true">纳入</Select.Option>
                <Select.Option value="false">不纳入</Select.Option>
              </Select>
            </FormItem>
          </div>
          <FormItem label="账户用途" field="purpose">
            <Input.TextArea rows={3} placeholder="例如：客户回款、供应商付款和税费缴纳" />
          </FormItem>
        </Form>
      </Modal>
      <AccountReconciliationModal
        account={reconcilingAccount}
        onClose={() => setReconcilingAccount(null)}
        onSuccess={() => void refreshData(true)}
      />
    </div>
  );
}
