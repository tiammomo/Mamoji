"use client";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, Card, Grid, Input, InputNumber, Message, Modal, Select, Tag } from "@arco-design/web-react";
import { IconDownload, IconEdit, IconIdcard, IconSearch } from "@arco-design/web-react/icon";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { useAppStore } from "@/lib/stores/appStore";
import type { Department, Employee, EnterpriseSummary, SocialInsuranceItem } from "@/lib/types";

const { Row, Col } = Grid;

const statusLabels: Record<string, { label: string; color: string }> = {
  onboarding: { label: "待入职", color: "orange" },
  probation: { label: "试用期", color: "arcoblue" },
  active: { label: "在职", color: "green" },
  departed: { label: "已离职", color: "gray" },
};

const tableColumns = [
  { label: "员工", width: "13%", align: "text-left" },
  { label: "应发 / 实发", width: "11%", align: "text-right" },
  { label: "五险明细", width: "31%", align: "text-left" },
  { label: "公积金口径", width: "13%", align: "text-left" },
  { label: "员工扣缴", width: "11%", align: "text-right" },
  { label: "公司月成本", width: "11%", align: "text-right" },
  { label: "状态", width: "5%", align: "text-center" },
  { label: "操作", width: "5%", align: "text-center" },
] as const;

const SHENZHEN_POLICY = {
  region: "深圳",
  pension: { min: 4775, max: 27549, personalRate: 8, companyRate: 16, validPeriod: "2025-07 至 2026-06" },
  medicalTier1: { min: 6727, max: 33633, personalRate: 2, companyRate: 6, validPeriod: "2026 年" },
  medicalTier2: { min: 6727, max: 33633, personalRate: 0.5, companyRate: 1.5, validPeriod: "2026 年" },
  maternity: { min: 6727, max: 33633, personalRate: 0, companyRate: 0.5, validPeriod: "2026 年" },
  unemployment: { min: 2520, max: 44265, personalRate: 0.2, companyRate: 0.8, validPeriod: "2025-07 至 2026-06" },
  workInjury: { min: 2520, max: null as number | null, personalRate: 0, companyRate: 0.2, validPeriod: "2024-07 起" },
};

const policyRows = [
  { name: "养老", range: "4775 - 27549", rate: "个人 8% / 公司 16%", period: SHENZHEN_POLICY.pension.validPeriod },
  { name: "医疗/生育", range: "6727 - 33633", rate: "一档 2%/6%，二档 0.5%/1.5%，生育公司 0.5%", period: SHENZHEN_POLICY.medicalTier1.validPeriod },
  { name: "失业", range: "2520 - 44265", rate: "个人 0.2% / 公司 0.8%", period: SHENZHEN_POLICY.unemployment.validPeriod },
  { name: "工伤", range: "不低于 2520", rate: "公司 0.2% - 1.4%，个人不缴", period: SHENZHEN_POLICY.workInjury.validPeriod },
];

type CompensationFormValues = {
  salary: number;
  socialInsuranceRegion: string;
  hukouType: "local" | "non_local";
  medicalTier: "tier1" | "tier2";
  pensionBase: number;
  medicalBase: number;
  maternityBase: number;
  unemploymentBase: number;
  workInjuryBase: number;
  workInjuryCompanyRate: number;
  housingFundBase: number;
  housingFundPersonalRate: number;
  housingFundCompanyRate: number;
  taxEstimate: number;
  personalDeduction: number;
};

type PayrollSnapshot = {
  salary: number;
  socialPersonalAmount: number;
  socialCompanyAmount: number;
  housingPersonalAmount: number;
  housingCompanyAmount: number;
  personalContribution: number;
  companyContribution: number;
  socialItems: SocialInsuranceItem[];
  socialWarnings: string[];
  taxEstimate: number;
  personalDeduction: number;
  netPayEstimate: number;
  monthlyCost: number;
};

const money = (value: unknown) => Number(value || 0);

const roundMoney = (value: number) => Math.round(value * 100) / 100;

const contribution = (base: number, rate: number) => roundMoney((money(base) * money(rate)) / 100);

const currencyText = (value: number) => `¥${money(value).toLocaleString("zh-CN", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})}`;

const currencyShort = (value: number) => `¥${money(value).toLocaleString("zh-CN", {
  maximumFractionDigits: 0,
})}`;

const formatPercent = (value: number) => {
  const normalized = money(value).toFixed(2);
  return `${normalized.replace(/\.00$/, "")}%`;
};

const clampBase = (label: string, value: number, min: number, max: number | null, warnings: string[]) => {
  const safeValue = money(value);
  if (safeValue < min) {
    warnings.push(`${label}低于深圳当前下限，已按 ${currencyText(min)} 测算`);
    return min;
  }
  if (max !== null && safeValue > max) {
    warnings.push(`${label}高于深圳当前上限，已按 ${currencyText(max)} 测算`);
    return max;
  }
  return safeValue;
};

const makeSocialItem = ({
  key,
  name,
  category,
  base,
  minBase,
  maxBase,
  personalRate,
  companyRate,
  policyBasis,
  validPeriod,
}: {
  key: string;
  name: string;
  category: string;
  base: number;
  minBase?: number | null;
  maxBase?: number | null;
  personalRate: number;
  companyRate: number;
  policyBasis: string;
  validPeriod: string;
}): SocialInsuranceItem => ({
  key,
  name,
  category,
  base: money(base),
  minBase,
  maxBase,
  personalRate: money(personalRate),
  companyRate: money(companyRate),
  personalAmount: contribution(base, personalRate),
  companyAmount: contribution(base, companyRate),
  policyBasis,
  validPeriod,
  status: "normal",
});

const buildSocialInsuranceItems = (values: CompensationFormValues) => {
  const warnings: string[] = [];
  const pensionBase = clampBase("养老保险基数", values.pensionBase || values.salary, SHENZHEN_POLICY.pension.min, SHENZHEN_POLICY.pension.max, warnings);
  const medicalBase = clampBase("医疗保险基数", values.medicalBase || values.salary, SHENZHEN_POLICY.medicalTier1.min, SHENZHEN_POLICY.medicalTier1.max, warnings);
  const maternityBase = clampBase("生育保险基数", values.maternityBase || medicalBase, SHENZHEN_POLICY.maternity.min, SHENZHEN_POLICY.maternity.max, warnings);
  const unemploymentBase = clampBase("失业保险基数", values.unemploymentBase || values.salary, SHENZHEN_POLICY.unemployment.min, SHENZHEN_POLICY.unemployment.max, warnings);
  const rawWorkInjuryBase = money(values.workInjuryBase || values.salary);
  const workInjuryBase = Math.max(rawWorkInjuryBase, SHENZHEN_POLICY.workInjury.min);
  if (rawWorkInjuryBase < SHENZHEN_POLICY.workInjury.min) {
    warnings.push(`工伤保险基数低于深圳当前下限，已按 ${currencyText(SHENZHEN_POLICY.workInjury.min)} 测算`);
  }
  const medicalPolicy = values.medicalTier === "tier2" ? SHENZHEN_POLICY.medicalTier2 : SHENZHEN_POLICY.medicalTier1;
  const items: SocialInsuranceItem[] = [
    makeSocialItem({
      key: "pension",
      name: "养老保险",
      category: "养老",
      base: pensionBase,
      minBase: SHENZHEN_POLICY.pension.min,
      maxBase: SHENZHEN_POLICY.pension.max,
      personalRate: SHENZHEN_POLICY.pension.personalRate,
      companyRate: SHENZHEN_POLICY.pension.companyRate,
      policyBasis: "广东企业职工养老基数 2025-07 起；单位 16%，个人 8%",
      validPeriod: SHENZHEN_POLICY.pension.validPeriod,
    }),
    makeSocialItem({
      key: "medical",
      name: `医疗保险${values.medicalTier === "tier2" ? "二档" : "一档"}`,
      category: "医疗",
      base: medicalBase,
      minBase: medicalPolicy.min,
      maxBase: medicalPolicy.max,
      personalRate: medicalPolicy.personalRate,
      companyRate: medicalPolicy.companyRate,
      policyBasis: "深圳职工医保 2026 基数；一档单位 6%/个人 2%，二档单位 1.5%/个人 0.5%",
      validPeriod: medicalPolicy.validPeriod,
    }),
    makeSocialItem({
      key: "maternity",
      name: "生育保险",
      category: "生育",
      base: maternityBase,
      minBase: SHENZHEN_POLICY.maternity.min,
      maxBase: SHENZHEN_POLICY.maternity.max,
      personalRate: SHENZHEN_POLICY.maternity.personalRate,
      companyRate: SHENZHEN_POLICY.maternity.companyRate,
      policyBasis: "深圳生育保险按职工医保基数，单位 0.5%，个人不缴",
      validPeriod: SHENZHEN_POLICY.maternity.validPeriod,
    }),
    makeSocialItem({
      key: "unemployment",
      name: "失业保险",
      category: "失业",
      base: unemploymentBase,
      minBase: SHENZHEN_POLICY.unemployment.min,
      maxBase: SHENZHEN_POLICY.unemployment.max,
      personalRate: SHENZHEN_POLICY.unemployment.personalRate,
      companyRate: SHENZHEN_POLICY.unemployment.companyRate,
      policyBasis: "深圳失业保险 2025-07 至 2026-06 基数；单位 0.8%，个人 0.2%",
      validPeriod: SHENZHEN_POLICY.unemployment.validPeriod,
    }),
    makeSocialItem({
      key: "workInjury",
      name: "工伤保险",
      category: "工伤",
      base: workInjuryBase,
      minBase: SHENZHEN_POLICY.workInjury.min,
      maxBase: SHENZHEN_POLICY.workInjury.max,
      personalRate: SHENZHEN_POLICY.workInjury.personalRate,
      companyRate: money(values.workInjuryCompanyRate) || SHENZHEN_POLICY.workInjury.companyRate,
      policyBasis: "广东省级统筹八档行业基准费率，深圳 2024-07 起 0.2%-1.4%，个人不缴",
      validPeriod: SHENZHEN_POLICY.workInjury.validPeriod,
    }),
  ];

  if (values.hukouType === "local") {
    items.splice(1, 0, makeSocialItem({
      key: "localSupplementPension",
      name: "地方补充养老",
      category: "养老",
      base: pensionBase,
      minBase: SHENZHEN_POLICY.pension.min,
      maxBase: SHENZHEN_POLICY.pension.max,
      personalRate: 0,
      companyRate: 1,
      policyBasis: "深圳本市户籍地方补充养老，单位承担",
      validPeriod: "长期政策，按最新通知调整",
    }));
  }

  return { items, warnings };
};

const sumSocialPersonal = (items: SocialInsuranceItem[]) => roundMoney(items.reduce((total, item) => total + money(item.personalAmount), 0));

const sumSocialCompany = (items: SocialInsuranceItem[]) => roundMoney(items.reduce((total, item) => total + money(item.companyAmount), 0));

const payrollFromForm = (values: CompensationFormValues): PayrollSnapshot => {
  const { items: socialItems, warnings: socialWarnings } = buildSocialInsuranceItems(values);
  const socialPersonalAmount = sumSocialPersonal(socialItems);
  const socialCompanyAmount = sumSocialCompany(socialItems);
  const housingPersonalAmount = contribution(values.housingFundBase, values.housingFundPersonalRate);
  const housingCompanyAmount = contribution(values.housingFundBase, values.housingFundCompanyRate);
  const personalContribution = socialPersonalAmount + housingPersonalAmount;
  const companyContribution = socialCompanyAmount + housingCompanyAmount;
  const netPayEstimate = Math.max(
    0,
    roundMoney(values.salary - personalContribution - values.taxEstimate - values.personalDeduction)
  );
  return {
    salary: values.salary,
    socialPersonalAmount,
    socialCompanyAmount,
    housingPersonalAmount,
    housingCompanyAmount,
    personalContribution,
    companyContribution,
    socialItems,
    socialWarnings,
    taxEstimate: values.taxEstimate,
    personalDeduction: values.personalDeduction,
    netPayEstimate,
    monthlyCost: roundMoney(values.salary + companyContribution),
  };
};

const payrollOf = (employee: Employee): PayrollSnapshot => {
  const salary = money(employee.salary);
  const fallbackSocial = buildSocialInsuranceItems(formFromEmployee(employee));
  const socialItems = employee.socialInsuranceItems?.length ? employee.socialInsuranceItems : fallbackSocial.items;
  const socialWarnings = employee.socialInsuranceWarnings?.length ? employee.socialInsuranceWarnings : fallbackSocial.warnings;
  const socialPersonalAmount = sumSocialPersonal(socialItems) || money(employee.socialInsurancePersonalAmount);
  const socialCompanyAmount = sumSocialCompany(socialItems) || money(employee.socialInsuranceCompanyAmount) || money(employee.socialInsurance);
  const housingPersonalAmount = money(employee.housingFundPersonalAmount);
  const housingCompanyAmount = money(employee.housingFundCompanyAmount) || money(employee.housingFund);
  const personalContribution = socialPersonalAmount + housingPersonalAmount;
  const companyContribution = socialCompanyAmount + housingCompanyAmount;
  const taxEstimate = money(employee.taxEstimate);
  const personalDeduction = money(employee.personalDeduction);
  const netPayEstimate = money(employee.netPayEstimate)
    || Math.max(0, roundMoney(salary - personalContribution - taxEstimate - personalDeduction));
  const monthlyCost = money(employee.monthlyCost) || roundMoney(salary + companyContribution);

  return {
    salary,
    socialPersonalAmount,
    socialCompanyAmount,
    housingPersonalAmount,
    housingCompanyAmount,
    personalContribution,
    companyContribution,
    socialItems,
    socialWarnings,
    taxEstimate,
    personalDeduction,
    netPayEstimate,
    monthlyCost,
  };
};

const formFromEmployee = (employee: Employee): CompensationFormValues => {
  const salary = money(employee.salary);
  const medicalBase = money(employee.medicalBase) || Math.min(Math.max(salary, SHENZHEN_POLICY.medicalTier1.min), SHENZHEN_POLICY.medicalTier1.max);
  return {
    salary,
    socialInsuranceRegion: employee.socialInsuranceRegion || SHENZHEN_POLICY.region,
    hukouType: employee.hukouType === "local" ? "local" : "non_local",
    medicalTier: employee.medicalTier === "tier2" ? "tier2" : "tier1",
    pensionBase: money(employee.pensionBase) || money(employee.socialInsuranceBase) || Math.min(Math.max(salary, SHENZHEN_POLICY.pension.min), SHENZHEN_POLICY.pension.max),
    medicalBase,
    maternityBase: money(employee.maternityBase) || medicalBase,
    unemploymentBase: money(employee.unemploymentBase) || Math.min(Math.max(salary, SHENZHEN_POLICY.unemployment.min), SHENZHEN_POLICY.unemployment.max),
    workInjuryBase: money(employee.workInjuryBase) || Math.max(salary, SHENZHEN_POLICY.workInjury.min),
    workInjuryCompanyRate: money(employee.workInjuryCompanyRate) || SHENZHEN_POLICY.workInjury.companyRate,
    housingFundBase: money(employee.housingFundBase) || salary,
    housingFundPersonalRate: money(employee.housingFundPersonalRate) || 8,
    housingFundCompanyRate: money(employee.housingFundCompanyRate) || 8,
    taxEstimate: money(employee.taxEstimate),
    personalDeduction: money(employee.personalDeduction),
  };
};

const zeroForm: CompensationFormValues = {
  salary: 0,
  socialInsuranceRegion: SHENZHEN_POLICY.region,
  hukouType: "non_local",
  medicalTier: "tier1",
  pensionBase: 0,
  medicalBase: 0,
  maternityBase: 0,
  unemploymentBase: 0,
  workInjuryBase: 0,
  workInjuryCompanyRate: SHENZHEN_POLICY.workInjury.companyRate,
  housingFundBase: 0,
  housingFundPersonalRate: 8,
  housingFundCompanyRate: 8,
  taxEstimate: 0,
  personalDeduction: 0,
};

function MetricCard({
  title,
  amount,
  caption,
  tone = "expense",
}: {
  title: string;
  amount: number;
  caption: string;
  tone?: "expense" | "neutral";
}) {
  return (
    <Card style={{ borderRadius: 12, height: "100%" }}>
      <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>{title}</div>
      <AmountDisplay amount={amount} type={tone === "expense" ? 2 : undefined} size="large" />
      <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>{caption}</div>
    </Card>
  );
}

function NumberField({
  label,
  value,
  onChange,
  precision = 2,
}: {
  label: string;
  value: number;
  onChange: (value: number) => void;
  precision?: number;
}) {
  return (
    <label className="block">
      <div className="mb-1 text-xs" style={{ color: "var(--text-color-3)" }}>{label}</div>
      <InputNumber
        value={value}
        min={0}
        precision={precision}
        onChange={(nextValue) => onChange(money(nextValue))}
        style={{ width: "100%" }}
      />
    </label>
  );
}

function SelectField({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <label className="block">
      <div className="mb-1 text-xs" style={{ color: "var(--text-color-3)" }}>{label}</div>
      <Select value={value} onChange={(nextValue) => onChange(String(nextValue))} style={{ width: "100%" }}>
        {options.map((option) => (
          <Select.Option key={option.value} value={option.value}>{option.label}</Select.Option>
        ))}
      </Select>
    </label>
  );
}

export default function CompensationPage() {
  const activeCompanyId = useAppStore((state) => state.activeCompanyId);
  const [summary, setSummary] = useState<EnterpriseSummary | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("active");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [editingEmployee, setEditingEmployee] = useState<Employee | null>(null);
  const [compensationForm, setCompensationForm] = useState<CompensationFormValues>(zeroForm);
  const tableScrollRef = useRef<HTMLDivElement | null>(null);
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
      (acc, employee) => {
        const payroll = payrollOf(employee);
        return {
          salary: acc.salary + payroll.salary,
          companyContribution: acc.companyContribution + payroll.companyContribution,
          personalContribution: acc.personalContribution + payroll.personalContribution,
          taxEstimate: acc.taxEstimate + payroll.taxEstimate,
          personalDeduction: acc.personalDeduction + payroll.personalDeduction,
          netPayEstimate: acc.netPayEstimate + payroll.netPayEstimate,
          monthlyCost: acc.monthlyCost + payroll.monthlyCost,
        };
      },
      {
        salary: 0,
        companyContribution: 0,
        personalContribution: 0,
        taxEstimate: 0,
        personalDeduction: 0,
        netPayEstimate: 0,
        monthlyCost: 0,
      }
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
      current.monthlyCost += payrollOf(employee).monthlyCost;
      groups.set(key, current);
    });

    return Array.from(groups.values()).sort((left, right) => right.monthlyCost - left.monthlyCost);
  }, [departments, employees]);

  const maxDepartmentCost = Math.max(...departmentCostRows.map((row) => row.monthlyCost), 1);
  const projectedPayroll = useMemo(() => payrollFromForm(compensationForm), [compensationForm]);
  const totalEmployeeDeductions = compensationSummary.personalContribution
    + compensationSummary.taxEstimate
    + compensationSummary.personalDeduction;

  const handleSearch = () => {
    employeesPagination.resetPage();
    tableScrollRef.current?.scrollTo({ left: 0 });
    void fetchData(keyword, status);
  };

  const updateForm = <K extends keyof CompensationFormValues>(field: K, value: CompensationFormValues[K]) => {
    setCompensationForm((current) => ({ ...current, [field]: value }));
  };

  const openCompensationEditor = (employee: Employee) => {
    setEditingEmployee(employee);
    setCompensationForm(formFromEmployee(employee));
  };

  const closeCompensationEditor = () => {
    if (saving) return;
    setEditingEmployee(null);
    setCompensationForm(zeroForm);
  };

  const handleSaveCompensation = async () => {
    if (!editingEmployee) return;
    try {
      setSaving(true);
      await enterpriseApi.updateEmployee(editingEmployee.id, {
        salary: compensationForm.salary,
        taxEstimate: compensationForm.taxEstimate,
        socialInsuranceRegion: compensationForm.socialInsuranceRegion,
        hukouType: compensationForm.hukouType,
        medicalTier: compensationForm.medicalTier,
        pensionBase: compensationForm.pensionBase,
        medicalBase: compensationForm.medicalBase,
        maternityBase: compensationForm.maternityBase,
        unemploymentBase: compensationForm.unemploymentBase,
        workInjuryBase: compensationForm.workInjuryBase,
        workInjuryCompanyRate: compensationForm.workInjuryCompanyRate,
        housingFundBase: compensationForm.housingFundBase,
        housingFundPersonalRate: compensationForm.housingFundPersonalRate,
        housingFundCompanyRate: compensationForm.housingFundCompanyRate,
        personalDeduction: compensationForm.personalDeduction,
      });
      Message.success("薪酬档案已更新");
      setEditingEmployee(null);
      await fetchData(keyword, status);
    } catch {
      Message.error("薪酬档案保存失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="max-w-7xl mx-auto animate-fade-in">
      <PageHeader
        title="人员薪酬"
        subtitle={summary?.company
          ? `${summary.company.name} · 每人独立维护工资、深圳五险、公积金、个税和实发测算`
          : "每人独立维护工资、深圳五险、公积金、个税和实发测算"}
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

      <Row gutter={[16, 16]} className="mb-6">
        <Col xs={12} md={6}>
          <MetricCard
            title="公司月成本"
            amount={compensationSummary.monthlyCost || summary?.monthlyPeopleCost || 0}
            caption={`当前筛选 ${employees.length} 人`}
          />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard
            title="应发工资"
            amount={compensationSummary.salary}
            caption="合同工资与固定薪酬"
          />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard
            title="公司缴费"
            amount={compensationSummary.companyContribution}
            caption="公司承担五险 + 公积金"
          />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard
            title="员工扣缴"
            amount={totalEmployeeDeductions}
            caption="个人缴费 + 个税 + 其他扣减"
          />
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={7}>
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
          <Card className="mt-4" style={{ borderRadius: 12 }} title="深圳五险政策">
            <div className="space-y-3">
              {policyRows.map((row) => (
                <div
                  key={row.name}
                  className="rounded-lg border p-3"
                  style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="text-sm font-medium">{row.name}</div>
                    <Tag color="arcoblue">{row.period}</Tag>
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>基数 {row.range}</div>
                  <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>{row.rate}</div>
                </div>
              ))}
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={17}>
          <Card style={{ borderRadius: 12 }}>
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="font-medium">员工薪酬档案</div>
                <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                  按员工独立维护五险基数、医保档次、户籍类型、公积金比例和实发测算
                </div>
              </div>
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
                    tableScrollRef.current?.scrollTo({ left: 0 });
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

            <div ref={tableScrollRef} className="overflow-x-auto">
              <table className="w-full min-w-[1300px] table-fixed border-collapse text-sm">
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
                    const payroll = payrollOf(employee);
                    return (
                      <tr key={employee.id} className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]" style={{ borderColor: "var(--border-color-light)" }}>
                        <td className="px-4 py-4 align-middle">
                          <div className="font-medium" style={{ color: "var(--text-color-1)" }}>{employee.name}</div>
                          <div className="mt-1 break-all text-xs" style={{ color: "var(--text-color-3)" }}>{employee.email}</div>
                          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                            {employee.departmentName || "未分配部门"} · {employee.position}
                          </div>
                        </td>
                        <td className="px-4 py-4 align-middle text-right">
                          <div className="text-xs" style={{ color: "var(--text-color-3)" }}>应发</div>
                          <AmountDisplay amount={payroll.salary} type={2} size="small" />
                          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>实发预估</div>
                          <AmountDisplay amount={payroll.netPayEstimate} size="small" />
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <div className="mb-2 flex flex-wrap items-center gap-1">
                            <Tag color="arcoblue">{employee.socialInsuranceRegion || SHENZHEN_POLICY.region}</Tag>
                            <Tag color={employee.hukouType === "local" ? "green" : "gray"}>
                              {employee.hukouType === "local" ? "深户" : "非深户"}
                            </Tag>
                            <Tag color="purple">{employee.medicalTier === "tier2" ? "医保二档" : "医保一档"}</Tag>
                            {payroll.socialWarnings.length > 0 && <Tag color="orange">已校正基数</Tag>}
                          </div>
                          <div className="space-y-1">
                            {payroll.socialItems.map((item) => (
                              <div
                                key={item.key}
                                className="grid grid-cols-[72px_112px_1fr] items-center gap-2 text-xs"
                                style={{ color: "var(--text-color-3)" }}
                              >
                                <span className="font-medium" style={{ color: "var(--text-color-2)" }}>{item.name}</span>
                                <span>基数 {currencyShort(money(item.base))}</span>
                                <span className="text-right">
                                  个 {currencyShort(money(item.personalAmount))} / 司 {currencyShort(money(item.companyAmount))}
                                </span>
                              </div>
                            ))}
                          </div>
                          <div className="mt-2 flex items-center justify-between text-xs" style={{ color: "var(--text-color-3)" }}>
                            <span>个人合计 {currencyShort(payroll.socialPersonalAmount)}</span>
                            <span>公司合计 {currencyShort(payroll.socialCompanyAmount)}</span>
                          </div>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <div className="font-medium"><AmountDisplay amount={money(employee.housingFundBase) || payroll.salary} size="small" /></div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                            个人 {formatPercent(money(employee.housingFundPersonalRate))} · <AmountDisplay amount={payroll.housingPersonalAmount} size="small" />
                          </div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                            公司 {formatPercent(money(employee.housingFundCompanyRate))} · <AmountDisplay amount={payroll.housingCompanyAmount} type={2} size="small" />
                          </div>
                        </td>
                        <td className="px-4 py-4 align-middle text-right">
                          <div className="text-xs" style={{ color: "var(--text-color-3)" }}>个人缴费</div>
                          <AmountDisplay amount={payroll.personalContribution} size="small" />
                          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>个税 / 其他</div>
                          <div className="text-sm">
                            <AmountDisplay amount={payroll.taxEstimate + payroll.personalDeduction} size="small" />
                          </div>
                        </td>
                        <td className="px-4 py-4 align-middle text-right">
                          <AmountDisplay amount={payroll.monthlyCost} type={2} />
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                            公司缴费 <AmountDisplay amount={payroll.companyContribution} type={2} size="small" />
                          </div>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <div className="flex justify-center">
                            <Tag color={statusConfig.color}>{statusConfig.label}</Tag>
                          </div>
                        </td>
                        <td className="px-4 py-4 align-middle text-center">
                          <Button
                            type="text"
                            size="mini"
                            icon={<IconEdit />}
                            title="编辑薪酬档案"
                            onClick={() => openCompensationEditor(employee)}
                          />
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

      <Modal
        className="compensation-payroll-modal"
        title={editingEmployee ? `薪酬档案 · ${editingEmployee.name}` : "薪酬档案"}
        visible={Boolean(editingEmployee)}
        okText="保存薪酬档案"
        cancelText="取消"
        confirmLoading={saving}
        onOk={handleSaveCompensation}
        onCancel={closeCompensationEditor}
        style={{ width: "900px", maxWidth: "calc(100vw - 40px)" }}
      >
        <div className="space-y-5">
          <div
            className="rounded-lg border p-4"
            style={{ backgroundColor: "var(--color-fill-1)", borderColor: "var(--border-color-light)" }}
          >
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>公司月成本</div>
                <AmountDisplay amount={projectedPayroll.monthlyCost} type={2} />
              </div>
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>实发预估</div>
                <AmountDisplay amount={projectedPayroll.netPayEstimate} />
              </div>
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>公司缴费</div>
                <AmountDisplay amount={projectedPayroll.companyContribution} type={2} />
              </div>
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>个人扣缴</div>
                <AmountDisplay amount={projectedPayroll.personalContribution + projectedPayroll.taxEstimate + projectedPayroll.personalDeduction} />
              </div>
            </div>
            {projectedPayroll.socialWarnings.length > 0 && (
              <div
                className="mt-4 rounded-md border px-3 py-2 text-xs"
                style={{ borderColor: "rgba(245, 130, 32, 0.35)", color: "var(--color-warning)" }}
              >
                {projectedPayroll.socialWarnings.join("；")}
              </div>
            )}
          </div>

          <div>
            <div className="mb-3 font-medium">工资与扣缴</div>
            <div className="grid gap-3 md:grid-cols-3">
              <NumberField label="基本工资" value={compensationForm.salary} onChange={(value) => updateForm("salary", value)} />
              <NumberField label="个税估算" value={compensationForm.taxEstimate} onChange={(value) => updateForm("taxEstimate", value)} />
              <NumberField label="其他个人扣减" value={compensationForm.personalDeduction} onChange={(value) => updateForm("personalDeduction", value)} />
            </div>
          </div>

          <div>
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div className="font-medium">深圳五险口径</div>
              <Tag color="arcoblue">按当前公开上下限自动校正</Tag>
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <label className="block">
                <div className="mb-1 text-xs" style={{ color: "var(--text-color-3)" }}>参保地区</div>
                <Input
                  value={compensationForm.socialInsuranceRegion}
                  onChange={(value) => updateForm("socialInsuranceRegion", value)}
                />
              </label>
              <SelectField
                label="户籍类型"
                value={compensationForm.hukouType}
                options={[
                  { value: "non_local", label: "非深户" },
                  { value: "local", label: "深户" },
                ]}
                onChange={(value) => updateForm("hukouType", value as CompensationFormValues["hukouType"])}
              />
              <SelectField
                label="医保档次"
                value={compensationForm.medicalTier}
                options={[
                  { value: "tier1", label: "医保一档" },
                  { value: "tier2", label: "医保二档" },
                ]}
                onChange={(value) => updateForm("medicalTier", value as CompensationFormValues["medicalTier"])}
              />
            </div>
            <div className="mt-3 grid gap-3 md:grid-cols-3">
              <NumberField label="养老基数 4775-27549" value={compensationForm.pensionBase} onChange={(value) => updateForm("pensionBase", value)} />
              <NumberField label="医疗基数 6727-33633" value={compensationForm.medicalBase} onChange={(value) => updateForm("medicalBase", value)} />
              <NumberField label="生育基数 6727-33633" value={compensationForm.maternityBase} onChange={(value) => updateForm("maternityBase", value)} />
              <NumberField label="失业基数 2520-44265" value={compensationForm.unemploymentBase} onChange={(value) => updateForm("unemploymentBase", value)} />
              <NumberField label="工伤基数 不低于2520" value={compensationForm.workInjuryBase} onChange={(value) => updateForm("workInjuryBase", value)} />
              <NumberField label="工伤公司费率 %" value={compensationForm.workInjuryCompanyRate} precision={2} onChange={(value) => updateForm("workInjuryCompanyRate", value)} />
            </div>
          </div>

          <div>
            <div className="mb-3 font-medium">五险测算明细</div>
            <div className="grid gap-3 md:grid-cols-2">
              {projectedPayroll.socialItems.map((item) => (
                <div
                  key={item.key}
                  className="rounded-lg border p-3"
                  style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="font-medium">{item.name}</div>
                    <Tag color="arcoblue">{item.validPeriod}</Tag>
                  </div>
                  <div className="mt-3 grid grid-cols-3 gap-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                    <div>
                      <div>缴费基数</div>
                      <div className="mt-1 font-medium" style={{ color: "var(--text-color-1)" }}>{currencyText(money(item.base))}</div>
                    </div>
                    <div>
                      <div>个人</div>
                      <div className="mt-1 font-medium" style={{ color: "var(--text-color-1)" }}>
                        {formatPercent(money(item.personalRate))} · {currencyText(money(item.personalAmount))}
                      </div>
                    </div>
                    <div>
                      <div>公司</div>
                      <div className="mt-1 font-medium" style={{ color: "var(--color-danger)" }}>
                        {formatPercent(money(item.companyRate))} · {currencyText(money(item.companyAmount))}
                      </div>
                    </div>
                  </div>
                  <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>{item.policyBasis}</div>
                </div>
              ))}
            </div>
          </div>

          <div>
            <div className="mb-3 font-medium">公积金口径</div>
            <div className="grid gap-3 md:grid-cols-3">
              <NumberField label="公积金基数" value={compensationForm.housingFundBase} onChange={(value) => updateForm("housingFundBase", value)} />
              <NumberField label="个人比例" value={compensationForm.housingFundPersonalRate} onChange={(value) => updateForm("housingFundPersonalRate", value)} />
              <NumberField label="公司比例" value={compensationForm.housingFundCompanyRate} onChange={(value) => updateForm("housingFundCompanyRate", value)} />
            </div>
          </div>
        </div>
      </Modal>
    </div>
  );
}
