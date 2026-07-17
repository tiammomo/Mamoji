"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Alert, Button, Card, Empty, Grid, Progress, Skeleton, Tag } from "@arco-design/web-react";
import {
  IconCalendar,
  IconCheckCircle,
  IconDashboard,
  IconExclamationCircle,
  IconFile,
  IconRefresh,
  IconRight,
  IconSafe,
  IconSwap,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import AmountDisplay from "@/components/common/AmountDisplay";
import PageHeader from "@/components/common/PageHeader";
import { workspaceApi } from "@/lib/api/workspace";
import { useAppStore } from "@/lib/stores/appStore";
import { formatDate } from "@/lib/utils/format";
import type {
  WorkspaceMetrics,
  WorkspaceSeverity,
  WorkspaceView,
} from "@/lib/types";

const { Row, Col } = Grid;

const severityMeta: Record<WorkspaceSeverity, {
  label: string;
  range: string;
  summary: string;
  color: string;
  accent: string;
  background: string;
}> = {
  success: {
    label: "健康",
    range: "85–100 分",
    summary: "整体经营状态稳定",
    color: "green",
    accent: "#10b981",
    background: "rgba(16,185,129,.1)",
  },
  notice: {
    label: "需关注",
    range: "70–84 分",
    summary: "整体可控，但仍有事项需要处理",
    color: "arcoblue",
    accent: "#3b82f6",
    background: "rgba(59,130,246,.1)",
  },
  warning: {
    label: "预警",
    range: "55–69 分",
    summary: "多项指标偏离，建议尽快处理",
    color: "orange",
    accent: "#f59e0b",
    background: "rgba(245,158,11,.1)",
  },
  danger: {
    label: "高风险",
    range: "0–54 分",
    summary: "存在明显风险，请优先处置",
    color: "red",
    accent: "#ef4444",
    background: "rgba(239,68,68,.1)",
  },
};

type MetricCard = {
  key: keyof WorkspaceMetrics;
  label: string;
  icon: ReactNode;
  tone: string;
  type?: 1 | 2;
};

const metricCards: MetricCard[] = [
  { key: "monthlyIncome", label: "本月收入", icon: <IconDashboard />, tone: "#10b981", type: 1 },
  { key: "monthlyExpense", label: "本月成本", icon: <IconSwap />, tone: "#ef4444", type: 2 },
  { key: "monthlyProfit", label: "本月经营净额", icon: <IconDashboard />, tone: "#6366f1" },
  { key: "availableCash", label: "可用资金", icon: <IconSafe />, tone: "#3b82f6" },
];

const moduleIcon: Record<string, ReactNode> = {
  operations: <IconDashboard />,
  budget: <IconCalendar />,
  finance: <IconSafe />,
  workflow: <IconCheckCircle />,
};

export default function DashboardPage() {
  const router = useRouter();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      setError(false);
      try {
        const response = await workspaceApi.view();
        if (!cancelled) setWorkspace(response.data);
      } catch {
        if (!cancelled) {
          setWorkspace(null);
          setError(true);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [activeCompanyId, reloadKey]);

  const quickActions = useMemo(() => {
    const capabilities = new Set(workspace?.capabilities || []);
    return [
      capabilities.has("workflow") ? { label: "处理审批", path: "/approvals", icon: <IconCheckCircle /> } : null,
      capabilities.has("operations") ? { label: "新增流水", path: "/transactions?action=new", icon: <IconSwap /> } : null,
      capabilities.has("budget") ? { label: "预算管理", path: "/budgets", icon: <IconCalendar /> } : null,
      capabilities.has("finance") ? { label: "票据归档", path: "/receipts", icon: <IconFile /> } : null,
    ].filter((item): item is NonNullable<typeof item> => Boolean(item));
  }, [workspace?.capabilities]);

  if (loading && !workspace) {
    return (
      <div className="page-shell space-y-5">
        <Skeleton animation text={{ rows: 2 }} />
        <Row gutter={[16, 16]}>{[0, 1, 2, 3].map((key) => <Col key={key} xs={24} sm={12} xl={6}><Card><Skeleton animation text={{ rows: 3 }} /></Card></Col>)}</Row>
        <Card><Skeleton animation text={{ rows: 8 }} /></Card>
      </div>
    );
  }

  return (
    <div className="page-shell space-y-5">
      <PageHeader
        eyebrow="INTERNAL OPERATIONS"
        title={workspace?.companyName || "企业经营工作台"}
        subtitle={workspace ? `${workspace.period} · 预算、审批、资金与凭证统一视图` : "预算、审批、资金与凭证统一视图"}
        icon={<IconDashboard />}
        meta={workspace ? <Tag color={severityMeta[workspace.severity].color}>{severityMeta[workspace.severity].label}</Tag> : undefined}
        extra={(
          <Button icon={<IconRefresh />} loading={loading} onClick={() => setReloadKey((value) => value + 1)}>
            刷新
          </Button>
        )}
      />

      {error && (
        <Alert
          type="error"
          showIcon
          title="工作台暂时无法加载"
          content="聚合数据请求失败，请稍后重试。其他业务页面不受影响。"
          action={<Button size="small" onClick={() => setReloadKey((value) => value + 1)}>重试</Button>}
        />
      )}

      {workspace && (
        <>
          <Row gutter={[16, 16]}>
            {metricCards.map((card) => {
              const value = workspace.metrics[card.key];
              return (
                <Col key={card.key} xs={24} sm={12} xl={6}>
                  <Card className="h-full" bordered>
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="text-sm" style={{ color: "var(--text-color-3)" }}>{card.label}</div>
                        <div className="mt-3 min-h-8">
                          {typeof value === "number"
                            ? <AmountDisplay amount={value} type={card.type} size="large" />
                            : <span className="text-2xl font-bold" style={{ color: "var(--text-color-3)" }}>—</span>}
                        </div>
                        <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                          {typeof value === "number" ? "当前公司口径" : "当前角色无查看权限"}
                        </div>
                      </div>
                      <span className="grid h-11 w-11 shrink-0 place-items-center rounded-2xl text-xl" style={{ color: card.tone, backgroundColor: `${card.tone}18` }}>
                        {card.icon}
                      </span>
                    </div>
                  </Card>
                </Col>
              );
            })}
          </Row>

          <Row gutter={[16, 16]}>
            <Col xs={24} xl={9}>
              <Card className="h-full" title="经营健康度">
                <div className="grid min-h-[210px] grid-cols-1 items-center gap-6 py-3 sm:grid-cols-[160px_1fr]">
                  <div
                    className="flex justify-center"
                    role="img"
                    aria-label={`经营健康度 ${workspace.score} 分，${severityMeta[workspace.severity].label}`}
                  >
                    <Progress
                      type="circle"
                      percent={workspace.score}
                      width={136}
                      color={severityMeta[workspace.severity].accent}
                      trailColor="var(--border-color-light)"
                      formatText={() => `${workspace.score}分`}
                    />
                  </div>
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <Tag color={severityMeta[workspace.severity].color}>
                        {severityMeta[workspace.severity].label}
                      </Tag>
                      <span className="text-xs" style={{ color: "var(--text-color-3)" }}>
                        {severityMeta[workspace.severity].range}
                      </span>
                    </div>
                    <div className="mt-3 text-base font-semibold" style={{ color: "var(--text-color-1)" }}>
                      {severityMeta[workspace.severity].summary}
                    </div>
                    <p className="mt-2 text-sm leading-6" style={{ color: "var(--text-color-3)" }}>
                      {workspace.severity === "success"
                        ? "当前已进入健康区间，请继续保持关键指标稳定。"
                        : `距健康区间还差 ${Math.max(85 - workspace.score, 0)} 分，建议从右侧优先事项开始处理。`}
                    </p>
                    <div
                      className="mt-4 rounded-xl border px-3 py-2.5 text-xs leading-5"
                      style={{
                        borderColor: "var(--border-color-light)",
                        color: "var(--text-color-3)",
                        backgroundColor: "var(--bg-color-page)",
                      }}
                    >
                      基于当前权限可见的 {workspace.modules.length} 个业务模块计算
                      {workspace.priorityActions.filter((action) => action.severity !== "success").length > 0
                        ? ` · ${workspace.priorityActions.filter((action) => action.severity !== "success").length} 项优先事项待处理`
                        : " · 暂无优先风险事项"}
                    </div>
                  </div>
                </div>
              </Card>
            </Col>
            <Col xs={24} xl={15}>
              <Card className="h-full" title="优先处理" extra={<Button type="text" onClick={() => router.push("/approvals")}>查看待办 <IconRight /></Button>}>
                <div className="space-y-3">
                  {workspace.priorityActions.map((action) => {
                    const meta = severityMeta[action.severity];
                    return (
                      <button
                        key={action.code}
                        type="button"
                        className="flex w-full cursor-pointer items-center gap-3 rounded-2xl border p-3 text-left transition-transform hover:-translate-y-px"
                        style={{ borderColor: "var(--border-color-light)", backgroundColor: meta.background }}
                        onClick={() => router.push(action.path)}
                      >
                        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl" style={{ color: meta.accent, backgroundColor: "var(--bg-color-card)" }}>
                          {action.severity === "success" ? <IconCheckCircle /> : <IconExclamationCircle />}
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>{action.title}</span>
                          <span className="mt-1 block text-xs" style={{ color: "var(--text-color-3)" }}>{action.detail}</span>
                        </span>
                        <IconRight style={{ color: "var(--text-color-3)" }} />
                      </button>
                    );
                  })}
                </div>
              </Card>
            </Col>
          </Row>

          <Card title="模块健康度">
            {workspace.modules.length === 0 ? (
              <Empty description="当前角色仅开放个人待办" />
            ) : (
              <Row gutter={[16, 16]}>
                {workspace.modules.map((module) => {
                  const meta = severityMeta[module.severity];
                  return (
                    <Col key={module.key} xs={24} md={12} xl={6}>
                      <button
                        type="button"
                        className="h-full w-full cursor-pointer rounded-2xl border bg-transparent p-4 text-left"
                        style={{ borderColor: "var(--border-color-light)" }}
                        onClick={() => router.push(module.path)}
                      >
                        <div className="flex items-center justify-between gap-3">
                          <span className="grid h-9 w-9 place-items-center rounded-xl" style={{ color: meta.accent, backgroundColor: meta.background }}>
                            {moduleIcon[module.key] || <IconDashboard />}
                          </span>
                          <span className="text-2xl font-bold" style={{ color: meta.accent }}>{module.score}</span>
                        </div>
                        <div className="mt-4 text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>{module.title}</div>
                        <div className="mt-1 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{module.detail}</div>
                        <Progress className="mt-3" percent={module.score} showText={false} color={meta.accent} />
                      </button>
                    </Col>
                  );
                })}
              </Row>
            )}
          </Card>

          <Row gutter={[16, 16]}>
            <Col xs={24} xl={12}>
              <Card className="h-full" title="日清检查">
                <div className="space-y-2">
                  {workspace.dailyChecks.map((check) => (
                    <button
                      key={check.key}
                      type="button"
                      className="flex w-full cursor-pointer items-center gap-3 rounded-xl border-0 bg-transparent px-2 py-3 text-left hover:bg-black/[0.025] dark:hover:bg-white/[0.04]"
                      onClick={() => router.push(check.path)}
                    >
                      <span style={{ color: check.done ? "#10b981" : "#f59e0b" }}>
                        {check.done ? <IconCheckCircle /> : <IconExclamationCircle />}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{check.label}</span>
                        <span className="block text-xs" style={{ color: "var(--text-color-3)" }}>{check.detail}</span>
                      </span>
                      <IconRight style={{ color: "var(--text-color-3)" }} />
                    </button>
                  ))}
                </div>
              </Card>
            </Col>
            <Col xs={24} xl={12}>
              <Card className="h-full" title="预算风险" extra={<Button type="text" onClick={() => router.push("/budgets")}>全部预算 <IconRight /></Button>}>
                {workspace.budgetRisks.length === 0 ? (
                  <Empty description="暂无预算预警" />
                ) : (
                  <div className="space-y-3">
                    {workspace.budgetRisks.slice(0, 5).map((budget) => (
                      <button key={budget.id} type="button" className="w-full cursor-pointer rounded-xl border bg-transparent p-3 text-left" style={{ borderColor: "var(--border-color-light)" }} onClick={() => router.push("/budgets")}>
                        <div className="flex items-center justify-between gap-3">
                          <span className="truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{budget.name}</span>
                          <Tag color={budget.riskLevel === "critical" ? "red" : "orange"}>{(budget.usageRate * 100).toFixed(1)}%</Tag>
                        </div>
                        <Progress className="mt-2" percent={Math.min(budget.usageRate * 100, 100)} showText={false} color={budget.riskLevel === "critical" ? "#ef4444" : "#f59e0b"} />
                      </button>
                    ))}
                  </div>
                )}
              </Card>
            </Col>
          </Row>

          <Row gutter={[16, 16]}>
            <Col xs={24} xl={16}>
              <Card title="近期经营流水" extra={<Button type="text" onClick={() => router.push("/transactions")}>查看全部 <IconRight /></Button>}>
                {workspace.recentTransactions.length === 0 ? (
                  <Empty description="暂无可见流水" />
                ) : (
                  <div className="divide-y" style={{ borderColor: "var(--border-color-light)" }}>
                    {workspace.recentTransactions.map((transaction) => (
                      <button
                        key={transaction.id}
                        type="button"
                        className="flex w-full cursor-pointer items-center gap-3 border-0 bg-transparent py-3 text-left"
                        onClick={() => router.push("/transactions")}
                      >
                        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl" style={{ color: transaction.type === 1 ? "#10b981" : "#ef4444", backgroundColor: transaction.type === 1 ? "rgba(16,185,129,.1)" : "rgba(239,68,68,.1)" }}>
                          <IconSwap />
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{transaction.note || transaction.categoryName}</span>
                          <span className="block text-xs" style={{ color: "var(--text-color-3)" }}>{transaction.categoryName} · {transaction.accountName} · {formatDate(transaction.date)}</span>
                        </span>
                        <AmountDisplay amount={transaction.amount} type={transaction.type === 1 || transaction.type === 3 ? 1 : 2} showSign size="small" />
                      </button>
                    ))}
                  </div>
                )}
              </Card>
            </Col>
            <Col xs={24} xl={8}>
              <Card className="h-full" title="近期事项">
                {workspace.upcomingItems.length === 0 ? (
                  <Empty description="未来 14 天暂无周期事项" />
                ) : (
                  <div className="space-y-2">
                    {workspace.upcomingItems.map((item) => (
                      <button key={item.id} type="button" className="flex w-full cursor-pointer items-center gap-3 rounded-xl border-0 bg-transparent p-2 text-left" onClick={() => router.push(item.path)}>
                        <IconCalendar style={{ color: item.overdue ? "#ef4444" : "#6366f1" }} />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm" style={{ color: "var(--text-color-1)" }}>{item.title}</span>
                          <span className="block text-xs" style={{ color: item.overdue ? "#ef4444" : "var(--text-color-3)" }}>{item.overdue ? "已逾期" : "计划"} · {formatDate(item.dueDate)}</span>
                        </span>
                      </button>
                    ))}
                  </div>
                )}
              </Card>
            </Col>
          </Row>

          {quickActions.length > 0 && (
            <Card title="快捷操作">
              <div className="flex flex-wrap gap-3">
                {quickActions.map((action) => (
                  <Button key={action.path} icon={action.icon} onClick={() => router.push(action.path)}>{action.label}</Button>
                ))}
              </div>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
