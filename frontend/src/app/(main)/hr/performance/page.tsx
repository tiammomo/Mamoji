"use client";
import { useEffect, useMemo, useState } from "react";
import { Card, Grid, Message, Progress, Select, Tag } from "@arco-design/web-react";
import { IconTrophy } from "@arco-design/web-react/icon";
import AmountDisplay from "@/components/common/AmountDisplay";
import PageHeader from "@/components/common/PageHeader";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useAppStore } from "@/lib/stores/appStore";
import type { Department, Employee, EnterpriseSummary } from "@/lib/types";

const { Row, Col } = Grid;

const cycleOptions = [
  { key: "2026-H1", label: "2026 上半年", startDate: "2026-01-01", endDate: "2026-06-30", status: "calibration" },
  { key: "2026-Q2", label: "2026 Q2", startDate: "2026-04-01", endDate: "2026-06-30", status: "review" },
  { key: "2026-Q3", label: "2026 Q3", startDate: "2026-07-01", endDate: "2026-09-30", status: "draft" },
];

const gradeConfig: Record<string, { label: string; color: string }> = {
  S: { label: "卓越", color: "gold" },
  A: { label: "优秀", color: "green" },
  B: { label: "达标", color: "arcoblue" },
  C: { label: "待改进", color: "orange" },
};

const statusConfig: Record<string, { label: string; color: string }> = {
  draft: { label: "目标制定", color: "gray" },
  self_review: { label: "自评中", color: "arcoblue" },
  manager_review: { label: "上级评价", color: "purple" },
  calibration: { label: "校准中", color: "orange" },
  confirmed: { label: "已确认", color: "green" },
};

type PerformanceRecord = {
  employee: Employee;
  departmentName: string;
  reviewerName: string;
  goals: number;
  completedGoals: number;
  score: number;
  grade: string;
  status: string;
  bonusSuggestion: number;
};

const money = (value: unknown) => Number(value || 0);

const activeStatuses = new Set(["active", "probation"]);

export default function PerformancePage() {
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [selectedCycle, setSelectedCycle] = useState("2026-H1");
  const [departmentFilter, setDepartmentFilter] = useState("all");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    const loadInitialData = async () => {
      try {
        setLoading(true);
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
        Message.error("绩效数据加载失败");
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void loadInitialData();

    return () => {
      cancelled = true;
    };
  }, [activeCompanyId]);

  const departmentMap = useMemo(
    () => new Map(departments.map((department) => [department.id, department])),
    [departments]
  );

  const records = useMemo<PerformanceRecord[]>(() => {
    const eligibleEmployees = employees.filter((employee) => activeStatuses.has(employee.status));
    return eligibleEmployees.map((employee, index) => {
      const department = employee.departmentId ? departmentMap.get(employee.departmentId) : undefined;
      const peers = eligibleEmployees.filter((candidate) => candidate.departmentId === employee.departmentId);
      const reviewer = department?.managerEmployeeId
        ? employees.find((candidate) => candidate.id === department.managerEmployeeId)
        : peers.find((candidate) => candidate.accessRole === "department_manager")
          || employees.find((candidate) => candidate.accessRole === "founder");
      const scorePattern = [94, 88, 82, 76];
      const score = scorePattern[index % scorePattern.length];
      const grade = score >= 92 ? "S" : score >= 86 ? "A" : score >= 80 ? "B" : "C";
      const goals = 4;
      const completedGoals = grade === "S" ? 4 : grade === "A" ? 3 : grade === "B" ? 3 : 2;
      const statusPattern = ["confirmed", "calibration", "manager_review", "self_review"];
      return {
        employee,
        departmentName: department?.name || employee.departmentName || "未分配部门",
        reviewerName: reviewer?.name || "--",
        goals,
        completedGoals,
        score,
        grade,
        status: statusPattern[index % statusPattern.length],
        bonusSuggestion: Math.round(money(employee.salary) * (grade === "S" ? 0.18 : grade === "A" ? 0.12 : grade === "B" ? 0.06 : 0)),
      };
    });
  }, [departmentMap, employees]);

  const filteredRecords = useMemo(() => {
    return records.filter((record) => departmentFilter === "all" || String(record.employee.departmentId || "none") === departmentFilter);
  }, [departmentFilter, records]);

  const summaryCards = useMemo(() => {
    const confirmedCount = filteredRecords.filter((record) => record.status === "confirmed").length;
    const averageScore = filteredRecords.length
      ? Math.round(filteredRecords.reduce((total, record) => total + record.score, 0) / filteredRecords.length)
      : 0;
    const highPerformers = filteredRecords.filter((record) => ["S", "A"].includes(record.grade)).length;
    const bonusTotal = filteredRecords.reduce((total, record) => total + record.bonusSuggestion, 0);
    return { confirmedCount, averageScore, highPerformers, bonusTotal };
  }, [filteredRecords]);

  const gradeDistribution = useMemo(() => {
    return Object.keys(gradeConfig).map((grade) => ({
      grade,
      count: filteredRecords.filter((record) => record.grade === grade).length,
    }));
  }, [filteredRecords]);

  const activeCycle = cycleOptions.find((cycle) => cycle.key === selectedCycle) || cycleOptions[0];

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="绩效管理"
        subtitle={summary?.company
          ? `${summary.company.name} · 绩效周期、目标完成、评价校准和奖金建议`
          : "绩效周期、目标完成、评价校准和奖金建议"}
        icon={<IconTrophy />}
        extra={
          <Select value={selectedCycle} onChange={(value) => setSelectedCycle(String(value))} style={{ width: 150 }}>
            {cycleOptions.map((cycle) => (
              <Select.Option key={cycle.key} value={cycle.key}>{cycle.label}</Select.Option>
            ))}
          </Select>
        }
      />

      <Row gutter={[16, 16]} className="mb-6">
        <Col xs={12} md={6}>
          <MetricCard title="参评人数" value={filteredRecords.length} caption={`${activeCycle.startDate} 至 ${activeCycle.endDate}`} />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard title="已确认" value={summaryCards.confirmedCount} caption="结果确认人数" />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard title="平均得分" value={summaryCards.averageScore} caption="当前筛选口径" />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard title="奖金建议" amount={summaryCards.bonusTotal} caption="按绩效等级估算" />
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={7}>
          <Card style={{ borderRadius: 12 }} title="绩效周期">
            <div className="space-y-3">
              {cycleOptions.map((cycle) => {
                const isActive = cycle.key === selectedCycle;
                return (
                  <button
                    key={cycle.key}
                    type="button"
                    onClick={() => setSelectedCycle(cycle.key)}
                    className="w-full cursor-pointer rounded-lg border p-4 text-left transition-colors"
                    style={{
                      borderColor: isActive ? "var(--color-primary)" : "var(--border-color-light)",
                      backgroundColor: isActive ? "rgba(99, 102, 241, 0.09)" : "var(--color-fill-1)",
                      color: "var(--text-color-1)",
                    }}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="font-medium">{cycle.label}</div>
                      <Tag color={cycle.status === "calibration" ? "orange" : cycle.status === "review" ? "arcoblue" : "gray"}>
                        {cycle.status === "calibration" ? "校准中" : cycle.status === "review" ? "评价中" : "草稿"}
                      </Tag>
                    </div>
                    <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                      {cycle.startDate} - {cycle.endDate}
                    </div>
                  </button>
                );
              })}
            </div>
          </Card>

          <Card className="mt-4" style={{ borderRadius: 12 }} title="等级分布">
            <div className="space-y-4">
              {gradeDistribution.map((item) => {
                const percentage = filteredRecords.length ? Math.round((item.count / filteredRecords.length) * 100) : 0;
                return (
                  <div key={item.grade}>
                    <div className="mb-2 flex items-center justify-between gap-3">
                      <Tag color={gradeConfig[item.grade].color}>{item.grade} · {gradeConfig[item.grade].label}</Tag>
                      <span className="text-sm font-medium">{item.count} 人</span>
                    </div>
                    <Progress percent={percentage} size="small" showText={false} color="var(--color-primary)" />
                  </div>
                );
              })}
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={17}>
          <Card style={{ borderRadius: 12 }}>
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="font-medium">员工绩效台账</div>
                <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                  目标、评价、校准、结果和奖金建议
                </div>
              </div>
              <Select value={departmentFilter} onChange={(value) => setDepartmentFilter(String(value))} style={{ width: 160 }}>
                <Select.Option value="all">全部部门</Select.Option>
                {departments.map((department) => (
                  <Select.Option key={department.id} value={String(department.id)}>{department.name}</Select.Option>
                ))}
              </Select>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full min-w-[900px] table-fixed border-collapse text-sm">
                <thead>
                  <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                    {["员工", "部门 / 岗位", "目标完成", "得分", "等级", "上级", "状态", "奖金建议"].map((label) => (
                      <th key={label} className="px-4 py-3 text-left font-medium whitespace-nowrap" style={{ color: "var(--text-color-2)" }}>
                        {label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr>
                      <td colSpan={8} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>加载中...</td>
                    </tr>
                  ) : filteredRecords.length === 0 ? (
                    <tr>
                      <td colSpan={8} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>暂无绩效记录</td>
                    </tr>
                  ) : filteredRecords.map((record) => {
                    const grade = gradeConfig[record.grade];
                    const status = statusConfig[record.status];
                    const completion = Math.round((record.completedGoals / record.goals) * 100);
                    return (
                      <tr key={record.employee.id} className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]" style={{ borderColor: "var(--border-color-light)" }}>
                        <td className="px-4 py-4 align-middle">
                          <div className="font-medium">{record.employee.name}</div>
                          <div className="mt-1 break-all text-xs" style={{ color: "var(--text-color-3)" }}>{record.employee.email}</div>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <div>{record.departmentName}</div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{record.employee.position}</div>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <div className="mb-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                            {record.completedGoals} / {record.goals} 项
                          </div>
                          <Progress percent={completion} size="small" showText={false} color="var(--color-primary)" />
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <span className="text-lg font-semibold">{record.score}</span>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <Tag color={grade.color}>{record.grade} · {grade.label}</Tag>
                        </td>
                        <td className="px-4 py-4 align-middle">{record.reviewerName}</td>
                        <td className="px-4 py-4 align-middle">
                          <Tag color={status.color}>{status.label}</Tag>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <AmountDisplay amount={record.bonusSuggestion} size="small" />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  );
}

function MetricCard({
  title,
  value,
  amount,
  caption,
}: {
  title: string;
  value?: number;
  amount?: number;
  caption: string;
}) {
  return (
    <Card style={{ borderRadius: 12, height: "100%" }}>
      <div className="mb-2 text-sm" style={{ color: "var(--text-color-3)" }}>{title}</div>
      {amount === undefined ? (
        <div className="text-2xl font-bold">{value ?? 0}</div>
      ) : (
        <AmountDisplay amount={amount} size="large" />
      )}
      <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{caption}</div>
    </Card>
  );
}
