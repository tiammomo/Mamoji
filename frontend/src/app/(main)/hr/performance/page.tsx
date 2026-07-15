"use client";

import { useEffect, useMemo, useState } from "react";
import { Button, Card, Empty, Message, Progress, Select, Skeleton, Tag } from "@arco-design/web-react";
import {
  IconCheckCircle,
  IconExclamationCircle,
  IconTrophy,
  IconUserGroup,
} from "@arco-design/web-react/icon";
import { useRouter } from "next/navigation";
import PageHeader from "@/components/common/PageHeader";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useAppStore } from "@/lib/stores/appStore";
import type { Department, Employee, EnterpriseSummary } from "@/lib/types";

const activeStatuses = new Set(["active", "probation"]);

const employeeStatusMeta: Record<string, { label: string; color: string }> = {
  active: { label: "在岗", color: "green" },
  probation: { label: "试用期", color: "arcoblue" },
};

const profileChecks = [
  {
    key: "employeeNo",
    label: "员工编号",
    description: "用于稳定识别人员和后续记录归档",
    complete: (employee: Employee) => Boolean(employee.employeeNo?.trim()),
  },
  {
    key: "department",
    label: "组织归属",
    description: "已关联部门，可确定统计范围",
    complete: (employee: Employee) => Boolean(employee.departmentId),
  },
  {
    key: "position",
    label: "岗位信息",
    description: "已维护岗位名称和职级",
    complete: (employee: Employee) => Boolean(employee.position?.trim() && employee.jobLevel?.trim()),
  },
  {
    key: "manager",
    label: "直属汇报人",
    description: "已显式维护直属经理关系",
    complete: (employee: Employee) => Boolean(employee.directManagerEmployeeId),
  },
  {
    key: "workProfile",
    label: "任职信息",
    description: "已维护入职日期、用工类型和工作地点",
    complete: (employee: Employee) => Boolean(
      employee.hireDate && employee.employmentType && employee.workLocation?.trim()
    ),
  },
] as const;

const rolloutSteps = [
  {
    title: "建立真实绩效周期",
    description: "由后端保存周期名称、起止日期、适用组织和负责人，避免周期只存在于页面常量中。",
  },
  {
    title: "冻结参评范围",
    description: "从员工档案选择参评人员，并保存纳入、排除及调整原因。",
  },
  {
    title: "记录目标与证据",
    description: "保存目标、权重、进度、成果证据和变更记录，不从员工顺序推导完成度。",
  },
  {
    title: "完成评价与校准",
    description: "区分自评、上级评价和校准，保留评分口径、操作者和时间。",
  },
  {
    title: "确认结果后再联动薪酬",
    description: "只有已确认且可审计的绩效结果，才允许进入奖金测算或薪酬审批。",
  },
];

export default function PerformancePage() {
  const router = useRouter();
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [departmentFilter, setDepartmentFilter] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let cancelled = false;

    const loadData = async () => {
      setLoading(true);
      setError(null);
      setSummary(null);
      setDepartments([]);
      setEmployees([]);
      try {
        const [summaryRes, departmentsRes, employeesRes] = await Promise.all([
          enterpriseApi.summary(),
          enterpriseApi.departments(),
          enterpriseApi.employees(),
        ]);
        if (cancelled) return;
        setSummary(summaryRes.data);
        setDepartments(departmentsRes.data);
        setEmployees(employeesRes.data);
      } catch {
        if (cancelled) return;
        setError("员工与组织档案加载失败，当前无法判断基础数据是否完整。");
        Message.error("绩效准备数据加载失败");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    void loadData();

    return () => {
      cancelled = true;
    };
  }, [activeCompanyId, reloadToken]);

  const departmentMap = useMemo(
    () => new Map(departments.map((department) => [department.id, department])),
    [departments]
  );

  const employeeMap = useMemo(
    () => new Map(employees.map((employee) => [employee.id, employee])),
    [employees]
  );

  const eligibleEmployees = useMemo(
    () => employees.filter((employee) => activeStatuses.has(employee.status)),
    [employees]
  );

  const coverage = useMemo(() => {
    const fieldCounts = profileChecks.map((check) => ({
      ...check,
      count: eligibleEmployees.filter((employee) => check.complete(employee)).length,
    }));
    const completedFields = fieldCounts.reduce((total, field) => total + field.count, 0);
    const totalFields = eligibleEmployees.length * profileChecks.length;
    const percent = totalFields ? Math.round((completedFields / totalFields) * 100) : 0;
    const probationCount = eligibleEmployees.filter((employee) => employee.status === "probation").length;
    return { fieldCounts, percent, probationCount };
  }, [eligibleEmployees]);

  const filteredEmployees = useMemo(
    () => eligibleEmployees.filter((employee) => (
      departmentFilter === "all" || String(employee.departmentId || "none") === departmentFilter
    )),
    [departmentFilter, eligibleEmployees]
  );

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="绩效周期准备台"
        subtitle={summary?.company
          ? `${summary.company.name} · 试运行阶段，仅检查上线前的真实基础数据`
          : "试运行阶段，仅检查上线前的真实基础数据"}
        icon={<IconTrophy />}
        extra={
          <Button type="primary" icon={<IconUserGroup />} onClick={() => router.push("/admin/users")}>
            完善员工档案
          </Button>
        }
      />

      <div
        className="mb-5 rounded-2xl border p-4 sm:p-5"
        style={{
          borderColor: "var(--color-warning-border)",
          background: "linear-gradient(135deg, var(--color-warning-soft), rgba(99, 102, 241, 0.07))",
        }}
      >
        <div className="flex items-start gap-3">
          <span
            className="grid h-9 w-9 shrink-0 place-items-center rounded-xl"
            style={{ backgroundColor: "var(--color-warning-soft)", color: "var(--color-warning)" }}
          >
            <IconExclamationCircle />
          </span>
          <div>
            <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>当前没有后端绩效记录</div>
            <div className="mt-1 text-sm leading-6" style={{ color: "var(--text-color-2)" }}>
              当前接口只提供员工与部门档案，没有绩效周期、目标、评价、校准、得分或奖金结果。
              本页不会生成任何绩效结论；下方百分比只表示员工基础字段是否已填写。
            </div>
          </div>
        </div>
      </div>

      {error ? (
        <div
          className="mb-5 flex flex-col gap-3 rounded-xl border p-4 sm:flex-row sm:items-center sm:justify-between"
          style={{ borderColor: "rgba(239, 68, 68, 0.28)", backgroundColor: "rgba(239, 68, 68, 0.06)" }}
        >
          <div className="flex items-start gap-2 text-sm" style={{ color: "rgb(var(--red-6))" }}>
            <IconExclamationCircle className="mt-0.5 shrink-0" />
            <span>{error}</span>
          </div>
          <Button size="small" onClick={() => setReloadToken((value) => value + 1)}>重新加载</Button>
        </div>
      ) : null}

      <div className="metric-grid metric-wrap-until-xl grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard
          title="候选人员范围"
          value={eligibleEmployees.length}
          suffix="人"
          caption="仅统计在岗与试用期员工"
          loading={loading}
        />
        <MetricCard
          title="试用期人员"
          value={coverage.probationCount}
          suffix="人"
          caption="来自员工当前状态"
          loading={loading}
        />
        <MetricCard
          title="基础字段覆盖"
          value={coverage.percent}
          suffix="%"
          caption="资料覆盖率，不是绩效得分"
          loading={loading}
          tone="primary"
        />
        <MetricCard
          title="绩效结果记录"
          value="未接入"
          caption="不展示得分、评级或奖金建议"
          loading={loading}
          tone="warning"
        />
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1.08fr)_minmax(320px,0.92fr)]">
        <Card style={{ borderRadius: 14 }} title="基础资料覆盖情况">
          <div className="mb-4 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
            每项均直接读取员工档案字段；缺少字段只代表需要补录，不代表员工不具备参评资格。
          </div>
          {loading ? (
            <Skeleton />
          ) : eligibleEmployees.length === 0 ? (
            <Empty description="暂无在岗或试用期员工" />
          ) : (
            <div className="space-y-5">
              {coverage.fieldCounts.map((field) => {
                const percent = Math.round((field.count / eligibleEmployees.length) * 100);
                return (
                  <div key={field.key}>
                    <div className="mb-2 flex items-start justify-between gap-4">
                      <div>
                        <div className="text-sm font-semibold" style={{ color: "var(--text-color-1)" }}>{field.label}</div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{field.description}</div>
                      </div>
                      <div className="shrink-0 text-sm font-medium" style={{ color: "var(--text-color-2)" }}>
                        {field.count}/{eligibleEmployees.length}
                      </div>
                    </div>
                    <Progress
                      percent={percent}
                      showText={false}
                      color={percent === 100 ? "var(--color-success)" : "var(--color-primary)"}
                    />
                  </div>
                );
              })}
            </div>
          )}
        </Card>

        <Card style={{ borderRadius: 14 }} title="正式上线前的五步闭环">
          <div className="space-y-3">
            {rolloutSteps.map((step, index) => (
              <div
                key={step.title}
                className="flex items-start gap-3 rounded-xl border p-3"
                style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
              >
                <span
                  className="grid h-8 w-8 shrink-0 place-items-center rounded-lg text-sm font-bold"
                  style={{ backgroundColor: "rgba(99, 102, 241, 0.11)", color: "var(--color-primary)" }}
                >
                  {index + 1}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>{step.title}</div>
                    <Tag color="gray">尚未接入</Tag>
                  </div>
                  <div className="mt-1 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{step.description}</div>
                </div>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <Card className="mt-4" style={{ borderRadius: 14 }}>
        <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="font-semibold" style={{ color: "var(--text-color-1)" }}>人员基础资料核对</div>
            <div className="mt-1 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>
              展示可验证的档案字段，不展示虚构目标、得分、等级或奖金。
            </div>
          </div>
          <Select
            aria-label="按部门筛选员工"
            value={departmentFilter}
            onChange={(value) => setDepartmentFilter(String(value))}
            style={{ width: "min(100%, 220px)" }}
          >
            <Select.Option value="all">全部部门</Select.Option>
            <Select.Option value="none">未分配部门</Select.Option>
            {departments.map((department) => (
              <Select.Option key={department.id} value={String(department.id)}>{department.name}</Select.Option>
            ))}
          </Select>
        </div>

        {loading ? (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2"><Skeleton /><Skeleton /></div>
        ) : filteredEmployees.length === 0 ? (
          <Empty description={eligibleEmployees.length ? "当前筛选下没有员工" : "暂无可核对的员工档案"} />
        ) : (
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            {filteredEmployees.map((employee) => {
              const missingFields = profileChecks.filter((check) => !check.complete(employee));
              const completedCount = profileChecks.length - missingFields.length;
              const percent = Math.round((completedCount / profileChecks.length) * 100);
              const manager = employee.directManagerEmployeeId
                ? employeeMap.get(employee.directManagerEmployeeId)
                : undefined;
              const status = employeeStatusMeta[employee.status] || { label: employee.status, color: "gray" };
              return (
                <div
                  key={employee.id}
                  className="rounded-2xl border p-4"
                  style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--bg-color-page)" }}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold" style={{ color: "var(--text-color-1)" }}>{employee.name}</span>
                        <Tag color={status.color}>{status.label}</Tag>
                      </div>
                      <div className="mt-1 truncate text-xs" style={{ color: "var(--text-color-3)" }}>
                        {employee.employeeNo || "未维护员工编号"} · {employee.email}
                      </div>
                    </div>
                    <div className="shrink-0 text-right">
                      <div className="text-lg font-bold" style={{ color: "var(--text-color-1)" }}>{percent}%</div>
                      <div className="text-[11px]" style={{ color: "var(--text-color-3)" }}>字段覆盖</div>
                    </div>
                  </div>

                  <div className="mt-4 grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
                    <InfoField label="部门" value={employee.departmentId ? departmentMap.get(employee.departmentId)?.name || employee.departmentName || "部门记录不可用" : "未分配"} />
                    <InfoField label="岗位 / 职级" value={[employee.position, employee.jobLevel].filter(Boolean).join(" / ") || "未维护"} />
                    <InfoField label="直属汇报人" value={manager?.name || "未维护"} />
                    <InfoField label="工作地点" value={employee.workLocation || "未维护"} />
                  </div>

                  <div className="mt-4 border-t pt-3" style={{ borderColor: "var(--border-color-light)" }}>
                    {missingFields.length === 0 ? (
                      <div className="flex items-center gap-2 text-xs" style={{ color: "var(--color-success)" }}>
                        <IconCheckCircle />
                        <span>本页检查的基础字段已填写；仍不代表已产生绩效记录。</span>
                      </div>
                    ) : (
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-xs" style={{ color: "var(--text-color-3)" }}>待补：</span>
                        {missingFields.map((field) => <Tag key={field.key} color="orange">{field.label}</Tag>)}
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Card>
    </div>
  );
}

function MetricCard({
  title,
  value,
  suffix,
  caption,
  loading,
  tone = "default",
}: {
  title: string;
  value: number | string;
  suffix?: string;
  caption: string;
  loading: boolean;
  tone?: "default" | "primary" | "warning";
}) {
  const valueColor = tone === "primary"
    ? "var(--color-primary)"
    : tone === "warning"
      ? "var(--color-warning)"
      : "var(--text-color-1)";

  return (
    <Card className="metric-card" style={{ borderRadius: 14, minHeight: 126 }}>
      <div className="text-sm" style={{ color: "var(--text-color-3)" }}>{title}</div>
      {loading ? (
        <Skeleton className="mt-3" />
      ) : (
        <>
          <div className="mt-3 text-2xl font-bold" style={{ color: valueColor }}>
            {value}{suffix ? <span className="ml-1 text-sm font-medium">{suffix}</span> : null}
          </div>
          <div className="mt-2 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{caption}</div>
        </>
      )}
    </Card>
  );
}

function InfoField({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
      <div className="text-[11px]" style={{ color: "var(--text-color-3)" }}>{label}</div>
      <div className="mt-1 truncate text-sm font-medium" style={{ color: "var(--text-color-1)" }}>{value}</div>
    </div>
  );
}
