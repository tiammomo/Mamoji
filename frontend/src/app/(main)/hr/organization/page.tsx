"use client";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Card, Form, Grid, Input, InputNumber, Message, Modal, Select, Tag } from "@arco-design/web-react";
import { IconBranch, IconEdit, IconPlus } from "@arco-design/web-react/icon";
import AmountDisplay from "@/components/common/AmountDisplay";
import PageHeader from "@/components/common/PageHeader";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useAppStore } from "@/lib/stores/appStore";
import type { Department, Employee, EnterpriseSummary } from "@/lib/types";

const { Row, Col } = Grid;
const FormItem = Form.Item;

type DepartmentFormValues = {
  name: string;
  costCenter: string;
  budget: number;
  managerEmployeeId?: number | null;
  status: number;
};

type DepartmentRow = Department & {
  activeCount: number;
  onboardingCount: number;
  monthlyCost: number;
  manager?: Employee;
  positions: string[];
  budgetUsage: number;
};

const money = (value: unknown) => Number(value || 0);

const activeStatuses = new Set(["active", "probation"]);

export default function OrganizationPage() {
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingDepartment, setEditingDepartment] = useState<Department | null>(null);
  const [form] = Form.useForm<DepartmentFormValues>();

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      const [summaryRes, departmentsRes, employeesRes] = await Promise.all([
        enterpriseApi.summary(),
        enterpriseApi.departments(),
        enterpriseApi.employees(),
      ]);
      setSummary(summaryRes.data);
      setDepartments(departmentsRes.data);
      setEmployees(employeesRes.data);
    } catch {
      Message.error("组织架构加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

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
        Message.error("组织架构加载失败");
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

  const employeeOptions = useMemo(
    () => employees
      .filter((employee) => employee.status !== "departed")
      .map((employee) => ({ value: employee.id, label: `${employee.name} · ${employee.position}` })),
    [employees]
  );

  const departmentRows = useMemo<DepartmentRow[]>(() => {
    return departments.map((department) => {
      const departmentEmployees = employees.filter((employee) => employee.departmentId === department.id);
      const activeEmployees = departmentEmployees.filter((employee) => activeStatuses.has(employee.status));
      const onboardingEmployees = departmentEmployees.filter((employee) => employee.status === "onboarding");
      const manager = department.managerEmployeeId
        ? employees.find((employee) => employee.id === department.managerEmployeeId)
        : activeEmployees.find((employee) => employee.accessRole === "department_manager")
          || activeEmployees.find((employee) => employee.position.includes("负责人") || employee.position.includes("经理"));
      const monthlyCost = activeEmployees.reduce((total, employee) => total + money(employee.monthlyCost), 0);
      const budget = money(department.budget);
      return {
        ...department,
        activeCount: activeEmployees.length,
        onboardingCount: onboardingEmployees.length,
        monthlyCost,
        manager,
        positions: Array.from(new Set(departmentEmployees.map((employee) => employee.position))).slice(0, 4),
        budgetUsage: budget > 0 ? Math.min(100, Math.round((monthlyCost / budget) * 100)) : 0,
      };
    });
  }, [departments, employees]);

  const totals = useMemo(() => {
    return departmentRows.reduce(
      (acc, row) => ({
        budget: acc.budget + money(row.budget),
        monthlyCost: acc.monthlyCost + row.monthlyCost,
        activeCount: acc.activeCount + row.activeCount,
        onboardingCount: acc.onboardingCount + row.onboardingCount,
      }),
      { budget: 0, monthlyCost: 0, activeCount: 0, onboardingCount: 0 }
    );
  }, [departmentRows]);

  const openCreate = () => {
    setEditingDepartment(null);
    form.setFieldsValue({
      name: "",
      costCenter: "",
      budget: 0,
      managerEmployeeId: undefined,
      status: 1,
    });
    setModalVisible(true);
  };

  const openEdit = (department: Department) => {
    setEditingDepartment(department);
    form.setFieldsValue({
      name: department.name,
      costCenter: department.costCenter,
      budget: money(department.budget),
      managerEmployeeId: department.managerEmployeeId || undefined,
      status: department.status,
    });
    setModalVisible(true);
  };

  const handleSubmit = async (values: DepartmentFormValues) => {
    try {
      setSaving(true);
      const payload = {
        companyId: activeCompanyId || undefined,
        name: values.name,
        costCenter: values.costCenter,
        budget: money(values.budget),
        managerEmployeeId: values.managerEmployeeId || null,
        status: Number(values.status || 1),
      };
      if (editingDepartment) {
        await enterpriseApi.updateDepartment(editingDepartment.id, payload);
        Message.success("部门已更新");
      } else {
        await enterpriseApi.createDepartment(payload);
        Message.success("部门已新增");
      }
      setModalVisible(false);
      setEditingDepartment(null);
      await fetchData();
    } catch {
      Message.error("部门保存失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="mx-auto max-w-7xl animate-fade-in">
      <PageHeader
        title="组织人员"
        subtitle={summary?.company
          ? `${summary.company.name} · 部门、岗位、负责人、成本中心和部门预算`
          : "部门、岗位、负责人、成本中心和部门预算"}
        icon={<IconBranch />}
        extra={
          <Button type="primary" icon={<IconPlus />} onClick={openCreate}>
            新增部门
          </Button>
        }
      />

      <Row gutter={[16, 16]} className="metric-grid">
        <Col xs={12} md={6}>
          <MetricCard title="组织单元" value={departmentRows.length} caption="公司部门" />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard title="在岗人数" value={totals.activeCount} caption={`待入职 ${totals.onboardingCount}`} />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard title="部门预算" amount={totals.budget} caption="按部门成本中心归集" />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard title="月人力成本" amount={totals.monthlyCost || summary?.monthlyPeopleCost || 0} caption="在岗员工月成本" tone="expense" />
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={7}>
          <Card style={{ borderRadius: 12 }} title="组织视图">
            <div className="bi-flat-list space-y-3">
              {loading ? (
                <div className="py-10 text-center text-sm" style={{ color: "var(--text-color-3)" }}>加载中...</div>
              ) : departmentRows.map((department) => (
                <div
                  key={department.id}
                  className="rounded-lg border p-4"
                  style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="font-medium">{department.name}</div>
                      <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                        {department.costCenter || "未设置成本中心"}
                      </div>
                    </div>
                    <Tag color={department.status === 1 ? "green" : "gray"}>{department.status === 1 ? "启用" : "停用"}</Tag>
                  </div>
                  <div className="mt-3 grid grid-cols-3 gap-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    <div>
                      <div>负责人</div>
                      <div className="mt-1 font-medium" style={{ color: "var(--text-color-1)" }}>{department.manager?.name || "--"}</div>
                    </div>
                    <div>
                      <div>在岗</div>
                      <div className="mt-1 font-medium" style={{ color: "var(--text-color-1)" }}>{department.activeCount}</div>
                    </div>
                    <div>
                      <div>预算使用</div>
                      <div className="mt-1 font-medium" style={{ color: "var(--text-color-1)" }}>{department.budgetUsage}%</div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={17}>
          <Card style={{ borderRadius: 12 }}>
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="font-medium">部门管理</div>
                <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                  部门、负责人、岗位、成本中心与预算
                </div>
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full min-w-[900px] table-fixed border-collapse text-sm">
                <colgroup>
                  <col style={{ width: 160 }} />
                  <col style={{ width: 140 }} />
                  <col style={{ width: 170 }} />
                  <col style={{ width: 110 }} />
                  <col style={{ width: 220 }} />
                  <col style={{ width: 90 }} />
                  <col style={{ width: 80 }} />
                </colgroup>
                <thead>
                  <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                    {[
                      { label: "部门", align: "text-left" },
                      { label: "负责人", align: "text-left" },
                      { label: "岗位", align: "text-left" },
                      { label: "人员", align: "text-center" },
                      { label: "预算与成本", align: "text-left" },
                      { label: "状态", align: "text-center" },
                      { label: "操作", align: "text-center" },
                    ].map((column) => (
                      <th key={column.label} className={`px-4 py-3 font-medium whitespace-nowrap ${column.align}`} style={{ color: "var(--text-color-2)" }}>
                        {column.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr>
                      <td colSpan={7} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>加载中...</td>
                    </tr>
                  ) : departmentRows.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>暂无部门</td>
                    </tr>
                  ) : departmentRows.map((department) => (
                    <tr key={department.id} className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]" style={{ borderColor: "var(--border-color-light)" }}>
                      <td className="px-4 py-4 align-middle">
                        <div className="font-medium">{department.name}</div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{department.costCenter || "--"}</div>
                      </td>
                      <td className="px-4 py-4 align-middle">
                        <div>{department.manager?.name || "--"}</div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{department.manager?.position || ""}</div>
                      </td>
                      <td className="px-4 py-4 align-middle">
                        <div className="flex flex-wrap gap-1">
                          {department.positions.length ? department.positions.map((position) => (
                            <Tag key={position} color="arcoblue">{position}</Tag>
                          )) : <span style={{ color: "var(--text-color-3)" }}>--</span>}
                        </div>
                      </td>
                      <td className="px-4 py-4 text-center align-middle">
                        <div className="font-medium">{department.activeCount} 在岗</div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>待入职 {department.onboardingCount}</div>
                      </td>
                      <td className="px-4 py-4 align-middle">
                        <div className="flex items-center justify-between gap-3">
                          <AmountDisplay amount={department.monthlyCost} type={2} size="small" />
                          <span className="text-xs" style={{ color: "var(--text-color-3)" }}>{department.budgetUsage}%</span>
                        </div>
                        <div className="mt-2 h-2 overflow-hidden rounded-full" style={{ backgroundColor: "var(--color-fill-1)" }}>
                          <div
                            className="h-full rounded-full"
                            style={{
                              width: `${Math.max(4, department.budgetUsage)}%`,
                              background: department.budgetUsage >= 90 ? "var(--gradient-expense)" : "var(--gradient-primary)",
                            }}
                          />
                        </div>
                        <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                          预算 <AmountDisplay amount={department.budget} size="small" />
                        </div>
                      </td>
                      <td className="px-4 py-4 text-center align-middle">
                        <Tag color={department.status === 1 ? "green" : "gray"}>{department.status === 1 ? "启用" : "停用"}</Tag>
                      </td>
                      <td className="px-4 py-4 text-center align-middle">
                        <Button type="text" size="mini" icon={<IconEdit />} title="编辑部门" onClick={() => openEdit(department)} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </Col>
      </Row>

      <Modal
        title={editingDepartment ? "编辑部门" : "新增部门"}
        visible={modalVisible}
        confirmLoading={saving}
        okText="保存部门"
        cancelText="取消"
        onCancel={() => setModalVisible(false)}
        onOk={() => form.submit()}
        style={{ width: 640, maxWidth: "calc(100vw - 40px)" }}
      >
        <Form form={form} layout="vertical" onSubmit={handleSubmit}>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <FormItem label="部门名称" field="name" rules={[{ required: true, message: "请输入部门名称" }]}>
                <Input placeholder="例如 产品研发" />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="成本中心" field="costCenter" rules={[{ required: true, message: "请输入成本中心" }]}>
                <Input placeholder="例如 RND" />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="部门预算" field="budget">
                <InputNumber min={0} precision={2} style={{ width: "100%" }} />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="部门负责人" field="managerEmployeeId">
                <Select placeholder="选择负责人" allowClear>
                  {employeeOptions.map((employee) => (
                    <Select.Option key={employee.value} value={employee.value}>{employee.label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="状态" field="status">
                <Select>
                  <Select.Option value={1}>启用</Select.Option>
                  <Select.Option value={0}>停用</Select.Option>
                </Select>
              </FormItem>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}

function MetricCard({
  title,
  value,
  amount,
  caption,
  tone = "neutral",
}: {
  title: string;
  value?: number;
  amount?: number;
  caption: string;
  tone?: "neutral" | "expense";
}) {
  return (
    <Card className="metric-card" style={{ borderRadius: 12, height: "100%" }}>
      <div className="mb-2 text-sm" style={{ color: "var(--text-color-3)" }}>{title}</div>
      {amount === undefined ? (
        <div className="text-2xl font-bold">{value ?? 0}</div>
      ) : (
        <AmountDisplay amount={amount} type={tone === "expense" ? 2 : undefined} size="large" />
      )}
      <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{caption}</div>
    </Card>
  );
}
