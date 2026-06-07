"use client";
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { Button, Card, Drawer, Form, Grid, Input, Message, Modal, Select, Tag } from "@arco-design/web-react";
import { IconEdit, IconPlus, IconSearch, IconUserGroup } from "@arco-design/web-react/icon";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { useAppStore } from "@/lib/stores/appStore";
import type {
  Department,
  Employee,
  EmployeeCertificate,
  EmployeeExperience,
  EmployeePayload,
  EnterpriseSummary,
  PermissionMatrix,
  TaxItem,
} from "@/lib/types";

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

const educationLabels: Record<string, string> = {
  high_school: "高中/中专",
  junior_college: "大专",
  bachelor: "本科",
  master: "硕士",
  doctor: "博士",
  other: "其他",
};

const graduateStatusLabels: Record<string, { label: string; color: string }> = {
  fresh: { label: "应届", color: "green" },
  within_2_years: { label: "毕业 2 年内", color: "arcoblue" },
  within_5_years: { label: "毕业 5 年内", color: "purple" },
  experienced: { label: "往届", color: "gray" },
};

const contractTypeLabels: Record<string, string> = {
  fixed_term: "固定期限",
  open_ended: "无固定期限",
  internship: "实习协议",
  labor_service: "劳务协议",
  contractor: "外包/顾问",
};

const contractStatusLabels: Record<string, { label: string; color: string }> = {
  unsigned: { label: "待签署", color: "orange" },
  active: { label: "有效", color: "green" },
  expiring: { label: "临期", color: "gold" },
  terminated: { label: "已终止", color: "gray" },
};

const materialStatusLabels: Record<string, { label: string; color: string }> = {
  missing: { label: "材料缺失", color: "red" },
  pending: { label: "待核验", color: "orange" },
  verified: { label: "已核验", color: "green" },
  waived: { label: "无需提供", color: "gray" },
};

const certificateVerificationLabels: Record<string, { label: string; color: string }> = {
  unverified: { label: "未核验", color: "orange" },
  pending: { label: "核验中", color: "arcoblue" },
  verified: { label: "已核验", color: "green" },
  expired: { label: "已过期", color: "red" },
};

const experienceTypeLabels: Record<string, string> = {
  work: "工作经历",
  project: "项目经历",
  education: "教育经历",
  training: "培训经历",
};

const employeeTableColumns = [
  { label: "员工", width: "18%", align: "text-left" },
  { label: "部门", width: "9%", align: "text-center" },
  { label: "岗位", width: "11%", align: "text-center" },
  { label: "学历/技能", width: "13%", align: "text-center" },
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

const emptyCertificate = (): EmployeeCertificate => ({
  name: "",
  category: "",
  level: "",
  issuer: "",
  certificateNo: "",
  issueDate: "",
  expiryDate: "",
  verificationStatus: "unverified",
  materialStatus: "missing",
  note: "",
});

const emptyExperience = (): EmployeeExperience => ({
  type: "work",
  organization: "",
  title: "",
  startDate: "",
  endDate: "",
  description: "",
  achievements: "",
  skills: "",
});

const formatCurrency = (value: unknown) => `¥${money(value).toLocaleString("zh-CN", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})}`;

const formatPercent = (value: unknown) => {
  const normalized = money(value).toFixed(2);
  return `${normalized.replace(/\.00$/, "")}%`;
};

const compactDate = (value?: string | null) => value || "--";

function employeeProfileCompleteness(employee: Employee) {
  const checks = [
    Boolean(employee.educationLevel),
    Boolean(employee.graduationYear || employee.graduationDate),
    Boolean(employee.certificates?.length),
    Boolean(employee.resumeSummary || employee.experiences?.length),
    Boolean(employee.contractEndDate),
    employee.materialStatus === "verified",
  ];
  return Math.round((checks.filter(Boolean).length / checks.length) * 100);
}

function DetailItem({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <div className="text-xs" style={{ color: "var(--text-color-3)" }}>{label}</div>
      <div className="mt-1 text-sm font-medium break-words" style={{ color: "var(--text-color-1)" }}>{value || "--"}</div>
    </div>
  );
}

function DetailPanel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section
      className="rounded-xl border p-4"
      style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-bg-2)" }}
    >
      <div className="mb-4 font-medium" style={{ color: "var(--text-color-1)" }}>{title}</div>
      {children}
    </section>
  );
}

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
  const [selectedEmployee, setSelectedEmployee] = useState<Employee | null>(null);
  const [certificateDrafts, setCertificateDrafts] = useState<EmployeeCertificate[]>([]);
  const [experienceDrafts, setExperienceDrafts] = useState<EmployeeExperience[]>([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [form] = Form.useForm();
  const employeesPagination = useClientPagination(employees, 10);

  const departmentMap = useMemo(
    () => new Map(departments.map((department) => [department.id, department.name])),
    [departments]
  );
  const permissionNameMap = useMemo(
    () => new Map((permissionMatrix?.permissions || []).map((permission) => [permission.key, permission.name])),
    [permissionMatrix]
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

  const updateCertificateDraft = (index: number, patch: Partial<EmployeeCertificate>) => {
    setCertificateDrafts((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item));
  };

  const updateExperienceDraft = (index: number, patch: Partial<EmployeeExperience>) => {
    setExperienceDrafts((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item));
  };

  const openCreate = () => {
    setSelectedEmployee(null);
    setEditingEmployee(null);
    setCertificateDrafts([emptyCertificate()]);
    setExperienceDrafts([emptyExperience()]);
    form.resetFields();
    form.setFieldsValue({
      employmentType: "full_time",
      status: "onboarding",
      accessRole: "employee",
      accessScope: "self",
      contractType: "fixed_term",
      contractStatus: "unsigned",
      materialStatus: "missing",
      hireDate: new Date().toISOString().slice(0, 10),
      salary: 0,
      socialInsurance: 0,
      housingFund: 0,
      taxEstimate: 0,
    });
    setModalVisible(true);
  };

  const openEdit = (employee: Employee) => {
    setSelectedEmployee(null);
    setEditingEmployee(employee);
    setCertificateDrafts(employee.certificates?.length ? employee.certificates : [emptyCertificate()]);
    setExperienceDrafts(employee.experiences?.length ? employee.experiences : [emptyExperience()]);
    form.setFieldsValue({
      ...employee,
      departmentId: employee.departmentId || undefined,
      leaveDate: employee.leaveDate || undefined,
      directManagerEmployeeId: employee.directManagerEmployeeId || undefined,
      graduationYear: employee.graduationYear || undefined,
      profileVerifiedBy: employee.profileVerifiedBy || undefined,
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
    directManagerEmployeeId: values.directManagerEmployeeId || null,
    graduationYear: values.graduationYear ? Number(values.graduationYear) : null,
    profileVerifiedBy: values.profileVerifiedBy || null,
    certificates: certificateDrafts.filter((item) => (item.name || "").trim()),
    experiences: experienceDrafts.filter((item) =>
      (item.organization || "").trim() || (item.title || "").trim() || (item.description || "").trim()
    ),
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
  const selectedStatus = selectedEmployee
    ? statusLabels[selectedEmployee.status] || { label: selectedEmployee.status, color: "gray" }
    : null;
  const selectedRole = selectedEmployee
    ? accessRoleLabels[selectedEmployee.accessRole] || { label: selectedEmployee.accessRole, color: "gray" }
    : null;
  const selectedMatrix = selectedEmployee
    ? permissionMatrix?.matrix.find((item) => item.role === selectedEmployee.accessRole)
    : null;
  const selectedRoleDefinition = selectedEmployee
    ? permissionMatrix?.roles.find((item) => item.key === selectedEmployee.accessRole)
    : null;
  const selectedDirectManager = selectedEmployee?.directManagerEmployeeId
    ? employees.find((employee) => employee.id === selectedEmployee.directManagerEmployeeId)
    : null;
  const selectedSeveranceEstimate = selectedEmployee && selectedEmployee.status === "departed"
    ? money(selectedEmployee.salary)
    : 0;

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
              <table className="w-full min-w-[1280px] table-fixed border-collapse text-sm">
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
                      <td colSpan={employeeTableColumns.length} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>
                        加载中...
                      </td>
                    </tr>
                  ) : employees.length === 0 ? (
                    <tr>
                      <td colSpan={employeeTableColumns.length} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>
                        暂无员工信息
                      </td>
                    </tr>
                  ) : employeesPagination.pagedData.map((employee) => {
                    const statusConfig = statusLabels[employee.status] || { label: employee.status, color: "gray" };
                    const role = accessRoleLabels[employee.accessRole] || { label: employee.accessRole, color: "gray" };
                    return (
                      <tr
                        key={employee.id}
                        className="cursor-pointer border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
                        style={{ borderColor: "var(--border-color-light)" }}
                        onClick={() => setSelectedEmployee(employee)}
                      >
                        <td className="px-5 py-5 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{employee.name}</div>
                          <div className="text-xs break-all mt-1" style={{ color: "var(--text-color-3)" }}>{employee.email}</div>
                        </td>
                        <td className="px-4 py-5 align-middle text-center whitespace-nowrap">
                          {employee.departmentName || (employee.departmentId ? departmentMap.get(employee.departmentId) : "--")}
                        </td>
                        <td className="px-4 py-5 align-middle text-center whitespace-nowrap">{employee.position}</td>
                        <td className="px-4 py-5 align-middle text-center">
                          <div className="flex flex-col items-center gap-1">
                            <span className="text-xs" style={{ color: "var(--text-color-2)" }}>
                              {educationLabels[employee.educationLevel || ""] || employee.educationLevel || "学历待补"}
                              {employee.graduationYear ? ` · ${employee.graduationYear}` : ""}
                            </span>
                            <div className="flex flex-wrap justify-center gap-1">
                              <Tag size="small" color={(employee.certificates?.length || 0) > 0 ? "green" : "gray"}>
                                证书 {employee.certificates?.length || 0}
                              </Tag>
                              <Tag size="small" color={employeeProfileCompleteness(employee) >= 70 ? "arcoblue" : "orange"}>
                                {employeeProfileCompleteness(employee)}%
                              </Tag>
                            </div>
                          </div>
                        </td>
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
                            <Button
                              aria-label={`编辑 ${employee.name}`}
                              type="text"
                              size="mini"
                              icon={<IconEdit />}
                              onClick={(event) => {
                                event.stopPropagation();
                                openEdit(employee);
                              }}
                            />
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

      <Drawer
        title="员工详情"
        visible={Boolean(selectedEmployee)}
        width={720}
        onCancel={() => setSelectedEmployee(null)}
        footer={selectedEmployee ? (
          <div className="flex justify-end gap-2">
            <Button onClick={() => setSelectedEmployee(null)}>关闭</Button>
            <Button type="primary" icon={<IconEdit />} onClick={() => openEdit(selectedEmployee)}>
              编辑员工信息
            </Button>
          </div>
        ) : null}
      >
        {selectedEmployee && (
          <div className="space-y-4">
            <div
              className="rounded-2xl border p-5"
              style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
            >
              <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="text-xl font-semibold" style={{ color: "var(--text-color-1)" }}>{selectedEmployee.name}</div>
                    {selectedStatus && <Tag color={selectedStatus.color}>{selectedStatus.label}</Tag>}
                    {selectedRole && <Tag color={selectedRole.color}>{selectedRole.label}</Tag>}
                  </div>
                  <div className="mt-2 text-sm" style={{ color: "var(--text-color-3)" }}>
                    {(selectedEmployee.departmentName || (selectedEmployee.departmentId ? departmentMap.get(selectedEmployee.departmentId) : "未分配部门") || "未分配部门")} · {selectedEmployee.position}
                  </div>
                  <div className="mt-1 break-all text-sm" style={{ color: "var(--text-color-3)" }}>{selectedEmployee.email}</div>
                </div>
                <div className="text-right">
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>月人力成本</div>
                  <AmountDisplay amount={selectedEmployee.monthlyCost} type={2} size="large" />
                </div>
              </div>
            </div>

            <DetailPanel title="基础档案">
              <div className="grid gap-4 md:grid-cols-3">
                <DetailItem label="员工编号" value={selectedEmployee.employeeNo || "--"} />
                <DetailItem label="法定姓名" value={selectedEmployee.legalName || selectedEmployee.name} />
                <DetailItem label="常用名" value={selectedEmployee.preferredName || "--"} />
                <DetailItem label="手机号" value={selectedEmployee.phone || "--"} />
                <DetailItem label="工作地点" value={selectedEmployee.workLocation || "--"} />
                <DetailItem label="职级/岗级" value={selectedEmployee.jobLevel || "--"} />
                <DetailItem label="用工类型" value={employmentTypeLabels[selectedEmployee.employmentType] || selectedEmployee.employmentType} />
                <DetailItem label="员工状态" value={selectedStatus ? <Tag color={selectedStatus.color}>{selectedStatus.label}</Tag> : selectedEmployee.status} />
                <DetailItem label="入职日期" value={selectedEmployee.hireDate} />
                <DetailItem label="离职日期" value={selectedEmployee.leaveDate || "--"} />
                <DetailItem label="紧急联系人" value={selectedEmployee.emergencyContact || "--"} />
              </div>
            </DetailPanel>

            <DetailPanel title="组织与权限">
              <div className="grid gap-4 md:grid-cols-3">
                <DetailItem
                  label="所属部门"
                  value={selectedEmployee.departmentName || (selectedEmployee.departmentId ? departmentMap.get(selectedEmployee.departmentId) : "--")}
                />
                <DetailItem label="直属上级" value={selectedDirectManager?.name || "--"} />
                <DetailItem label="企业角色" value={selectedRole ? <Tag color={selectedRole.color}>{selectedRole.label}</Tag> : selectedEmployee.accessRole} />
                <DetailItem label="数据范围" value={accessScopeLabels[selectedEmployee.accessScope] || selectedEmployee.accessScope} />
              </div>
              {selectedRoleDefinition && (
                <div className="mt-4 rounded-lg px-3 py-2 text-sm" style={{ backgroundColor: "var(--color-fill-1)", color: "var(--text-color-3)" }}>
                  {selectedRoleDefinition.description}
                </div>
              )}
              {selectedMatrix && (
                <div className="mt-4">
                  <div className="mb-2 text-xs" style={{ color: "var(--text-color-3)" }}>权限项</div>
                  <div className="flex flex-wrap gap-2">
                    {selectedMatrix.permissions.slice(0, 12).map((permission) => (
                      <Tag key={permission} color="arcoblue">{permissionNameMap.get(permission) || permission}</Tag>
                    ))}
                    {selectedMatrix.permissions.length > 12 && (
                      <Tag color="gray">+{selectedMatrix.permissions.length - 12}</Tag>
                    )}
                  </div>
                </div>
              )}
            </DetailPanel>

            <DetailPanel title="教育与政策画像">
              <div className="grid gap-4 md:grid-cols-3">
                <DetailItem label="最高学历" value={educationLabels[selectedEmployee.educationLevel || ""] || selectedEmployee.educationLevel || "--"} />
                <DetailItem label="毕业院校" value={selectedEmployee.graduationSchool || "--"} />
                <DetailItem label="专业" value={selectedEmployee.major || "--"} />
                <DetailItem label="毕业年份" value={selectedEmployee.graduationYear || "--"} />
                <DetailItem label="毕业日期" value={selectedEmployee.graduationDate || "--"} />
                <DetailItem
                  label="毕业生身份"
                  value={selectedEmployee.graduateStatus
                    ? <Tag color={graduateStatusLabels[selectedEmployee.graduateStatus]?.color || "gray"}>{graduateStatusLabels[selectedEmployee.graduateStatus]?.label || selectedEmployee.graduateStatus}</Tag>
                    : "--"}
                />
                <DetailItem
                  label="材料状态"
                  value={selectedEmployee.materialStatus
                    ? <Tag color={materialStatusLabels[selectedEmployee.materialStatus]?.color || "gray"}>{materialStatusLabels[selectedEmployee.materialStatus]?.label || selectedEmployee.materialStatus}</Tag>
                    : "--"}
                />
                <DetailItem label="核验日期" value={selectedEmployee.profileVerifiedAt || "--"} />
                <DetailItem label="档案完整度" value={`${employeeProfileCompleteness(selectedEmployee)}%`} />
              </div>
              {selectedEmployee.skillTags && (
                <div className="mt-4">
                  <div className="mb-2 text-xs" style={{ color: "var(--text-color-3)" }}>技能标签</div>
                  <div className="flex flex-wrap gap-2">
                    {selectedEmployee.skillTags.split(/[，,]/).filter(Boolean).map((tag) => (
                      <Tag key={tag.trim()} color="arcoblue">{tag.trim()}</Tag>
                    ))}
                  </div>
                </div>
              )}
            </DetailPanel>

            <DetailPanel title="技能证书">
              {selectedEmployee.certificates?.length ? (
                <div className="space-y-3">
                  {selectedEmployee.certificates.map((certificate) => {
                    const verification = certificateVerificationLabels[certificate.verificationStatus || "unverified"] || certificateVerificationLabels.unverified;
                    const material = materialStatusLabels[certificate.materialStatus || "missing"] || materialStatusLabels.missing;
                    return (
                      <div key={certificate.id || certificate.name} className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}>
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="min-w-0">
                            <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{certificate.name}</div>
                            <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                              {[certificate.category, certificate.level, certificate.issuer].filter(Boolean).join(" · ") || "证书信息待补"}
                            </div>
                          </div>
                          <div className="flex flex-wrap gap-1">
                            <Tag color={verification.color}>{verification.label}</Tag>
                            <Tag color={material.color}>{material.label}</Tag>
                          </div>
                        </div>
                        <div className="mt-3 grid gap-2 text-xs md:grid-cols-3" style={{ color: "var(--text-color-3)" }}>
                          <span>编号 {certificate.certificateNo || "--"}</span>
                          <span>发证 {compactDate(certificate.issueDate)}</span>
                          <span>到期 {compactDate(certificate.expiryDate)}</span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="text-sm" style={{ color: "var(--text-color-3)" }}>暂无技能证书</div>
              )}
            </DetailPanel>

            <DetailPanel title="个人履历">
              {selectedEmployee.resumeSummary && (
                <div className="mb-4 rounded-lg px-3 py-2 text-sm leading-6" style={{ backgroundColor: "var(--color-fill-1)", color: "var(--text-color-2)" }}>
                  {selectedEmployee.resumeSummary}
                </div>
              )}
              {selectedEmployee.experiences?.length ? (
                <div className="space-y-3">
                  {selectedEmployee.experiences.map((experience) => (
                    <div key={experience.id || `${experience.organization}-${experience.title}`} className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}>
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div>
                          <div className="font-medium">{experience.organization}</div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                            {experience.title || experienceTypeLabels[experience.type] || experience.type} · {compactDate(experience.startDate)} - {compactDate(experience.endDate)}
                          </div>
                        </div>
                        <Tag color="arcoblue">{experienceTypeLabels[experience.type] || experience.type}</Tag>
                      </div>
                      {experience.description && <div className="mt-3 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>{experience.description}</div>}
                      {experience.achievements && <div className="mt-2 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>成果：{experience.achievements}</div>}
                      {experience.skills && <div className="mt-2 text-xs leading-5" style={{ color: "var(--text-color-3)" }}>技能：{experience.skills}</div>}
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-sm" style={{ color: "var(--text-color-3)" }}>暂无结构化履历</div>
              )}
            </DetailPanel>

            <DetailPanel title="薪酬与成本">
              <div className="grid gap-3 md:grid-cols-4">
                <div className="rounded-lg p-3" style={{ backgroundColor: "rgba(239, 68, 68, 0.06)" }}>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>月人力成本</div>
                  <AmountDisplay amount={selectedEmployee.monthlyCost} type={2} size="medium" />
                </div>
                <div className="rounded-lg p-3" style={{ backgroundColor: "var(--color-fill-1)" }}>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>应发工资</div>
                  <AmountDisplay amount={selectedEmployee.salary} size="medium" />
                </div>
                <div className="rounded-lg p-3" style={{ backgroundColor: "rgba(16, 185, 129, 0.06)" }}>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>个人到账</div>
                  <AmountDisplay amount={selectedEmployee.netPayEstimate} type={1} size="medium" />
                </div>
                <div className="rounded-lg p-3" style={{ backgroundColor: "var(--color-fill-1)" }}>
                  <div className="text-xs" style={{ color: "var(--text-color-3)" }}>个税估算</div>
                  <AmountDisplay amount={selectedEmployee.taxEstimate} type={2} size="medium" />
                </div>
              </div>
              <div className="mt-4 rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}>
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <div className="text-sm font-medium">离职/裁员补偿</div>
                    <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                      一次性人力成本，和固定月薪分开复盘
                    </div>
                  </div>
                  <div className="text-right">
                    <AmountDisplay amount={selectedSeveranceEstimate} type={2} size="medium" />
                    <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                      {selectedEmployee.status === "departed" ? "按 1 个月工资占位估算" : "暂无离职补偿"}
                    </div>
                  </div>
                </div>
              </div>
              <div className="mt-4 grid gap-3 md:grid-cols-2">
                <DetailItem label="社保个人/公司" value={`${formatCurrency(selectedEmployee.socialInsurancePersonalAmount)} / ${formatCurrency(selectedEmployee.socialInsuranceCompanyAmount || selectedEmployee.socialInsurance)}`} />
                <DetailItem label="公积金个人/公司" value={`${formatCurrency(selectedEmployee.housingFundPersonalAmount)} / ${formatCurrency(selectedEmployee.housingFundCompanyAmount || selectedEmployee.housingFund)}`} />
                <DetailItem label="其他个人扣减" value={formatCurrency(selectedEmployee.personalDeduction)} />
                <DetailItem label="公司承担缴费" value={formatCurrency(money(selectedEmployee.socialInsuranceCompanyAmount || selectedEmployee.socialInsurance) + money(selectedEmployee.housingFundCompanyAmount || selectedEmployee.housingFund))} />
              </div>
            </DetailPanel>

            <DetailPanel title="社保与公积金">
              <div className="grid gap-4 md:grid-cols-3">
                <DetailItem label="参保地区" value={selectedEmployee.socialInsuranceRegion || "--"} />
                <DetailItem label="户籍类型" value={selectedEmployee.hukouType === "local" ? "深户" : "非深户"} />
                <DetailItem label="医保档次" value={selectedEmployee.medicalTier === "tier2" ? "医保二档" : "医保一档"} />
                <DetailItem label="养老基数" value={formatCurrency(selectedEmployee.pensionBase || selectedEmployee.socialInsuranceBase)} />
                <DetailItem label="医疗基数" value={formatCurrency(selectedEmployee.medicalBase)} />
                <DetailItem label="失业基数" value={formatCurrency(selectedEmployee.unemploymentBase)} />
                <DetailItem label="工伤基数" value={formatCurrency(selectedEmployee.workInjuryBase)} />
                <DetailItem label="公积金基数" value={formatCurrency(selectedEmployee.housingFundBase)} />
                <DetailItem
                  label="公积金比例"
                  value={`个人 ${formatPercent(selectedEmployee.housingFundPersonalRate)} / 公司 ${formatPercent(selectedEmployee.housingFundCompanyRate)}`}
                />
              </div>
              {selectedEmployee.socialInsuranceItems && selectedEmployee.socialInsuranceItems.length > 0 && (
                <div className="mt-4 space-y-2">
                  {selectedEmployee.socialInsuranceItems.map((item) => (
                    <div
                      key={item.key}
                      className="grid grid-cols-[88px_1fr_1fr] gap-3 rounded-lg px-3 py-2 text-xs"
                      style={{ backgroundColor: "var(--color-fill-1)", color: "var(--text-color-3)" }}
                    >
                      <span className="font-medium" style={{ color: "var(--text-color-2)" }}>{item.name}</span>
                      <span>基数 {formatCurrency(item.base)}</span>
                      <span>个人 {formatCurrency(item.personalAmount)} / 公司 {formatCurrency(item.companyAmount)}</span>
                    </div>
                  ))}
                </div>
              )}
            </DetailPanel>

            <DetailPanel title="合同、考勤与绩效">
              <div className="grid gap-3 md:grid-cols-3">
                <div className="rounded-lg p-3" style={{ backgroundColor: "var(--color-fill-1)" }}>
                  <div className="text-sm font-medium">劳动合同</div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    起始 {selectedEmployee.contractStartDate || selectedEmployee.hireDate} · 到期 {selectedEmployee.contractEndDate || "未录入"}
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    {contractTypeLabels[selectedEmployee.contractType || ""] || selectedEmployee.contractType || "合同类型待补"}
                  </div>
                  <Tag className="mt-3" color={contractStatusLabels[selectedEmployee.contractStatus || "active"]?.color || "green"}>
                    {contractStatusLabels[selectedEmployee.contractStatus || "active"]?.label || selectedEmployee.contractStatus || "有效"}
                  </Tag>
                </div>
                <div className="rounded-lg p-3" style={{ backgroundColor: "var(--color-fill-1)" }}>
                  <div className="text-sm font-medium">试用期</div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    起始 {selectedEmployee.probationStartDate || selectedEmployee.hireDate} · 结束 {selectedEmployee.probationEndDate || "未录入"}
                  </div>
                  <Tag className="mt-3" color={selectedEmployee.status === "probation" ? "arcoblue" : "gray"}>
                    {selectedEmployee.status === "probation" ? "试用中" : "非试用状态"}
                  </Tag>
                </div>
                <div className="rounded-lg p-3" style={{ backgroundColor: "var(--color-fill-1)" }}>
                  <div className="text-sm font-medium">绩效记录</div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>最近评级 未录入 · 绩效奖金 未录入</div>
                  <Tag className="mt-3" color="gray">待维护</Tag>
                </div>
              </div>
            </DetailPanel>
          </div>
        )}
      </Drawer>

      <Modal
        title={editingEmployee ? "编辑员工信息" : "新增员工信息"}
        visible={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={() => form.submit()}
        style={{ width: 960 }}
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
            <Col xs={24} md={8}>
              <FormItem label="员工编号" field="employeeNo">
                <Input placeholder="例如 EMP-0001" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="法定姓名" field="legalName">
                <Input placeholder="证件姓名" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="常用名" field="preferredName">
                <Input placeholder="英文名/昵称" />
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
            <Col xs={24} md={8}>
              <FormItem label="直属上级" field="directManagerEmployeeId">
                <Select placeholder="选择直属上级" allowClear>
                  {employees
                    .filter((employee) => employee.id !== editingEmployee?.id)
                    .map((employee) => (
                      <Select.Option key={employee.id} value={employee.id}>{employee.name}</Select.Option>
                    ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="职级/岗级" field="jobLevel">
                <Input placeholder="例如 P5 / M2 / L3" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="工作地点" field="workLocation">
                <Input placeholder="例如 深圳南山" />
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
            <Col xs={24} md={8}>
              <FormItem label="试用期开始" field="probationStartDate">
                <Input placeholder="YYYY-MM-DD" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="试用期结束" field="probationEndDate">
                <Input placeholder="YYYY-MM-DD" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="合同类型" field="contractType">
                <Select allowClear>
                  {Object.entries(contractTypeLabels).map(([key, label]) => (
                    <Select.Option key={key} value={key}>{label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="合同开始" field="contractStartDate">
                <Input placeholder="YYYY-MM-DD" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="合同结束" field="contractEndDate">
                <Input placeholder="YYYY-MM-DD" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="合同状态" field="contractStatus">
                <Select allowClear>
                  {Object.entries(contractStatusLabels).map(([key, item]) => (
                    <Select.Option key={key} value={key}>{item.label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="紧急联系人" field="emergencyContact">
                <Input placeholder="姓名和联系方式" />
              </FormItem>
            </Col>
            <Col xs={24} md={12}>
              <FormItem label="材料状态" field="materialStatus">
                <Select allowClear>
                  {Object.entries(materialStatusLabels).map(([key, item]) => (
                    <Select.Option key={key} value={key}>{item.label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="最高学历" field="educationLevel">
                <Select allowClear>
                  {Object.entries(educationLabels).map(([key, label]) => (
                    <Select.Option key={key} value={key}>{label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="毕业院校" field="graduationSchool">
                <Input placeholder="学校/院校" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="专业" field="major">
                <Input placeholder="专业名称" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="毕业年份" field="graduationYear">
                <Input type="number" placeholder="例如 2026" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="毕业日期" field="graduationDate">
                <Input placeholder="YYYY-MM-DD" />
              </FormItem>
            </Col>
            <Col xs={24} md={8}>
              <FormItem label="毕业生身份" field="graduateStatus">
                <Select allowClear>
                  {Object.entries(graduateStatusLabels).map(([key, item]) => (
                    <Select.Option key={key} value={key}>{item.label}</Select.Option>
                  ))}
                </Select>
              </FormItem>
            </Col>
            <Col xs={24}>
              <FormItem label="技能标签" field="skillTags">
                <Input placeholder="用逗号分隔，例如 Java, React, 财务分析" />
              </FormItem>
            </Col>
            <Col xs={24}>
              <FormItem label="履历摘要" field="resumeSummary">
                <Input.TextArea placeholder="概述教育背景、行业经验、代表项目和能力亮点" autoSize={{ minRows: 3, maxRows: 6 }} />
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
          <div className="mt-2 rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
            <div className="mb-3 flex items-center justify-between gap-3">
              <div className="font-medium" style={{ color: "var(--text-color-1)" }}>技能证书</div>
              <Button htmlType="button" size="small" type="outline" onClick={() => setCertificateDrafts((current) => [...current, emptyCertificate()])}>
                添加证书
              </Button>
            </div>
            <div className="space-y-3">
              {certificateDrafts.map((certificate, index) => (
                <div key={index} className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}>
                  <div className="mb-3 flex items-center justify-between gap-3">
                    <span className="text-sm font-medium">证书 {index + 1}</span>
                    <Button htmlType="button" size="mini" status="danger" type="text" onClick={() => setCertificateDrafts((current) => current.filter((_, itemIndex) => itemIndex !== index))}>
                      删除
                    </Button>
                  </div>
                  <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                    <Input
                      placeholder="证书名称"
                      value={certificate.name || ""}
                      onChange={(value) => updateCertificateDraft(index, { name: value })}
                    />
                    <Input
                      placeholder="类别，例如 职业资格"
                      value={certificate.category || ""}
                      onChange={(value) => updateCertificateDraft(index, { category: value })}
                    />
                    <Input
                      placeholder="等级，例如 高级"
                      value={certificate.level || ""}
                      onChange={(value) => updateCertificateDraft(index, { level: value })}
                    />
                    <Input
                      placeholder="发证机构"
                      value={certificate.issuer || ""}
                      onChange={(value) => updateCertificateDraft(index, { issuer: value })}
                    />
                    <Input
                      placeholder="证书编号"
                      value={certificate.certificateNo || ""}
                      onChange={(value) => updateCertificateDraft(index, { certificateNo: value })}
                    />
                    <Input
                      placeholder="发证日期 YYYY-MM-DD"
                      value={certificate.issueDate || ""}
                      onChange={(value) => updateCertificateDraft(index, { issueDate: value })}
                    />
                    <Input
                      placeholder="有效期至 YYYY-MM-DD"
                      value={certificate.expiryDate || ""}
                      onChange={(value) => updateCertificateDraft(index, { expiryDate: value })}
                    />
                    <Select
                      value={certificate.verificationStatus || "unverified"}
                      onChange={(value) => updateCertificateDraft(index, { verificationStatus: value })}
                    >
                      {Object.entries(certificateVerificationLabels).map(([key, item]) => (
                        <Select.Option key={key} value={key}>{item.label}</Select.Option>
                      ))}
                    </Select>
                    <Select
                      value={certificate.materialStatus || "missing"}
                      onChange={(value) => updateCertificateDraft(index, { materialStatus: value })}
                    >
                      {Object.entries(materialStatusLabels).map(([key, item]) => (
                        <Select.Option key={key} value={key}>{item.label}</Select.Option>
                      ))}
                    </Select>
                  </div>
                  <Input
                    className="mt-3"
                    placeholder="备注，例如证书查验链接、补贴关联项目"
                    value={certificate.note || ""}
                    onChange={(value) => updateCertificateDraft(index, { note: value })}
                  />
                </div>
              ))}
            </div>
          </div>

          <div className="mt-4 rounded-xl border p-4" style={{ borderColor: "var(--border-color-light)" }}>
            <div className="mb-3 flex items-center justify-between gap-3">
              <div className="font-medium" style={{ color: "var(--text-color-1)" }}>个人履历</div>
              <Button htmlType="button" size="small" type="outline" onClick={() => setExperienceDrafts((current) => [...current, emptyExperience()])}>
                添加履历
              </Button>
            </div>
            <div className="space-y-3">
              {experienceDrafts.map((experience, index) => (
                <div key={index} className="rounded-lg border p-3" style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}>
                  <div className="mb-3 flex items-center justify-between gap-3">
                    <span className="text-sm font-medium">履历 {index + 1}</span>
                    <Button htmlType="button" size="mini" status="danger" type="text" onClick={() => setExperienceDrafts((current) => current.filter((_, itemIndex) => itemIndex !== index))}>
                      删除
                    </Button>
                  </div>
                  <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
                    <Select
                      value={experience.type || "work"}
                      onChange={(value) => updateExperienceDraft(index, { type: value })}
                    >
                      {Object.entries(experienceTypeLabels).map(([key, label]) => (
                        <Select.Option key={key} value={key}>{label}</Select.Option>
                      ))}
                    </Select>
                    <Input
                      placeholder="组织/公司/项目"
                      value={experience.organization || ""}
                      onChange={(value) => updateExperienceDraft(index, { organization: value })}
                    />
                    <Input
                      placeholder="岗位/角色"
                      value={experience.title || ""}
                      onChange={(value) => updateExperienceDraft(index, { title: value })}
                    />
                    <Input
                      placeholder="技能关键词"
                      value={experience.skills || ""}
                      onChange={(value) => updateExperienceDraft(index, { skills: value })}
                    />
                    <Input
                      placeholder="开始 YYYY-MM"
                      value={experience.startDate || ""}
                      onChange={(value) => updateExperienceDraft(index, { startDate: value })}
                    />
                    <Input
                      placeholder="结束 YYYY-MM"
                      value={experience.endDate || ""}
                      onChange={(value) => updateExperienceDraft(index, { endDate: value })}
                    />
                    <Input
                      className="md:col-span-2"
                      placeholder="成果摘要"
                      value={experience.achievements || ""}
                      onChange={(value) => updateExperienceDraft(index, { achievements: value })}
                    />
                  </div>
                  <Input.TextArea
                    className="mt-3"
                    placeholder="职责、项目范围或经历说明"
                    value={experience.description || ""}
                    autoSize={{ minRows: 2, maxRows: 4 }}
                    onChange={(value) => updateExperienceDraft(index, { description: value })}
                  />
                </div>
              ))}
            </div>
          </div>
        </Form>
      </Modal>
    </div>
  );
}
