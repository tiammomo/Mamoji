"use client";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Card, Grid, Input, Message, Select, Tag } from "@arco-design/web-react";
import { IconDownload, IconIdcard, IconSearch } from "@arco-design/web-react/icon";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { useAppStore } from "@/lib/stores/appStore";
import type { Department, Employee, EnterpriseSummary } from "@/lib/types";

const { Row, Col } = Grid;

const statusLabels: Record<string, { label: string; color: string }> = {
  onboarding: { label: "待入职", color: "orange" },
  probation: { label: "试用期", color: "arcoblue" },
  active: { label: "在职", color: "green" },
  departed: { label: "已离职", color: "gray" },
};

const tableColumns = [
  { label: "员工", width: "18%", align: "text-left" },
  { label: "部门 / 岗位", width: "16%", align: "text-left" },
  { label: "基本工资", width: "12%", align: "text-right" },
  { label: "社保", width: "10%", align: "text-right" },
  { label: "公积金", width: "10%", align: "text-right" },
  { label: "个税估算", width: "10%", align: "text-right" },
  { label: "月人力成本", width: "14%", align: "text-right" },
  { label: "状态", width: "10%", align: "text-center" },
] as const;

const money = (value: unknown) => Number(value || 0);

export default function CompensationPage() {
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("active");
  const [loading, setLoading] = useState(true);
  const employeesPagination = useClientPagination(employees, 10);

  const fetchData = useCallback(async (nextKeyword = keyword, nextStatus = status) => {
    try {
      setLoading(true);
      const [summaryRes, departmentsRes, employeesRes] = await Promise.all([
        enterpriseApi.summary(),
        enterpriseApi.departments(),
        enterpriseApi.employees({
          keyword: nextKeyword || undefined,
          status: nextStatus === "all" ? undefined : nextStatus,
        }),
      ]);
      setSummary(summaryRes.data);
      setDepartments(departmentsRes.data);
      setEmployees(employeesRes.data);
    } catch {
      Message.error("薪酬数据加载失败");
    } finally {
      setLoading(false);
    }
  }, [keyword, status]);

  useEffect(() => {
    let cancelled = false;

    const loadInitialData = async () => {
      try {
        setLoading(true);
        const [summaryRes, departmentsRes, employeesRes] = await Promise.all([
          enterpriseApi.summary(),
          enterpriseApi.departments(),
          enterpriseApi.employees({ status: "active" }),
        ]);
        if (cancelled) return;
        setSummary(summaryRes.data);
        setDepartments(departmentsRes.data);
        setEmployees(employeesRes.data);
      } catch {
        Message.error("薪酬数据加载失败");
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

  const compensationSummary = useMemo(() => {
    return employees.reduce(
      (acc, employee) => ({
        salary: acc.salary + money(employee.salary),
        socialInsurance: acc.socialInsurance + money(employee.socialInsurance),
        housingFund: acc.housingFund + money(employee.housingFund),
        taxEstimate: acc.taxEstimate + money(employee.taxEstimate),
        monthlyCost: acc.monthlyCost + money(employee.monthlyCost),
      }),
      { salary: 0, socialInsurance: 0, housingFund: 0, taxEstimate: 0, monthlyCost: 0 }
    );
  }, [employees]);

  const departmentCostRows = useMemo(() => {
    const departmentMap = new Map(departments.map((department) => [department.id, department.name]));
    const groups = new Map<string, { name: string; count: number; monthlyCost: number }>();

    employees.forEach((employee) => {
      const key = String(employee.departmentId || "none");
      const current = groups.get(key) || {
        name: employee.departmentName || (employee.departmentId ? departmentMap.get(employee.departmentId) : "未分配部门") || "未分配部门",
        count: 0,
        monthlyCost: 0,
      };
      current.count += 1;
      current.monthlyCost += money(employee.monthlyCost);
      groups.set(key, current);
    });

    return Array.from(groups.values()).sort((left, right) => right.monthlyCost - left.monthlyCost);
  }, [departments, employees]);

  const maxDepartmentCost = Math.max(...departmentCostRows.map((row) => row.monthlyCost), 1);

  const handleSearch = () => {
    employeesPagination.resetPage();
    void fetchData(keyword, status);
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title="人员薪酬"
        subtitle={summary?.company
          ? `${summary.company.name} · 薪资、社保、公积金和个税估算`
          : "薪资、社保、公积金和个税估算"}
        icon={<IconIdcard />}
        extra={
          <Button
            type="outline"
            icon={<IconDownload />}
            disabled
            title="薪酬导出将在发薪批次能力中开放"
          >
            导出
          </Button>
        }
      />

      <Row gutter={16} className="mb-6">
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>月人力成本</div>
            <AmountDisplay amount={compensationSummary.monthlyCost || summary?.monthlyPeopleCost || 0} type={2} size="large" />
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>当前筛选 {employees.length} 人</div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>基本工资</div>
            <AmountDisplay amount={compensationSummary.salary} type={2} size="large" />
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>合同薪资与固定薪酬</div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>社保公积金</div>
            <AmountDisplay amount={compensationSummary.socialInsurance + compensationSummary.housingFund} type={2} size="large" />
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>社保 + 公积金</div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>个税估算</div>
            <AmountDisplay amount={compensationSummary.taxEstimate} type={2} size="large" />
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>用于薪酬成本预估</div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <Card style={{ borderRadius: 12 }} title="部门薪酬成本">
            <div className="space-y-4">
              {departmentCostRows.length === 0 && !loading ? (
                <div className="py-10 text-center text-sm" style={{ color: "var(--text-color-3)" }}>
                  暂无薪酬数据
                </div>
              ) : departmentCostRows.map((row) => (
                <div key={row.name}>
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <div className="text-sm font-medium">{row.name}</div>
                      <div className="text-xs mt-1" style={{ color: "var(--text-color-3)" }}>{row.count} 人</div>
                    </div>
                    <AmountDisplay amount={row.monthlyCost} type={2} size="small" />
                  </div>
                  <div className="mt-2 h-2 overflow-hidden rounded-full" style={{ backgroundColor: "var(--color-fill-1)" }}>
                    <div
                      className="h-full rounded-full"
                      style={{
                        width: `${Math.max(6, (row.monthlyCost / maxDepartmentCost) * 100)}%`,
                        background: "var(--gradient-expense)",
                      }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={16}>
          <Card style={{ borderRadius: 12 }}>
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div className="font-medium">员工薪酬明细</div>
              <div className="flex flex-wrap gap-2">
                <Input
                  prefix={<IconSearch />}
                  placeholder="搜索姓名、邮箱、岗位、部门"
                  value={keyword}
                  onChange={setKeyword}
                  onPressEnter={handleSearch}
                  style={{ width: 260 }}
                />
                <Select
                  value={status}
                  onChange={(value) => {
                    setStatus(value);
                    employeesPagination.resetPage();
                    void fetchData(keyword, value);
                  }}
                  style={{ width: 132 }}
                >
                  <Select.Option value="active">在职</Select.Option>
                  <Select.Option value="probation">试用期</Select.Option>
                  <Select.Option value="onboarding">待入职</Select.Option>
                  <Select.Option value="departed">已离职</Select.Option>
                  <Select.Option value="all">全部状态</Select.Option>
                </Select>
                <Button type="primary" onClick={handleSearch}>搜索</Button>
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full min-w-[980px] table-fixed border-collapse text-sm">
                <colgroup>
                  {tableColumns.map((column) => (
                    <col key={column.label} style={{ width: column.width }} />
                  ))}
                </colgroup>
                <thead>
                  <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                    {tableColumns.map((column) => (
                      <th key={column.label} className={`px-4 py-3 font-medium whitespace-nowrap ${column.align}`} style={{ color: "var(--text-color-2)" }}>
                        {column.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr>
                      <td colSpan={8} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>加载中...</td>
                    </tr>
                  ) : employees.length === 0 ? (
                    <tr>
                      <td colSpan={8} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>暂无薪酬数据</td>
                    </tr>
                  ) : employeesPagination.pagedData.map((employee) => {
                    const statusConfig = statusLabels[employee.status] || { label: employee.status, color: "gray" };
                    return (
                      <tr key={employee.id} className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]" style={{ borderColor: "var(--border-color-light)" }}>
                        <td className="px-4 py-4 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{employee.name}</div>
                          <div className="mt-1 break-all text-xs" style={{ color: "var(--text-color-3)" }}>{employee.email}</div>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <div className="font-medium">{employee.departmentName || "未分配部门"}</div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{employee.position}</div>
                        </td>
                        <td className="px-4 py-4 align-middle text-right"><AmountDisplay amount={employee.salary} type={2} size="small" /></td>
                        <td className="px-4 py-4 align-middle text-right"><AmountDisplay amount={employee.socialInsurance} type={2} size="small" /></td>
                        <td className="px-4 py-4 align-middle text-right"><AmountDisplay amount={employee.housingFund} type={2} size="small" /></td>
                        <td className="px-4 py-4 align-middle text-right"><AmountDisplay amount={employee.taxEstimate} type={2} size="small" /></td>
                        <td className="px-4 py-4 align-middle text-right"><AmountDisplay amount={employee.monthlyCost} type={2} /></td>
                        <td className="px-4 py-4 align-middle">
                          <div className="flex justify-center">
                            <Tag color={statusConfig.color}>{statusConfig.label}</Tag>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <AppPagination
              current={employeesPagination.page}
              pageSize={employeesPagination.pageSize}
              total={employeesPagination.total}
              pageSizeOptions={[10, 20, 50, 100]}
              onChange={employeesPagination.handleChange}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
