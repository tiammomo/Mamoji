"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Alert, Button, Card, DatePicker, Empty, Message, Skeleton, Tag } from "@arco-design/web-react";
import {
  IconBranch,
  IconCalendar,
  IconDashboard,
  IconExclamationCircle,
  IconIdcard,
  IconRefresh,
  IconRight,
  IconSafe,
  IconUserGroup,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import AmountDisplay from "@/components/common/AmountDisplay";
import PageHeader from "@/components/common/PageHeader";
import { workforceApi } from "@/lib/api/workforce";
import { useAppStore } from "@/lib/stores/appStore";
import { useAuthStore } from "@/lib/stores/authStore";
import type { WorkforceAttentionItem, WorkforceCostView } from "@/lib/types";
import { formatAmount, formatPercent } from "@/lib/utils/format";

const MonthPicker = DatePicker.MonthPicker;

const currentPeriod = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
};

const clampPercent = (ratio: number) => Math.max(0, Math.min(ratio * 100, 100));

const sourceMeta = (view: WorkforceCostView) => {
  if (view.source === "employee_estimate") {
    return { label: "实时估算", color: "orange", detail: "来自员工档案的当前薪酬口径" };
  }
  if (view.payrollRunStatus === "closed") {
    return { label: "正式月结", color: "green", detail: "来自已锁定的薪酬快照" };
  }
  return { label: "月结草稿", color: "arcoblue", detail: "来自尚未锁定的薪酬批次" };
};

const attentionMeta: Record<WorkforceAttentionItem["severity"], { color: string; label: string }> = {
  info: { color: "arcoblue", label: "关注" },
  warning: { color: "orange", label: "待处理" },
  critical: { color: "red", label: "风险" },
};

function MetricCard({
  title,
  value,
  caption,
  icon,
  accent = "var(--color-primary)",
}: {
  title: string;
  value: ReactNode;
  caption: string;
  icon: ReactNode;
  accent?: string;
}) {
  return (
    <Card className="metric-card" style={{ borderRadius: 12, minHeight: 136 }}>
      <div className="flex h-full min-h-[96px] flex-col justify-between">
        <div className="flex items-start justify-between gap-3">
          <span className="text-sm" style={{ color: "var(--text-color-3)" }}>{title}</span>
          <span className="inline-flex text-xl" style={{ color: accent }}>{icon}</span>
        </div>
        <div className="mt-3">{value}</div>
        <div className="mt-2 truncate text-xs" style={{ color: "var(--text-color-3)" }}>{caption}</div>
      </div>
    </Card>
  );
}

export default function WorkforceCostPage() {
  const router = useRouter();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const user = useAuthStore((state) => state.user);
  const accessContext = useAuthStore((state) => state.accessContext);
  const [period, setPeriod] = useState(currentPeriod);
  const [view, setView] = useState<WorkforceCostView | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState(false);

  const canManagePayroll = user?.role === 1
    || Boolean(accessContext?.permissions.includes("workforce.cost.manage"));

  useEffect(() => {
    let cancelled = false;

    void workforceApi.view({ companyId: activeCompanyId || undefined, period })
      .then((response) => {
        if (!cancelled) setView(response.data);
      })
      .catch(() => {
        if (!cancelled) {
          setView(null);
          setLoadError(true);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [activeCompanyId, period]);

  const refresh = async () => {
    setRefreshing(true);
    try {
      const response = await workforceApi.view({ companyId: activeCompanyId || undefined, period });
      setView(response.data);
      setLoadError(false);
      Message.success("人力成本数据已刷新");
    } catch {
      setLoadError(true);
      Message.error("人力成本数据刷新失败");
    } finally {
      setRefreshing(false);
    }
  };

  const composition = useMemo(() => {
    if (!view) return [];
    return [
      { key: "salary", label: "基本工资", value: view.costs.salary, color: "#4f6bdc" },
      { key: "overtime", label: "加班工资", value: view.costs.overtime, color: "#7c5cc4" },
      { key: "social", label: "企业社保", value: view.costs.employerSocial, color: "#0ea5a8" },
      { key: "housing", label: "企业公积金", value: view.costs.employerHousing, color: "#d18b32" },
      { key: "other", label: "其他雇主成本", value: view.costs.other, color: "#8891a5" },
    ];
  }, [view]);

  const maxTrend = Math.max(0, ...(view?.trend.map((point) => Number(point.total || 0)) || []));
  const meta = view ? sourceMeta(view) : null;

  return (
    <div className="mx-auto max-w-[1600px] animate-fade-in">
      <PageHeader
        title="人力成本"
        subtitle={view
          ? `${view.companyName} · 工资、企业社保公积金、部门预算与经营支出的统一成本口径`
          : "工资、企业社保公积金、部门预算与经营支出的统一成本口径"}
        icon={<IconDashboard />}
        extra={
          <div className="flex flex-wrap items-center justify-end gap-2">
            <MonthPicker
              aria-label="成本月份"
              value={period}
              format="YYYY-MM"
              allowClear={false}
              onChange={(value) => value && setPeriod(value)}
              style={{ width: 132 }}
            />
            <Button icon={<IconRefresh />} loading={refreshing} onClick={() => void refresh()}>
              刷新
            </Button>
            {canManagePayroll && (
              <Button type="primary" icon={<IconIdcard />} onClick={() => router.push("/admin/compensation")}>
                薪酬月结
              </Button>
            )}
          </div>
        }
      />

      {loadError && (
        <Alert
          className="mb-6"
          type="error"
          title="人力成本数据未能加载"
          content="请检查当前公司权限和后端服务状态；缺失数据不会被当作 0 展示。"
          action={<Button size="small" icon={<IconRefresh />} loading={refreshing} onClick={() => void refresh()}>重新加载</Button>}
        />
      )}

      {loading && !view ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-5">
          {Array.from({ length: 5 }).map((_, index) => (
            <Card key={index} style={{ borderRadius: 12, minHeight: 136 }}>
              <Skeleton animation text={{ rows: 3 }} />
            </Card>
          ))}
        </div>
      ) : view ? (
        <>
          <Card className="mb-6" style={{ borderRadius: 12 }}>
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div className="flex flex-wrap items-center gap-3">
                <Tag color={meta?.color}>{meta?.label}</Tag>
                <span className="text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{view.period}</span>
                <span className="text-sm" style={{ color: "var(--text-color-3)" }}>{meta?.detail}</span>
              </div>
              <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-sm" style={{ color: "var(--text-color-2)" }}>
                <span>计薪人数 <strong>{view.headcount.costed}</strong></span>
                <span>在岗 <strong>{view.headcount.active + view.headcount.probation}</strong></span>
                <span>待入职 <strong>{view.headcount.onboarding}</strong></span>
                <span>本月离职 <strong>{view.headcount.departedThisMonth}</strong></span>
              </div>
            </div>
          </Card>

          <div className="metric-grid metric-wrap-until-2xl mb-6 grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-5">
            <MetricCard
              title="总人力成本"
              value={<AmountDisplay amount={view.costs.total} type={2} size="large" />}
              caption={`${view.headcount.costed} 人计入本期成本`}
              icon={<IconUserGroup />}
              accent="var(--color-danger)"
            />
            <MetricCard
              title="人均成本"
              value={<AmountDisplay amount={view.costs.average} size="large" />}
              caption="总人力成本 ÷ 计薪人数"
              icon={<IconUserGroup />}
            />
            <MetricCard
              title="工资成本"
              value={<AmountDisplay amount={view.costs.salary + view.costs.overtime} size="large" />}
              caption={`其中加班 ${formatAmount(view.costs.overtime)}`}
              icon={<IconIdcard />}
            />
            <MetricCard
              title="企业承担缴费"
              value={<AmountDisplay amount={view.costs.employerSocial + view.costs.employerHousing} size="large" />}
              caption="企业社保 + 企业公积金"
              icon={<IconSafe />}
              accent="var(--color-success)"
            />
            <MetricCard
              title="经营支出占比"
              value={<span className="text-2xl font-bold" style={{ color: "var(--text-color-1)" }}>{formatPercent(view.costs.operatingExpenseShare)}</span>}
              caption={`同期经营支出 ${formatAmount(view.costs.operatingExpense)}`}
              icon={<IconDashboard />}
              accent={view.costs.operatingExpenseShare > 0.6 ? "var(--color-danger)" : "var(--color-primary)"}
            />
          </div>

          <div className="mb-6 grid grid-cols-1 gap-4 xl:grid-cols-3">
            <Card title="成本构成" style={{ borderRadius: 12 }} className="xl:col-span-2">
              <div className="space-y-5">
                {composition.map((item) => {
                  const ratio = view.costs.total > 0 ? item.value / view.costs.total : 0;
                  return (
                    <div key={item.key}>
                      <div className="mb-2 flex items-center justify-between gap-4 text-sm">
                        <div className="flex items-center gap-2">
                          <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: item.color }} />
                          <span style={{ color: "var(--text-color-2)" }}>{item.label}</span>
                        </div>
                        <div className="text-right">
                          <span className="font-medium" style={{ color: "var(--text-color-1)" }}>{formatAmount(item.value)}</span>
                          <span className="ml-3 text-xs" style={{ color: "var(--text-color-3)" }}>{formatPercent(ratio)}</span>
                        </div>
                      </div>
                      <div className="h-2 overflow-hidden rounded-full" style={{ background: "var(--color-fill-2)" }}>
                        <div className="h-full rounded-full transition-all" style={{ width: `${clampPercent(ratio)}%`, backgroundColor: item.color }} />
                      </div>
                    </div>
                  );
                })}
              </div>
            </Card>

            <Card title="人员状态" style={{ borderRadius: 12 }}>
              <div className="grid grid-cols-2 gap-3">
                {[
                  ["正式在岗", view.headcount.active, "var(--color-success)"],
                  ["试用期", view.headcount.probation, "var(--color-primary)"],
                  ["待入职", view.headcount.onboarding, "var(--color-warning)"],
                  ["本月离职", view.headcount.departedThisMonth, "var(--color-danger)"],
                ].map(([label, value, color]) => (
                  <div key={String(label)} className="rounded-xl p-4" style={{ background: "var(--color-fill-1)" }}>
                    <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{label}</div>
                    <div className="mt-2 text-2xl font-bold" style={{ color: String(color) }}>{Number(value)}</div>
                  </div>
                ))}
              </div>
              <Button className="mt-4 w-full" icon={<IconBranch />} onClick={() => router.push("/hr/organization")}>
                查看组织人员
              </Button>
            </Card>
          </div>

          <div className="mb-6 grid grid-cols-1 gap-4 xl:grid-cols-3">
            <Card title="近六期成本趋势" style={{ borderRadius: 12 }} className="xl:col-span-2">
              {view.trend.length === 0 ? (
                <Empty description="生成首个月结批次后将形成趋势" />
              ) : (
                <div className="flex h-[230px] items-end gap-3 overflow-x-auto px-2 pt-8">
                  {view.trend.map((point) => {
                    const height = maxTrend > 0 ? Math.max(10, (point.total / maxTrend) * 100) : 10;
                    return (
                      <div key={`${point.period}-${point.status}`} className="flex min-w-[74px] flex-1 flex-col items-center justify-end">
                        <div className="mb-2 whitespace-nowrap text-xs font-medium" style={{ color: "var(--text-color-2)" }}>
                          {formatAmount(point.total)}
                        </div>
                        <div
                          className="w-full max-w-[62px] rounded-t-lg"
                          style={{
                            height: `${height}%`,
                            minHeight: 12,
                            background: point.status === "estimate"
                              ? "linear-gradient(180deg, #d18b32, #e5b568)"
                              : "linear-gradient(180deg, #4f6bdc, #7c5cc4)",
                          }}
                          role="img"
                          aria-label={`${point.period} 人力成本 ${formatAmount(point.total)}`}
                        />
                        <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{point.period.slice(2)}</div>
                        <div className="mt-1 text-[11px]" style={{ color: "var(--text-color-4)" }}>{point.headcount} 人</div>
                      </div>
                    );
                  })}
                </div>
              )}
            </Card>

            <Card title="本期关注" style={{ borderRadius: 12 }}>
              {view.attentionItems.length === 0 ? (
                <div className="flex min-h-[180px] flex-col items-center justify-center text-center">
                  <IconSafe className="mb-3 text-3xl" style={{ color: "var(--color-success)" }} />
                  <div className="font-medium" style={{ color: "var(--text-color-1)" }}>暂无待处理事项</div>
                  <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>当前成本口径和人员状态正常</div>
                </div>
              ) : (
                <div className="space-y-3">
                  {view.attentionItems.map((item) => (
                    <button
                      key={item.code}
                      type="button"
                      className="flex w-full cursor-pointer items-start gap-3 rounded-xl border-0 p-3 text-left transition-colors hover:brightness-[0.98]"
                      style={{ background: "var(--color-fill-1)" }}
                      onClick={() => router.push(item.path)}
                    >
                      <IconExclamationCircle className="mt-0.5 shrink-0" style={{ color: item.severity === "critical" ? "var(--color-danger)" : "var(--color-warning)" }} />
                      <span className="min-w-0 flex-1">
                        <span className="flex items-center justify-between gap-2">
                          <span className="text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{item.title}</span>
                          <Tag size="small" color={attentionMeta[item.severity].color}>{attentionMeta[item.severity].label}</Tag>
                        </span>
                        <span className="mt-1 block text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{item.detail}</span>
                      </span>
                      <IconRight className="mt-1 shrink-0" style={{ color: "var(--text-color-4)" }} />
                    </button>
                  ))}
                </div>
              )}
            </Card>
          </div>

          <Card title="部门人力成本" style={{ borderRadius: 12 }}>
            {view.departments.length === 0 ? (
              <Empty description="暂无计薪部门数据" />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[980px] border-collapse text-sm">
                  <thead>
                    <tr style={{ color: "var(--text-color-3)", borderBottom: "1px solid var(--border-color-light)" }}>
                      <th className="px-3 py-3 text-left font-medium">部门</th>
                      <th className="px-3 py-3 text-right font-medium">计薪人数</th>
                      <th className="px-3 py-3 text-right font-medium">人力成本</th>
                      <th className="px-3 py-3 text-right font-medium">人均成本</th>
                      <th className="px-3 py-3 text-right font-medium">公司占比</th>
                      <th className="px-3 py-3 text-right font-medium">部门预算</th>
                      <th className="px-3 py-3 text-left font-medium">预算使用</th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.departments.map((department) => {
                      const budgetAvailable = department.budget > 0;
                      const budgetOver = budgetAvailable && department.budgetVariance > 0;
                      return (
                        <tr key={department.departmentId ?? department.departmentName} style={{ borderBottom: "1px solid var(--border-color-light)" }}>
                          <td className="px-3 py-4">
                            <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{department.departmentName}</div>
                            <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                              工资 {formatAmount(department.salary + department.overtime)}
                            </div>
                          </td>
                          <td className="px-3 py-4 text-right">{department.headcount}</td>
                          <td className="px-3 py-4 text-right font-medium">{formatAmount(department.total)}</td>
                          <td className="px-3 py-4 text-right">{formatAmount(department.average)}</td>
                          <td className="px-3 py-4 text-right">{formatPercent(department.share)}</td>
                          <td className="px-3 py-4 text-right">{budgetAvailable ? formatAmount(department.budget) : "未设置"}</td>
                          <td className="px-3 py-4">
                            {budgetAvailable ? (
                              <div className="min-w-[150px]">
                                <div className="mb-1 flex justify-between text-xs">
                                  <span style={{ color: budgetOver ? "var(--color-danger)" : "var(--text-color-3)" }}>
                                    {budgetOver ? `超支 ${formatAmount(department.budgetVariance)}` : `结余 ${formatAmount(Math.abs(department.budgetVariance))}`}
                                  </span>
                                  <span>{formatPercent(department.budgetUsageRate)}</span>
                                </div>
                                <div className="h-1.5 overflow-hidden rounded-full" style={{ background: "var(--color-fill-2)" }}>
                                  <div
                                    className="h-full rounded-full"
                                    style={{ width: `${clampPercent(department.budgetUsageRate)}%`, background: budgetOver ? "var(--color-danger)" : "var(--color-primary)" }}
                                  />
                                </div>
                              </div>
                            ) : (
                              <Button size="mini" type="text" icon={<IconCalendar />} onClick={() => router.push("/hr/organization")}>设置预算</Button>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      ) : (
        <Card style={{ borderRadius: 12 }}>
          <Empty description="暂无可展示的人力成本数据" />
        </Card>
      )}
    </div>
  );
}
