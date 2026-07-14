"use client";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button, Card, Grid, Input, InputNumber, Message, Modal, Select, Tag } from "@arco-design/web-react";
import {
  IconCheckCircle,
  IconDownload,
  IconEdit,
  IconIdcard,
  IconPlus,
  IconSearch,
} from "@arco-design/web-react/icon";
import PageHeader from "@/components/common/PageHeader";
import AmountDisplay from "@/components/common/AmountDisplay";
import AppPagination from "@/components/common/AppPagination";
import { enterpriseApi } from "@/lib/api/enterprise";
import { useClientPagination } from "@/lib/hooks/useClientPagination";
import { useAppStore } from "@/lib/stores/appStore";
import type { Department, Employee, EnterpriseSummary, PayrollRun, SocialInsuranceItem } from "@/lib/types";

const { Row, Col } = Grid;

const statusLabels: Record<string, { label: string; color: string }> = {
  onboarding: { label: "待入职", color: "orange" },
  probation: { label: "试用期", color: "arcoblue" },
  active: { label: "在职", color: "green" },
  departed: { label: "已离职", color: "gray" },
};

const tableColumns = [
  { label: "员工档案", description: "部门、岗位、状态", width: "230px", accent: "var(--color-info)" },
  { label: "个人到账", description: "银行卡实发金额", width: "260px", accent: "var(--color-success)" },
  { label: "公司成本", description: "工资加公司缴费", width: "260px", accent: "var(--color-danger)" },
  { label: "考勤与个税", description: "计薪天数、预扣税", width: "240px", accent: "var(--color-warning)" },
  { label: "五险明细", description: "基数、个人、公司", width: "380px", accent: "var(--color-primary)" },
  { label: "公积金", description: "基数与双边比例", width: "190px", accent: "var(--text-color-3)" },
] as const;

type EmployeeSortMode = "default" | "salary_desc" | "salary_asc" | "hire_desc" | "hire_asc";

const SHENZHEN_POLICY = {
  region: "深圳",
  pension: { min: 4775, max: 27549, personalRate: 8, companyRate: 16, validPeriod: "2025-07 至 2026-06" },
  medicalTier1: { min: 6727, max: 33633, personalRate: 2, companyRate: 6, validPeriod: "2026 年" },
  medicalTier2: { min: 6727, max: 33633, personalRate: 0.5, companyRate: 1.5, validPeriod: "2026 年" },
  maternity: { min: 6727, max: 33633, personalRate: 0, companyRate: 0.5, validPeriod: "2026 年" },
  unemployment: { min: 2520, max: 44265, personalRate: 0.2, companyRate: 0.8, validPeriod: "2025-07 至 2026-06" },
  workInjury: { min: 2520, max: null as number | null, personalRate: 0, companyRate: 0.2, minCompanyRate: 0.2, maxCompanyRate: 1.4, validPeriod: "2024-07 起" },
  housingFund: { min: 2520, max: 44265, minRate: 5, personalRate: 12, companyRate: 12, maxRate: 12, validPeriod: "2025-07 至 2026-06" },
};

const policyRows = [
  { name: "养老", range: "4775 - 27549", rate: "个人 8% / 公司 16%", period: SHENZHEN_POLICY.pension.validPeriod },
  { name: "医疗/生育", range: "6727 - 33633", rate: "一档 2%/6%，二档 0.5%/1.5%，生育公司 0.5%", period: SHENZHEN_POLICY.medicalTier1.validPeriod },
  { name: "失业", range: "2520 - 44265", rate: "个人 0.2% / 公司 0.8%", period: SHENZHEN_POLICY.unemployment.validPeriod },
  { name: "工伤", range: "不低于 2520；普通单位按工资总额，无单人工资上限", rate: "行业基准 0.2% - 1.4%，个人不缴", period: SHENZHEN_POLICY.workInjury.validPeriod },
  { name: "公积金", range: "2520 - 44265", rate: "个人 5%-12% / 公司 5%-12%", period: SHENZHEN_POLICY.housingFund.validPeriod },
];

const PAYROLL_STANDARD_DAYS = 21.75;
const MONTHLY_WORK_DAYS = 20.67;
const STANDARD_DAILY_HOURS = 8;
const OVERTIME_POLICY = {
  minBase: 2520,
  weekdayRate: 1.5,
  restDayRate: 2,
  holidayRate: 3,
  validPeriod: "2025-01 起",
};
const STANDARD_MONTHLY_DEDUCTION = 5000;
const CURRENT_PAYROLL_MONTH = new Date().getMonth() + 1;
const currentPayrollPeriod = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
};
const CURRENT_PAYROLL_PERIOD = currentPayrollPeriod();

const cumulativeTaxBrackets = [
  { limit: 36000, rate: 3, quickDeduction: 0 },
  { limit: 144000, rate: 10, quickDeduction: 2520 },
  { limit: 300000, rate: 20, quickDeduction: 16920 },
  { limit: 420000, rate: 25, quickDeduction: 31920 },
  { limit: 660000, rate: 30, quickDeduction: 52920 },
  { limit: 960000, rate: 35, quickDeduction: 85920 },
  { limit: Number.POSITIVE_INFINITY, rate: 45, quickDeduction: 181920 },
] as const;

type CompensationFormValues = {
  salary: number;
  overtimeBase: number;
  weekdayOvertimeHours: number;
  restDayOvertimeHours: number;
  holidayOvertimeHours: number;
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
  attendance: AttendanceSnapshot;
  overtime: OvertimeSnapshot;
  taxWithholding: TaxWithholdingSnapshot;
  socialPersonalAmount: number;
  socialCompanyAmount: number;
  housingFundBase: number;
  housingFundPersonalRate: number;
  housingFundCompanyRate: number;
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

type AttendanceSnapshot = {
  standardDays: number;
  monthlyWorkDays: number;
  paidDays: number;
  absenceDays: number;
  missingPunches: number;
  attendanceDeduction: number;
  payableSalary: number;
  status: string;
};

type OvertimeSnapshot = {
  base: number;
  hourlyRate: number;
  weekdayHours: number;
  restDayHours: number;
  holidayHours: number;
  totalHours: number;
  weekdayPay: number;
  restDayPay: number;
  holidayPay: number;
  totalPay: number;
  warnings: string[];
};

type TaxWithholdingSnapshot = {
  currentMonth: number;
  monthlyTaxableIncome: number;
  cumulativeTaxableIncome: number;
  previousTaxableIncome: number;
  cumulativeTaxBefore: number;
  previousTaxBefore: number;
  currentTax: number;
  rate: number;
  quickDeduction: number;
};

const money = (value: unknown) => Number(value || 0);

const roundMoney = (value: number) => Math.round(value * 100) / 100;

const contribution = (base: number, rate: number) => roundMoney((money(base) * money(rate)) / 100);

const attendanceSnapshotOf = (salary: number, overtimePay = 0): AttendanceSnapshot => ({
  standardDays: PAYROLL_STANDARD_DAYS,
  monthlyWorkDays: MONTHLY_WORK_DAYS,
  paidDays: PAYROLL_STANDARD_DAYS,
  absenceDays: 0,
  missingPunches: 0,
  attendanceDeduction: 0,
  payableSalary: roundMoney(money(salary) + money(overtimePay)),
  status: money(overtimePay) > 0 ? "全勤含加班" : "全勤测算",
});

const taxForTaxableIncome = (taxableIncome: number) => {
  const safeTaxableIncome = Math.max(0, money(taxableIncome));
  const bracket = cumulativeTaxBrackets.find((item) => safeTaxableIncome <= item.limit) || cumulativeTaxBrackets[cumulativeTaxBrackets.length - 1];
  return {
    tax: roundMoney(Math.max(0, safeTaxableIncome * (bracket.rate / 100) - bracket.quickDeduction)),
    rate: bracket.rate,
    quickDeduction: bracket.quickDeduction,
  };
};

const cumulativeTaxWithholding = ({
  payableSalary,
  socialPersonalAmount,
  housingPersonalAmount,
  personalDeduction,
  month = CURRENT_PAYROLL_MONTH,
}: {
  payableSalary: number;
  socialPersonalAmount: number;
  housingPersonalAmount: number;
  personalDeduction: number;
  month?: number;
}): TaxWithholdingSnapshot => {
  const currentMonth = Math.min(12, Math.max(1, Math.floor(money(month) || 1)));
  const monthlyTaxableIncome = roundMoney(Math.max(
    0,
    payableSalary - socialPersonalAmount - housingPersonalAmount - personalDeduction - STANDARD_MONTHLY_DEDUCTION
  ));
  const cumulativeTaxableIncome = roundMoney(monthlyTaxableIncome * currentMonth);
  const previousTaxableIncome = roundMoney(monthlyTaxableIncome * Math.max(0, currentMonth - 1));
  const cumulativeTax = taxForTaxableIncome(cumulativeTaxableIncome);
  const previousTax = taxForTaxableIncome(previousTaxableIncome);

  return {
    currentMonth,
    monthlyTaxableIncome,
    cumulativeTaxableIncome,
    previousTaxableIncome,
    cumulativeTaxBefore: cumulativeTax.tax,
    previousTaxBefore: previousTax.tax,
    currentTax: roundMoney(Math.max(0, cumulativeTax.tax - previousTax.tax)),
    rate: cumulativeTax.rate,
    quickDeduction: cumulativeTax.quickDeduction,
  };
};

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

const clampRate = (label: string, value: number, min: number, max: number, warnings: string[]) => {
  const safeValue = money(value);
  if (safeValue < min) {
    warnings.push(`${label}低于当前下限，已按 ${formatPercent(min)} 测算`);
    return min;
  }
  if (safeValue > max) {
    warnings.push(`${label}高于当前上限，已按 ${formatPercent(max)} 测算`);
    return max;
  }
  return safeValue;
};

const buildOvertime = (values: CompensationFormValues): OvertimeSnapshot => {
  const warnings: string[] = [];
  const rawBase = money(values.overtimeBase || values.salary);
  const base = Math.max(rawBase, OVERTIME_POLICY.minBase);
  if (rawBase > 0 && rawBase < OVERTIME_POLICY.minBase) {
    warnings.push(`加班工资基数低于当前最低工资，已按 ${currencyText(OVERTIME_POLICY.minBase)} 测算`);
  }
  const hourlyRate = roundMoney(base / PAYROLL_STANDARD_DAYS / STANDARD_DAILY_HOURS);
  const weekdayHours = Math.max(0, money(values.weekdayOvertimeHours));
  const restDayHours = Math.max(0, money(values.restDayOvertimeHours));
  const holidayHours = Math.max(0, money(values.holidayOvertimeHours));
  const weekdayPay = roundMoney(hourlyRate * weekdayHours * OVERTIME_POLICY.weekdayRate);
  const restDayPay = roundMoney(hourlyRate * restDayHours * OVERTIME_POLICY.restDayRate);
  const holidayPay = roundMoney(hourlyRate * holidayHours * OVERTIME_POLICY.holidayRate);

  return {
    base,
    hourlyRate,
    weekdayHours,
    restDayHours,
    holidayHours,
    totalHours: roundMoney(weekdayHours + restDayHours + holidayHours),
    weekdayPay,
    restDayPay,
    holidayPay,
    totalPay: roundMoney(weekdayPay + restDayPay + holidayPay),
    warnings,
  };
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
  const workInjuryCompanyRate = clampRate(
    "工伤公司费率",
    values.workInjuryCompanyRate || SHENZHEN_POLICY.workInjury.companyRate,
    SHENZHEN_POLICY.workInjury.minCompanyRate,
    SHENZHEN_POLICY.workInjury.maxCompanyRate,
    warnings
  );
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
      companyRate: workInjuryCompanyRate,
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

const buildHousingFund = (values: CompensationFormValues) => {
  const warnings: string[] = [];
  const housingFundBase = clampBase("公积金缴存基数", values.housingFundBase || values.salary, SHENZHEN_POLICY.housingFund.min, SHENZHEN_POLICY.housingFund.max, warnings);
  const housingFundPersonalRate = clampRate(
    "公积金个人比例",
    values.housingFundPersonalRate || SHENZHEN_POLICY.housingFund.personalRate,
    SHENZHEN_POLICY.housingFund.minRate,
    SHENZHEN_POLICY.housingFund.maxRate,
    warnings
  );
  const housingFundCompanyRate = clampRate(
    "公积金公司比例",
    values.housingFundCompanyRate || SHENZHEN_POLICY.housingFund.companyRate,
    SHENZHEN_POLICY.housingFund.minRate,
    SHENZHEN_POLICY.housingFund.maxRate,
    warnings
  );

  return {
    base: housingFundBase,
    personalRate: housingFundPersonalRate,
    companyRate: housingFundCompanyRate,
    personalAmount: contribution(housingFundBase, housingFundPersonalRate),
    companyAmount: contribution(housingFundBase, housingFundCompanyRate),
    warnings,
  };
};

const payrollFromForm = (values: CompensationFormValues): PayrollSnapshot => {
  const { items: socialItems, warnings: socialWarnings } = buildSocialInsuranceItems(values);
  const housingFund = buildHousingFund(values);
  const overtime = buildOvertime(values);
  const attendance = attendanceSnapshotOf(values.salary, overtime.totalPay);
  const socialPersonalAmount = sumSocialPersonal(socialItems);
  const socialCompanyAmount = sumSocialCompany(socialItems);
  const housingPersonalAmount = housingFund.personalAmount;
  const housingCompanyAmount = housingFund.companyAmount;
  const personalContribution = socialPersonalAmount + housingPersonalAmount;
  const companyContribution = socialCompanyAmount + housingCompanyAmount;
  const taxWithholding = cumulativeTaxWithholding({
    payableSalary: attendance.payableSalary,
    socialPersonalAmount,
    housingPersonalAmount,
    personalDeduction: values.personalDeduction,
  });
  const taxEstimate = taxWithholding.currentTax;
  const netPayEstimate = Math.max(
    0,
    roundMoney(attendance.payableSalary - personalContribution - taxEstimate - values.personalDeduction)
  );
  return {
    salary: values.salary,
    attendance,
    overtime,
    taxWithholding,
    socialPersonalAmount,
    socialCompanyAmount,
    housingFundBase: housingFund.base,
    housingFundPersonalRate: housingFund.personalRate,
    housingFundCompanyRate: housingFund.companyRate,
    housingPersonalAmount,
    housingCompanyAmount,
    personalContribution,
    companyContribution,
    socialItems,
    socialWarnings: [...socialWarnings, ...housingFund.warnings, ...overtime.warnings],
    taxEstimate,
    personalDeduction: values.personalDeduction,
    netPayEstimate,
    monthlyCost: roundMoney(attendance.payableSalary + companyContribution),
  };
};

const payrollOf = (employee: Employee): PayrollSnapshot => {
  const salary = money(employee.salary);
  const employeeForm = formFromEmployee(employee);
  const fallbackSocial = buildSocialInsuranceItems(employeeForm);
  const housingFund = buildHousingFund(employeeForm);
  const overtime = buildOvertime(employeeForm);
  const socialItems = employee.socialInsuranceItems?.length ? employee.socialInsuranceItems : fallbackSocial.items;
  const socialWarnings = employee.socialInsuranceWarnings?.length ? employee.socialInsuranceWarnings : fallbackSocial.warnings;
  const socialPersonalAmount = sumSocialPersonal(socialItems) || money(employee.socialInsurancePersonalAmount);
  const socialCompanyAmount = sumSocialCompany(socialItems) || money(employee.socialInsuranceCompanyAmount) || money(employee.socialInsurance);
  const housingPersonalAmount = housingFund.personalAmount;
  const housingCompanyAmount = housingFund.companyAmount;
  const personalContribution = socialPersonalAmount + housingPersonalAmount;
  const companyContribution = socialCompanyAmount + housingCompanyAmount;
  const personalDeduction = money(employee.personalDeduction);
  const attendance = attendanceSnapshotOf(salary, overtime.totalPay);
  const taxWithholding = cumulativeTaxWithholding({
    payableSalary: attendance.payableSalary,
    socialPersonalAmount,
    housingPersonalAmount,
    personalDeduction,
  });
  const taxEstimate = taxWithholding.currentTax;
  const netPayEstimate = Math.max(0, roundMoney(attendance.payableSalary - personalContribution - taxEstimate - personalDeduction));
  const monthlyCost = roundMoney(attendance.payableSalary + companyContribution);

  return {
    salary,
    attendance,
    overtime,
    taxWithholding,
    socialPersonalAmount,
    socialCompanyAmount,
    housingFundBase: housingFund.base,
    housingFundPersonalRate: housingFund.personalRate,
    housingFundCompanyRate: housingFund.companyRate,
    housingPersonalAmount,
    housingCompanyAmount,
    personalContribution,
    companyContribution,
    socialItems,
    socialWarnings: [...socialWarnings, ...housingFund.warnings, ...overtime.warnings],
    taxEstimate,
    personalDeduction,
    netPayEstimate,
    monthlyCost,
  };
};

const formFromEmployee = (employee: Employee): CompensationFormValues => {
  const salary = money(employee.salary);
  const medicalBase = money(employee.medicalBase) || Math.min(Math.max(salary, SHENZHEN_POLICY.medicalTier1.min), SHENZHEN_POLICY.medicalTier1.max);
  const housingFundBase = money(employee.housingFundBase) || Math.min(Math.max(salary, SHENZHEN_POLICY.housingFund.min), SHENZHEN_POLICY.housingFund.max);
  return {
    salary,
    overtimeBase: money(employee.overtimeBase) || salary,
    weekdayOvertimeHours: money(employee.weekdayOvertimeHours),
    restDayOvertimeHours: money(employee.restDayOvertimeHours),
    holidayOvertimeHours: money(employee.holidayOvertimeHours),
    socialInsuranceRegion: employee.socialInsuranceRegion || SHENZHEN_POLICY.region,
    hukouType: employee.hukouType === "local" ? "local" : "non_local",
    medicalTier: employee.medicalTier === "tier2" ? "tier2" : "tier1",
    pensionBase: money(employee.pensionBase) || money(employee.socialInsuranceBase) || Math.min(Math.max(salary, SHENZHEN_POLICY.pension.min), SHENZHEN_POLICY.pension.max),
    medicalBase,
    maternityBase: money(employee.maternityBase) || medicalBase,
    unemploymentBase: money(employee.unemploymentBase) || Math.min(Math.max(salary, SHENZHEN_POLICY.unemployment.min), SHENZHEN_POLICY.unemployment.max),
    workInjuryBase: money(employee.workInjuryBase) || Math.max(salary, SHENZHEN_POLICY.workInjury.min),
    workInjuryCompanyRate: money(employee.workInjuryCompanyRate) || SHENZHEN_POLICY.workInjury.companyRate,
    housingFundBase,
    housingFundPersonalRate: money(employee.housingFundPersonalRate) || SHENZHEN_POLICY.housingFund.personalRate,
    housingFundCompanyRate: money(employee.housingFundCompanyRate) || SHENZHEN_POLICY.housingFund.companyRate,
    taxEstimate: money(employee.taxEstimate),
    personalDeduction: money(employee.personalDeduction),
  };
};

const zeroForm: CompensationFormValues = {
  salary: 0,
  overtimeBase: 0,
  weekdayOvertimeHours: 0,
  restDayOvertimeHours: 0,
  holidayOvertimeHours: 0,
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
  housingFundPersonalRate: SHENZHEN_POLICY.housingFund.personalRate,
  housingFundCompanyRate: SHENZHEN_POLICY.housingFund.companyRate,
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
  tone?: "expense" | "income" | "neutral";
}) {
  const amountType = tone === "expense" ? 2 : tone === "income" ? 1 : undefined;
  return (
    <Card className="metric-card" style={{ borderRadius: 12, height: "100%" }}>
      <div className="text-sm mb-2" style={{ color: "var(--text-color-3)" }}>{title}</div>
      <AmountDisplay amount={amount} type={amountType} size="large" />
      <div className="text-xs mt-2" style={{ color: "var(--text-color-3)" }}>{caption}</div>
    </Card>
  );
}

function BreakdownLine({
  label,
  value,
  sign,
  strong = false,
}: {
  label: string;
  value: number;
  sign?: "+" | "-";
  strong?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-3 text-xs leading-5">
      <span className="whitespace-nowrap" style={{ color: "var(--text-color-3)" }}>{label}</span>
      <span className={strong ? "font-semibold" : ""} style={{ color: strong ? "var(--text-color-1)" : "var(--text-color-2)" }}>
        {sign || ""}{currencyText(value)}
      </span>
    </div>
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
  const [payrollRuns, setPayrollRuns] = useState<PayrollRun[]>([]);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("active");
  const [sortMode, setSortMode] = useState<EmployeeSortMode>("default");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [payrollBusy, setPayrollBusy] = useState(false);
  const [editingEmployee, setEditingEmployee] = useState<Employee | null>(null);
  const [compensationForm, setCompensationForm] = useState<CompensationFormValues>(zeroForm);
  const tableScrollRef = useRef<HTMLDivElement | null>(null);
  const sortedEmployees = useMemo(() => {
    const nextEmployees = [...employees];
    switch (sortMode) {
      case "salary_desc":
        return nextEmployees.sort((left, right) => money(right.salary) - money(left.salary) || left.id - right.id);
      case "salary_asc":
        return nextEmployees.sort((left, right) => money(left.salary) - money(right.salary) || left.id - right.id);
      case "hire_desc":
        return nextEmployees.sort((left, right) => right.hireDate.localeCompare(left.hireDate) || left.id - right.id);
      case "hire_asc":
        return nextEmployees.sort((left, right) => left.hireDate.localeCompare(right.hireDate) || left.id - right.id);
      default:
        return nextEmployees;
    }
  }, [employees, sortMode]);
  const employeesPagination = useClientPagination(sortedEmployees, 10);

  const fetchData = useCallback(async (nextKeyword = keyword, nextStatus = status) => {
    try {
      setLoading(true);
      const [summaryRes, departmentsRes, employeesRes, payrollRunsRes] = await Promise.all([
        enterpriseApi.summary(),
        enterpriseApi.departments(),
        enterpriseApi.employees({
          keyword: nextKeyword || undefined,
          status: nextStatus === "all" ? undefined : nextStatus,
        }),
        enterpriseApi.payrollRuns({ period: CURRENT_PAYROLL_PERIOD }),
      ]);
      setSummary(summaryRes.data);
      setDepartments(departmentsRes.data);
      setEmployees(employeesRes.data);
      setPayrollRuns(payrollRunsRes.data);
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
        const [summaryRes, departmentsRes, employeesRes, payrollRunsRes] = await Promise.all([
          enterpriseApi.summary(),
          enterpriseApi.departments(),
          enterpriseApi.employees({ status: "active" }),
          enterpriseApi.payrollRuns({ period: CURRENT_PAYROLL_PERIOD }),
        ]);
        if (cancelled) return;
        setSummary(summaryRes.data);
        setDepartments(departmentsRes.data);
        setEmployees(employeesRes.data);
        setPayrollRuns(payrollRunsRes.data);
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
          salary: acc.salary + payroll.attendance.payableSalary,
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
  const currentPayrollRun = payrollRuns[0] || null;

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
        overtimeBase: projectedPayroll.overtime.base,
        weekdayOvertimeHours: projectedPayroll.overtime.weekdayHours,
        restDayOvertimeHours: projectedPayroll.overtime.restDayHours,
        holidayOvertimeHours: projectedPayroll.overtime.holidayHours,
        overtimePay: projectedPayroll.overtime.totalPay,
        overtimePolicyNote: `工作日延时150%，休息日未调休200%，法定节假日300%；小时工资=加班基数/21.75/8。`,
        taxEstimate: projectedPayroll.taxEstimate,
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

  const handleCreatePayrollRun = async () => {
    try {
      setPayrollBusy(true);
      const res = await enterpriseApi.createPayrollRun({ period: CURRENT_PAYROLL_PERIOD });
      setPayrollRuns([res.data]);
      Message.success("薪酬月结批次已生成");
      await fetchData(keyword, status);
    } catch {
      Message.error("薪酬月结批次生成失败，可能当前月份已有批次");
    } finally {
      setPayrollBusy(false);
    }
  };

  const handleClosePayrollRun = async () => {
    if (!currentPayrollRun) return;
    try {
      setPayrollBusy(true);
      const res = await enterpriseApi.closePayrollRun(currentPayrollRun.id);
      setPayrollRuns([res.data]);
      Message.success("薪酬月结批次已锁定");
    } catch {
      Message.error("薪酬月结批次锁定失败");
    } finally {
      setPayrollBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-[1600px] animate-fade-in">
      <PageHeader
        title="人员薪酬"
        subtitle={summary?.company
          ? `${summary.company.name} · 每人独立维护工资、考勤后应发、累计预扣个税、银行卡到账和公司总成本`
          : "每人独立维护工资、考勤后应发、累计预扣个税、银行卡到账和公司总成本"}
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

      <Row gutter={[16, 16]} className="metric-grid">
        <Col xs={12} md={6}>
          <MetricCard
            title="公司总成本"
            amount={compensationSummary.monthlyCost || summary?.monthlyPeopleCost || 0}
            caption="应发工资 + 公司五险 + 公司公积金"
          />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard
            title="个人银行卡到账"
            amount={compensationSummary.netPayEstimate}
            caption={`扣除个人五险、公积金和个税后的 ${employees.length} 人到账合计`}
            tone="income"
          />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard
            title="应发工资"
            amount={compensationSummary.salary}
            caption="按考勤打卡校正后的应发"
            tone="neutral"
          />
        </Col>
        <Col xs={12} md={6}>
          <MetricCard
            title="公司承担缴费"
            amount={compensationSummary.companyContribution}
            caption="公司承担五险 + 公司公积金"
          />
        </Col>
      </Row>

      <Card className="mb-6" style={{ borderRadius: 12 }}>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <div className="font-medium">薪酬月结批次</div>
              <Tag color={currentPayrollRun?.status === "closed" ? "green" : currentPayrollRun ? "arcoblue" : "gray"}>
                {currentPayrollRun?.status === "closed" ? "已锁定" : currentPayrollRun ? "待复核" : "未生成"}
              </Tag>
            </div>
            <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
              {CURRENT_PAYROLL_PERIOD} · 生成后保存当前员工薪酬快照，锁定后作为当月发薪与审计依据
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              icon={<IconPlus />}
              disabled={Boolean(currentPayrollRun)}
              loading={payrollBusy && !currentPayrollRun}
              onClick={handleCreatePayrollRun}
            >
              生成批次
            </Button>
            <Button
              type="primary"
              icon={<IconCheckCircle />}
              disabled={!currentPayrollRun || currentPayrollRun.status === "closed"}
              loading={payrollBusy && Boolean(currentPayrollRun)}
              onClick={handleClosePayrollRun}
            >
              锁定月结
            </Button>
          </div>
        </div>
        <div className="bi-segment-grid mt-4 grid md:grid-cols-4">
          <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
            <div className="text-xs" style={{ color: "var(--text-color-3)" }}>月结人数</div>
            <div className="mt-1 font-semibold">{currentPayrollRun?.employeeCount ?? employees.length} 人</div>
          </div>
          <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
            <div className="text-xs" style={{ color: "var(--text-color-3)" }}>银行实发</div>
            <AmountDisplay amount={currentPayrollRun?.netPayTotal ?? compensationSummary.netPayEstimate} type={1} size="small" />
          </div>
          <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
            <div className="text-xs" style={{ color: "var(--text-color-3)" }}>个税预扣</div>
            <AmountDisplay amount={currentPayrollRun?.taxTotal ?? compensationSummary.taxEstimate} type={2} size="small" />
          </div>
          <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
            <div className="text-xs" style={{ color: "var(--text-color-3)" }}>公司总成本</div>
            <AmountDisplay amount={currentPayrollRun?.companyCostTotal ?? compensationSummary.monthlyCost} type={2} size="small" />
          </div>
        </div>
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24}>
          <Card style={{ borderRadius: 12 }}>
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="font-medium">员工薪酬档案</div>
                <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                  个人到账 = 考勤后应发 - 个人五险 - 个人公积金 - 个税累计预扣 - 其他扣减；公司总成本 = 考勤后应发 + 公司五险 + 公司公积金
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
                <Select
                  value={sortMode}
                  onChange={(value) => {
                    setSortMode(value as EmployeeSortMode);
                    employeesPagination.resetPage();
                    tableScrollRef.current?.scrollTo({ left: 0 });
                  }}
                  style={{ width: 152 }}
                >
                  <Select.Option value="default">默认排序</Select.Option>
                  <Select.Option value="salary_desc">工资高到低</Select.Option>
                  <Select.Option value="salary_asc">工资低到高</Select.Option>
                  <Select.Option value="hire_desc">入职最新</Select.Option>
                  <Select.Option value="hire_asc">入职最早</Select.Option>
                </Select>
                <Button type="primary" onClick={handleSearch}>搜索</Button>
              </div>
            </div>

            <div
              ref={tableScrollRef}
              className="overflow-x-auto rounded-lg border"
              style={{ borderColor: "var(--border-color)" }}
            >
              <table className="w-full min-w-[1560px] table-fixed border-collapse text-sm">
                <colgroup>
                  {tableColumns.map((column) => (
                    <col key={column.label} style={{ width: column.width }} />
                  ))}
                </colgroup>
                <thead>
                  <tr
                    style={{
                      background: "linear-gradient(180deg, var(--color-bg-2) 0%, var(--color-bg-3) 100%)",
                    }}
                  >
                    {tableColumns.map((column) => (
                      <th
                        key={column.label}
                        scope="col"
                        className="border-b px-4 py-3.5 text-left align-top"
                        style={{
                          borderColor: "var(--border-color)",
                          color: "var(--text-color-2)",
                        }}
                      >
                        <div className="flex min-w-0 items-start gap-2.5">
                          <span
                            className="mt-0.5 h-8 w-1.5 shrink-0 rounded-full"
                            style={{ backgroundColor: column.accent }}
                            aria-hidden="true"
                          />
                          <div className="min-w-0">
                            <div className="truncate text-[13px] font-semibold leading-5" style={{ color: "var(--text-color-1)" }}>
                              {column.label}
                            </div>
                            <div className="truncate text-xs font-normal leading-5" style={{ color: "var(--text-color-3)" }}>
                              {column.description}
                            </div>
                          </div>
                        </div>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr>
                      <td colSpan={tableColumns.length} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>加载中...</td>
                    </tr>
                  ) : employees.length === 0 ? (
                    <tr>
                      <td colSpan={tableColumns.length} className="px-4 py-12 text-center" style={{ color: "var(--text-color-3)" }}>暂无薪酬数据</td>
                    </tr>
                  ) : employeesPagination.pagedData.map((employee) => {
                    const statusConfig = statusLabels[employee.status] || { label: employee.status, color: "gray" };
                    const payroll = payrollOf(employee);
                    const housingBase = payroll.housingFundBase;
                    const housingPersonalRate = payroll.housingFundPersonalRate;
                    const housingCompanyRate = payroll.housingFundCompanyRate;
                    const taxAndOtherDeduction = payroll.taxEstimate + payroll.personalDeduction;
                    return (
                      <tr key={employee.id} className="border-b transition-colors hover:bg-black/[0.015] dark:hover:bg-white/[0.03]" style={{ borderColor: "var(--border-color-light)" }}>
                        <td className="px-4 py-4 align-middle">
                          <div className="flex items-start justify-between gap-2">
                            <div className="min-w-0">
                              <div className="flex flex-wrap items-center gap-2">
                                <div className="whitespace-nowrap font-medium" style={{ color: "var(--text-color-1)" }}>{employee.name}</div>
                                <Tag color={statusConfig.color}>{statusConfig.label}</Tag>
                              </div>
                            </div>
                            <Button
                              type="text"
                              size="mini"
                              icon={<IconEdit />}
                              title="编辑薪酬档案"
                              onClick={() => openCompensationEditor(employee)}
                            />
                          </div>
                          <div className="mt-1 break-all text-xs" style={{ color: "var(--text-color-3)" }}>{employee.email}</div>
                          <div className="mt-2 text-xs" style={{ color: "var(--text-color-3)" }}>
                            {employee.departmentName || "未分配部门"} · {employee.position}
                          </div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                            入职 {employee.hireDate}
                          </div>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <div className="rounded-xl border p-3" style={{ borderColor: "rgba(16, 185, 129, 0.24)", backgroundColor: "rgba(16, 185, 129, 0.06)" }}>
                            <div className="text-xs font-medium" style={{ color: "var(--text-color-3)" }}>个人银行卡到账</div>
                            <div className="mt-1">
                              <AmountDisplay amount={payroll.netPayEstimate} type={1} size="medium" />
                            </div>
                            <div className="mt-2 space-y-0.5">
                              <BreakdownLine label="应发工资" value={payroll.attendance.payableSalary} strong />
                              {payroll.overtime.totalPay > 0 && <BreakdownLine label="其中加班费" value={payroll.overtime.totalPay} />}
                              <BreakdownLine label="个人五险" value={payroll.socialPersonalAmount} sign="-" />
                              <BreakdownLine label="个人公积金" value={payroll.housingPersonalAmount} sign="-" />
                              <BreakdownLine label="个税/其他" value={taxAndOtherDeduction} sign="-" />
                            </div>
                          </div>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <div className="rounded-xl border p-3" style={{ borderColor: "rgba(239, 68, 68, 0.22)", backgroundColor: "rgba(239, 68, 68, 0.05)" }}>
                            <div className="text-xs font-medium" style={{ color: "var(--text-color-3)" }}>公司总成本</div>
                            <div className="mt-1">
                              <AmountDisplay amount={payroll.monthlyCost} type={2} size="medium" />
                            </div>
                            <div className="mt-2 space-y-0.5">
                              <BreakdownLine label="应发工资" value={payroll.attendance.payableSalary} strong />
                              {payroll.overtime.totalPay > 0 && <BreakdownLine label="其中加班费" value={payroll.overtime.totalPay} />}
                              <BreakdownLine label="公司五险" value={payroll.socialCompanyAmount} sign="+" />
                              <BreakdownLine label="公司公积金" value={payroll.housingCompanyAmount} sign="+" />
                              <BreakdownLine label="公司承担合计" value={payroll.companyContribution} sign="+" />
                            </div>
                          </div>
                        </td>
                        <td className="px-4 py-4 align-middle">
                          <div className="space-y-2 text-xs">
                            <div className="flex items-center justify-between gap-2">
                              <span className="font-medium" style={{ color: "var(--text-color-2)" }}>考勤打卡</span>
                              <Tag color="green">{payroll.attendance.status}</Tag>
                            </div>
                            <div className="grid grid-cols-2 gap-x-3 gap-y-1" style={{ color: "var(--text-color-3)" }}>
                              <span>计薪 {payroll.attendance.paidDays}/{payroll.attendance.standardDays} 天</span>
                              <span>缺卡 {payroll.attendance.missingPunches} 次</span>
                              <span>缺勤 {payroll.attendance.absenceDays} 天</span>
                              <span>扣减 {currencyShort(payroll.attendance.attendanceDeduction)}</span>
                              <span>加班 {payroll.overtime.totalHours} 小时</span>
                              <span>加班费 {currencyShort(payroll.overtime.totalPay)}</span>
                            </div>
                            <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
                              <div className="flex items-center justify-between gap-2">
                                <span style={{ color: "var(--text-color-3)" }}>个税预扣</span>
                                <AmountDisplay amount={payroll.taxEstimate} type={2} size="small" />
                              </div>
                              <div className="mt-1" style={{ color: "var(--text-color-3)" }}>
                                第 {payroll.taxWithholding.currentMonth} 月 · 累计应税 {currencyShort(payroll.taxWithholding.cumulativeTaxableIncome)} · 税率 {formatPercent(payroll.taxWithholding.rate)}
                              </div>
                            </div>
                          </div>
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
                                className="grid grid-cols-[78px_112px_1fr] items-center gap-2 text-xs"
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
                          <div className="text-xs" style={{ color: "var(--text-color-3)" }}>缴存基数</div>
                          <div className="font-medium"><AmountDisplay amount={housingBase} size="small" /></div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                            个人 {formatPercent(housingPersonalRate)} · <AmountDisplay amount={payroll.housingPersonalAmount} size="small" />
                          </div>
                          <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                            公司 {formatPercent(housingCompanyRate)} · <AmountDisplay amount={payroll.housingCompanyAmount} type={2} size="small" />
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
        <Col xs={24} lg={12}>
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
        <Col xs={24} lg={12}>
          <Card style={{ borderRadius: 12 }} title="深圳五险一金政策">
            <div className="grid gap-3 md:grid-cols-2">
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
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>银行卡到账</div>
                <AmountDisplay amount={projectedPayroll.netPayEstimate} />
              </div>
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>公司缴费</div>
                <AmountDisplay amount={projectedPayroll.companyContribution} type={2} />
              </div>
              <div>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>本月个税</div>
                <AmountDisplay amount={projectedPayroll.taxEstimate} type={2} />
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
              <div
                className="rounded-lg border p-3"
                style={{ borderColor: "var(--border-color-light)", backgroundColor: "var(--color-fill-1)" }}
              >
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>个税累计预扣</div>
                <div className="mt-1">
                  <AmountDisplay amount={projectedPayroll.taxEstimate} type={2} />
                </div>
                <div className="mt-1 text-xs" style={{ color: "var(--text-color-3)" }}>
                  第 {projectedPayroll.taxWithholding.currentMonth} 月 · 累计应税 {currencyShort(projectedPayroll.taxWithholding.cumulativeTaxableIncome)}
                </div>
              </div>
              <NumberField label="其他个人扣减" value={compensationForm.personalDeduction} onChange={(value) => updateForm("personalDeduction", value)} />
            </div>
            <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
              <div className="font-medium">国家加班费口径</div>
              <Tag color="orange">工作日150% / 休息日未调休200% / 法定节假日300%</Tag>
            </div>
            <div className="mt-3 grid gap-3 md:grid-cols-4">
              <NumberField label="加班工资基数 ≥2520" value={compensationForm.overtimeBase} onChange={(value) => updateForm("overtimeBase", value)} />
              <NumberField label="工作日延时小时" value={compensationForm.weekdayOvertimeHours} onChange={(value) => updateForm("weekdayOvertimeHours", value)} />
              <NumberField label="休息日未调休小时" value={compensationForm.restDayOvertimeHours} onChange={(value) => updateForm("restDayOvertimeHours", value)} />
              <NumberField label="法定节假日小时" value={compensationForm.holidayOvertimeHours} onChange={(value) => updateForm("holidayOvertimeHours", value)} />
            </div>
            <div className="mt-3 grid gap-3 md:grid-cols-3 xl:grid-cols-6">
              <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>制度工作日</div>
                <div className="mt-1 font-semibold">{projectedPayroll.attendance.monthlyWorkDays} 天</div>
              </div>
              <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>月计薪天数</div>
                <div className="mt-1 font-semibold">{projectedPayroll.attendance.standardDays} 天</div>
              </div>
              <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>加班小时工资</div>
                <div className="mt-1 font-semibold">{currencyText(projectedPayroll.overtime.hourlyRate)}</div>
              </div>
              <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>加班合计</div>
                <div className="mt-1 font-semibold">{projectedPayroll.overtime.totalHours} 小时</div>
              </div>
              <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>加班费</div>
                <div className="mt-1 font-semibold">{currencyText(projectedPayroll.overtime.totalPay)}</div>
              </div>
              <div className="rounded-lg px-3 py-2" style={{ backgroundColor: "var(--color-fill-1)" }}>
                <div className="text-xs" style={{ color: "var(--text-color-3)" }}>应发工资</div>
                <div className="mt-1 font-semibold">{currencyText(projectedPayroll.attendance.payableSalary)}</div>
              </div>
            </div>
          </div>

          <div>
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div className="font-medium">深圳社保口径</div>
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
              <NumberField label="工伤基数 ≥2520，无上限" value={compensationForm.workInjuryBase} onChange={(value) => updateForm("workInjuryBase", value)} />
              <NumberField label="工伤行业基准费率 0.2%-1.4%" value={compensationForm.workInjuryCompanyRate} precision={2} onChange={(value) => updateForm("workInjuryCompanyRate", value)} />
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
              <NumberField label="公积金基数 2520-44265" value={compensationForm.housingFundBase} onChange={(value) => updateForm("housingFundBase", value)} />
              <NumberField label="个人比例 5%-12%" value={compensationForm.housingFundPersonalRate} onChange={(value) => updateForm("housingFundPersonalRate", value)} />
              <NumberField label="公司比例 5%-12%" value={compensationForm.housingFundCompanyRate} onChange={(value) => updateForm("housingFundCompanyRate", value)} />
            </div>
          </div>
        </div>
      </Modal>
    </div>
  );
}
