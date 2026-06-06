"use client";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Card, Form, Grid, Input, Message, Modal, Select, Tag } from "@arco-design/web-react";
import { IconEdit, IconPlus, IconSearch, IconUserGroup } from "@arco-design/web-react/icon";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { useAppStore } from "@/lib/stores/appStore";
import type { Department, Employee, EmployeePayload, EnterpriseSummary, PermissionMatrix, TaxItem } from "@/lib/types";

const { Row, Col } = Grid;
const FormItem = Form.Item;

const statusLabels: Record<string, { label: string; color: string }> = {
  onboarding: { label: "待入职", color: "orange" },
  probation: { label: "试用期", color: "arcoblue" },
  active: { label: "在职", color: "green" },
  departed: { label: "已离职", color: "gray" },
};

const employmentTypeLabels: Record<string, string> = {
  full_time: "全职",
  part_time: "兼职",
  contractor: "外包",
  intern: "实习",
  probation: "试用期",
};

const accessRoleLabels: Record<string, { label: string; color: string }> = {
  founder: { label: "创始人/CEO", color: "red" },
  finance_admin: { label: "财务管理员", color: "green" },
  hr_admin: { label: "人事管理员", color: "purple" },
  department_manager: { label: "部门负责人", color: "arcoblue" },
  employee: { label: "普通员工", color: "gray" },
  viewer: { label: "只读观察者", color: "orangered" },
};

const accessScopeLabels: Record<string, string> = {
  group: "多公司",
  company: "全公司",
  company_set: "指定公司",
  department: "本部门",
  self: "本人",
  readonly: "只读",
};

const employeeTableColumns = [
  { label: "员工", width: "18%", align: "text-left" },
  { label: "部门", width: "9%", align: "text-center" },
  { label: "岗位", width: "13%", align: "text-center" },
  { label: "状态", width: "8%", align: "text-center" },
  { label: "企业角色", width: "11%", align: "text-center" },
  { label: "范围", width: "7%", align: "text-center" },
  { label: "用工", width: "7%", align: "text-center" },
  { label: "入职日期", width: "10%", align: "text-center" },
  { label: "月人力成本", width: "12%", align: "text-right" },
  { label: "操作", width: "5%", align: "text-center" },
] as const;

type EmployeeFormValues = EmployeePayload;

const money = (value: unknown) => Number(value || 0);

export default function AdminUsersPage() {
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [taxItems, setTaxItems] = useState<TaxItem[]>([]);
  const [permissionMatrix, setPermissionMatrix] = useState<PermissionMatrix | null>(null);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState<string>("all");
  const [editingEmployee, setEditingEmployee] = useState<Employee | null>(null);
  const [modalVisible, setModalVisible] = useState(false);
  const [form] = Form.useForm();
  const employeesPagination = useClientPagination(employees, 10);

  const departmentMap = useMemo(
    () => new Map(departments.map((department) => [department.id, department.name])),
    [departments]
  );

  const fetchData = useCallback(async (nextKeyword = "", nextStatus = "all") => {
    try {
      const [summaryRes, departmentsRes, employeesRes, taxRes, matrixRes] = await Promise.all([
        enterpriseApi.summary(),
        enterpriseApi.departments(),
        enterpriseApi.employees({
          keyword: nextKeyword || undefined,
          status: nextStatus === "all" ? undefined : nextStatus,
        }),
        enterpriseApi.taxItems(),
        enterpriseApi.permissionMatrix(),
      ]);
      setSummary(summaryRes.data);
      setDepartments(departmentsRes.data);
      setEmployees(employeesRes.data);
      setTaxItems(taxRes.data);
      setPermissionMatrix(matrixRes.data);
    } catch {
      Message.error("人员数据加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;

    const loadInitialData = async () => {
      try {
        setLoading(true);
        const [summaryRes, departmentsRes, employeesRes, taxRes, matrixRes] = await Promise.all([
          enterpriseApi.summary(),
          enterpriseApi.departments(),
          enterpriseApi.employees(),
          enterpriseApi.taxItems(),
          enterpriseApi.permissionMatrix(),
        ]);
        if (cancelled) return;
        setSummary(summaryRes.data);
        setDepartments(departmentsRes.data);
        setEmployees(employeesRes.data);
        setTaxItems(taxRes.data);
        setPermissionMatrix(matrixRes.data);
      } catch {
        Message.error("人员数据加载失败");
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

  const openCreate = () => {
    setEditingEmployee(null);
    form.resetFields();
    form.setFieldsValue({
      employmentType: "full_time",
      status: "onboarding",
      accessRole: "employee",
      accessScope: "self",
      hireDate: new Date().toISOString().slice(0, 10),
      salary: 0,
      socialInsurance: 0,
      housingFund: 0,
      taxEstimate: 0,
    });
    setModalVisible(true);
  };

  const openEdit = (employee: Employee) => {
    setEditingEmployee(employee);
    form.setFieldsValue({
      ...employee,
      departmentId: employee.departmentId || undefined,
      leaveDate: employee.leaveDate || undefined,
    });
    setModalVisible(true);
  };

  const toPayload = (values: EmployeeFormValues): EmployeePayload => ({
    ...values,
    companyId: activeCompanyId || undefined,
    departmentId: values.departmentId || null,
    leaveDate: values.leaveDate || null,
    salary: money(values.salary),
    socialInsurance: money(values.socialInsurance),
    housingFund: money(values.housingFund),
    taxEstimate: money(values.taxEstimate),
  });

  const handleSubmit = async (values: EmployeeFormValues) => {
    try {
      const payload = toPayload(values);
      if (editingEmployee) {
        await enterpriseApi.updateEmployee(editingEmployee.id, payload);
        Message.success("员工信息已更新");
      } else {
        await enterpriseApi.createEmployee(payload);
        Message.success("员工信息已创建");
      }
      setModalVisible(false);
      setEditingEmployee(null);
      setLoading(true);
      void fetchData(keyword, status);
    } catch {
      Message.error("员工信息保存失败");
    }
  };

  const pendingTaxes = taxItems.filter((item) => item.status !== "paid");

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title="人员信息"
        subtitle={summary?.company
          ? `${summary.company.name} · ${summary.company.industry} · ${summary.company.operatingRegion || "地区待完善"}`
          : "员工信息、入职离职与组织权限"}
        icon={<IconUserGroup />}
        extra={
          <Button type="primary" icon={<IconPlus />} onClick={openCreate}>
            新增员工
          </Button>
        }
      />

      <Row gutter={16} className="mb-6">
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>在职员工</div>
            <div className="text-2xl font-bold">{summary?.activeEmployeeCount ?? 0}</div>
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>
              待入职 {summary?.onboardingCount ?? 0} · 本月入职 {summary?.hiresThisMonth ?? 0}
            </div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>月人力成本</div>
            <AmountDisplay amount={summary?.monthlyPeopleCost || 0} type={2} size="large" />
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>
              工资、社保、公积金和个税估算
            </div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>部门数量</div>
            <div className="text-2xl font-bold">{summary?.departmentCount ?? 0}</div>
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>
              本月离职 {summary?.departuresThisMonth ?? 0}
            </div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card style={{ borderRadius: 12 }}>
            <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>待处理税费</div>
            <AmountDisplay amount={summary?.pendingTaxAmount || 0} type={2} size="large" />
            <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>
              下个截止日 {summary?.nextTaxDueDate || "--"}
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24}>
          <Card style={{ borderRadius: 12 }}>
            <div className="flex items-center justify-between mb-4 gap-3 flex-wrap">
              <div className="font-medium">员工信息</div>
              <div className="flex gap-2 flex-wrap">
                <Input
                  prefix={<IconSearch />}
                  placeholder="搜索姓名、邮箱、岗位、部门"
                  value={keyword}
                  onChange={setKeyword}
                  onPressEnter={() => {
                    employeesPagination.resetPage();
                    setLoading(true);
                    void fetchData(keyword, status);
                  }}
                  style={{ width: 260 }}
                />
                <select
                  value={status}
                  onChange={(event) => {
                    const value = event.target.value;
                    setStatus(value);
                    employeesPagination.resetPage();
                    setLoading(true);
                    void fetchData(keyword, value);
                  }}
                  className="h-8 min-w-32 rounded border px-3 text-sm outline-none"
                  style={{ borderColor: "var(--border-color)", backgroundColor: "var(--bg-color-card)", color: "var(--text-color-1)" }}
                >
                  <option value="all">全部状态</option>
                  {Object.entries(statusLabels).map(([key, item]) => (
                    <option key={key} value={key}>{item.label}</option>
                  ))}
                </select>
                <Button
                  type="primary"
                  onClick={() => {
                    employeesPagination.resetPage();
                    setLoading(true);
                    void fetchData(keyword, status);
                  }}
                >
                  搜索
                </Button>
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full min-w-[1120px] table-fixed border-collapse text-sm">
                <colgroup>
                  {employeeTableColumns.map((column) => (
                    <col key={column.label} style={{ width: column.width }} />
                  ))}
                </colgroup>
                <thead>
                  <tr style={{ backgroundColor: "var(--bg-color-page)" }}>
                    {employeeTableColumns.map((column) => (
                      <th key={column.label} className={`px-4 py-3 font-medium whitespace-nowrap ${column.align}`} style={{ color: "var(--text-color-2)" }}>
                        {column.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr>
                      <td colSpan={10} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>
                        加载中...
                      </td>
                    </tr>
                  ) : employees.length === 0 ? (
                    <tr>
                      <td colSpan={10} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>
                        暂无员工信息
                      </td>
                    </tr>
                  ) : employeesPagination.pagedData.map((employee) => {
                    const statusConfig = statusLabels[employee.status] || { label: employee.status, color: "gray" };
                    const role = accessRoleLabels[employee.accessRole] || { label: employee.accessRole, color: "gray" };
                    return (
                      <tr key={employee.id} className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]" style={{ borderColor: "var(--border-color-light)" }}>
                        <td className="px-5 py-5 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{employee.name}</div>
                          <div className="text-xs break-all mt-1" style={{ color: "var(--text-color-3)" }}>{employee.email}</div>
                        </td>
                        <td className="px-4 py-5 align-middle text-center whitespace-nowrap">
                          {employee.departmentName || (employee.departmentId ? departmentMap.get(employee.departmentId) : "--")}
                        </td>
                        <td className="px-4 py-5 align-middle text-center whitespace-nowrap">{employee.position}</td>
                        <td className="px-4 py-5 align-middle whitespace-nowrap">
                          <div className="flex justify-center">
                            <Tag color={statusConfig.color}>{statusConfig.label}</Tag>
                          </div>
                        </td>
                        <td className="px-4 py-5 align-middle whitespace-nowrap">
                          <div className="flex justify-center">
                            <Tag color={role.color}>{role.label}</Tag>
                          </div>
                        </td>
                        <td className="px-4 py-5 align-middle text-center whitespace-nowrap">{accessScopeLabels[employee.accessScope] || employee.accessScope}</td>
                        <td className="px-4 py-5 align-middle text-center whitespace-nowrap">{employmentTypeLabels[employee.employmentType] || employee.employmentType}</td>
                        <td className="px-4 py-5 align-middle text-center whitespace-nowrap">{employee.hireDate}</td>
                        <td className="px-4 py-5 align-middle whitespace-nowrap">
                          <div className="flex justify-end">
                            <AmountDisplay amount={employee.monthlyCost} type={2} />
                          </div>
                        </td>
                        <td className="px-4 py-5 align-middle whitespace-nowrap">
                          <div className="flex justify-center">
                            <Button aria-label={`编辑 ${employee.name}`} type="text" size="mini" icon={<IconEdit />} onClick={() => openEdit(employee)} />
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

        <Col xs={24}>
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <Card style={{ borderRadius: 12 }} title="部门预算">
              <div className="space-y-3">
                {departments.map((department) => (
                  <div key={department.id} className="flex items-center justify-between gap-3">
                    <div>
                      <div className="text-sm font-medium">{department.name}</div>
                      <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{department.costCenter}</div>
                    </div>
                    <AmountDisplay amount={department.budget} size="small" />
                  </div>
                ))}
              </div>
            </Card>

            <Card style={{ borderRadius: 12 }} title="税费待办">
              <div className="space-y-3">
                {pendingTaxes.slice(0, 4).map((item) => (
                  <div key={item.id} className="rounded-lg p-3" style={{ backgroundColor: "var(--bg-color-page)" }}>
                    <div className="text-sm font-medium">{item.name}</div>
                    <div className="flex items-center justify-between mt-2 gap-3">
                      <AmountDisplay amount={item.taxAmount - item.paidAmount} type={2} size="small" />
                      <Tag color={item.status === "pending" ? "orange" : "arcoblue"}>{item.status === "pending" ? "待缴" : "预估"}</Tag>
                    </div>
                    <div className="text-xs mt-1" style={{ color: "var(--text-color-3)" }}>截止 {item.dueDate}</div>
                  </div>
                ))}
              </div>
            </Card>

            <Card style={{ borderRadius: 12 }} title="权限矩阵">
              <div className="space-y-3">
                {permissionMatrix?.matrix.map((item) => {
                  const role = accessRoleLabels[item.role] || { label: item.role, color: "gray" };
                  return (
                    <div key={item.role} className="rounded-lg p-3" style={{ backgroundColor: "var(--bg-color-page)" }}>
                      <div className="flex items-center justify-between gap-2">
                        <Tag color={role.color}>{role.label}</Tag>
                        <span className="text-xs" style={{ color: "var(--text-color-3)" }}>
                          {accessScopeLabels[item.defaultScope] || item.defaultScope}
                        </span>
                      </div>
                      <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>
                        {item.permissions.length} 项权限
                      </div>
                    </div>
                  );
                })}
              </div>
            </Card>
          </div>
        </Col>
      </Row>

      <Modal
        title={editingEmployee ? "编辑员工信息" : "新增员工信息"}
        visible={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={() => form.submit()}
        style={{ width: 720 }}
      >
        <Form form={form} layout="vertical" onSubmit={handleSubmit}>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <FormItem label="姓名" field="name" rules={[{ required: true, message: "请输入姓名" }]}>
                <Input placeholder="员工姓名" />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="邮箱" field="email" rules={[{ required: true, message: "请输入邮箱" }]}>
                <Input placeholder="name@company.com" />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="手机号" field="phone">
                <Input placeholder="手机号" />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="部门" field="departmentId">
                <Select placeholder="选择部门" allowClear>
                  {departments.map((department) => (
                    <Select.Option key={department.id} value={department.id}>{department.name}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="岗位" field="position" rules={[{ required: true, message: "请输入岗位" }]}>
                <Input placeholder="岗位名称" />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="用工类型" field="employmentType" rules={[{ required: true }]}>
                <Select>
                  {Object.entries(employmentTypeLabels).map(([key, label]) => (
                    <Select.Option key={key} value={key}>{label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="状态" field="status" rules={[{ required: true }]}>
                <Select>
                  {Object.entries(statusLabels).map(([key, item]) => (
                    <Select.Option key={key} value={key}>{item.label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="企业角色" field="accessRole" rules={[{ required: true }]}>
                <Select>
                  {Object.entries(accessRoleLabels).map(([key, item]) => (
                    <Select.Option key={key} value={key}>{item.label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="数据范围" field="accessScope" rules={[{ required: true }]}>
                <Select>
                  {Object.entries(accessScopeLabels).map(([key, label]) => (
                    <Select.Option key={key} value={key}>{label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="入职日期" field="hireDate" rules={[{ required: true, message: "请输入入职日期" }]}>
                <Input placeholder="YYYY-MM-DD" />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="离职日期" field="leaveDate">
                <Input placeholder="YYYY-MM-DD" />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="紧急联系人" field="emergencyContact">
                <Input placeholder="姓名和联系方式" />
              </FormItem>
            </Col>
            <Col xs={12} md={6}>
              <FormItem label="工资" field="salary">
                <Input type="number" placeholder="0.00" />
              </FormItem>
            </Col>
            <Col xs={12} md={6}>
              <FormItem label="社保" field="socialInsurance">
                <Input type="number" placeholder="0.00" />
              </FormItem>
            </Col>
            <Col xs={12} md={6}>
              <FormItem label="公积金" field="housingFund">
                <Input type="number" placeholder="0.00" />
              </FormItem>
            </Col>
            <Col xs={12} md={6}>
              <FormItem label="个税估算" field="taxEstimate">
                <Input type="number" placeholder="0.00" />
              </FormItem>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
