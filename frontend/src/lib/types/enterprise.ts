export interface Company {
  id: number;
  name: string;
  entityType: "company" | "household" | string;
  creditCode: string | null;
  industry: string;
  taxpayerType: string;
  currency: string;
  country: string;
  province: string;
  city: string;
  district: string;
  registeredAddress?: string | null;
  operatingRegion: string;
  taxAuthority?: string | null;
  policyProfileKey: string;
  fiscalYearStartMonth: number;
  ownerId: number;
  createdAt: string;
  updatedAt: string;
}

export interface Department {
  id: number;
  companyId: number;
  name: string;
  costCenter: string;
  managerEmployeeId: number | null;
  budget: number;
  status: number;
  createdAt: string;
  updatedAt: string;
}

export type EmployeeStatus = "onboarding" | "probation" | "active" | "departed";
export type EmploymentType = "full_time" | "part_time" | "contractor" | "intern";
export type AccessRole = "founder" | "finance_admin" | "hr_admin" | "department_manager" | "employee" | "viewer";
export type AccessScope = "group" | "company" | "company_set" | "department" | "self" | "readonly";

export interface SocialInsuranceItem {
  key: string;
  name: string;
  category: string;
  base: number;
  minBase?: number | null;
  maxBase?: number | null;
  personalRate: number;
  companyRate: number;
  personalAmount: number;
  companyAmount: number;
  policyBasis: string;
  validPeriod: string;
  status: string;
}

export interface EmployeeCertificate {
  id?: number;
  employeeId?: number;
  name: string;
  category?: string | null;
  level?: string | null;
  issuer?: string | null;
  certificateNo?: string | null;
  issueDate?: string | null;
  expiryDate?: string | null;
  verificationStatus?: string;
  materialStatus?: string;
  note?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface EmployeeExperience {
  id?: number;
  employeeId?: number;
  type: string;
  organization: string;
  title?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  description?: string | null;
  achievements?: string | null;
  skills?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface Employee {
  id: number;
  companyId: number;
  userId: number | null;
  departmentId: number | null;
  departmentName?: string | null;
  employeeNo?: string | null;
  name: string;
  legalName?: string | null;
  preferredName?: string | null;
  email: string;
  phone?: string | null;
  position: string;
  directManagerEmployeeId?: number | null;
  jobLevel?: string | null;
  workLocation?: string | null;
  employmentType: EmploymentType | string;
  status: EmployeeStatus | string;
  accessRole: AccessRole | string;
  accessScope: AccessScope | string;
  hireDate: string;
  leaveDate?: string | null;
  probationStartDate?: string | null;
  probationEndDate?: string | null;
  contractStartDate?: string | null;
  contractEndDate?: string | null;
  contractType?: string | null;
  contractStatus?: string | null;
  educationLevel?: string | null;
  graduationSchool?: string | null;
  major?: string | null;
  graduationDate?: string | null;
  graduationYear?: number | null;
  graduateStatus?: string | null;
  skillTags?: string | null;
  resumeSummary?: string | null;
  materialStatus?: string | null;
  profileVerifiedAt?: string | null;
  profileVerifiedBy?: number | null;
  salary: number;
  overtimeBase: number;
  weekdayOvertimeHours: number;
  restDayOvertimeHours: number;
  holidayOvertimeHours: number;
  overtimePay: number;
  overtimePolicyNote?: string | null;
  socialInsurance: number;
  housingFund: number;
  taxEstimate: number;
  monthlyCost: number;
  socialInsuranceBase: number;
  socialInsurancePersonalRate: number;
  socialInsuranceCompanyRate: number;
  socialInsurancePersonalAmount: number;
  socialInsuranceCompanyAmount: number;
  housingFundBase: number;
  housingFundPersonalRate: number;
  housingFundCompanyRate: number;
  housingFundPersonalAmount: number;
  housingFundCompanyAmount: number;
  personalDeduction: number;
  netPayEstimate: number;
  socialInsuranceRegion: string;
  hukouType: "local" | "non_local" | string;
  medicalTier: "tier1" | "tier2" | string;
  pensionBase: number;
  medicalBase: number;
  unemploymentBase: number;
  workInjuryBase: number;
  maternityBase: number;
  workInjuryCompanyRate: number;
  socialInsurancePolicyNote?: string | null;
  socialInsuranceItems?: SocialInsuranceItem[];
  socialInsuranceWarnings?: string[];
  certificates?: EmployeeCertificate[];
  experiences?: EmployeeExperience[];
  emergencyContact?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface EmploymentEvent {
  id: number;
  companyId: number;
  employeeId: number;
  type: string;
  effectiveDate: string;
  note: string;
  operatorUserId: number;
  createdAt: string;
}

export interface TaxItem {
  id: number;
  companyId: number;
  name: string;
  period: string;
  taxType: string;
  taxableAmount: number;
  taxAmount: number;
  paidAmount: number;
  deductibleAmount: number;
  taxRate: number;
  dueDate: string;
  status: string;
  filingStatus: string;
  paymentStatus: string;
  frequency: string;
  declarationDate?: string | null;
  paymentDate?: string | null;
  responsiblePerson?: string | null;
  riskLevel: string;
  policyBasis?: string | null;
  sourceType: string;
  note?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TaxItemPayload {
  companyId?: number;
  name: string;
  period: string;
  taxType: string;
  taxableAmount: number;
  taxAmount: number;
  paidAmount?: number;
  deductibleAmount?: number;
  taxRate?: number;
  dueDate: string;
  status: string;
  filingStatus?: string;
  paymentStatus?: string;
  frequency?: string;
  declarationDate?: string | null;
  paymentDate?: string | null;
  responsiblePerson?: string | null;
  riskLevel?: string;
  policyBasis?: string | null;
  sourceType?: string;
  note?: string | null;
}

export interface TaxPolicySource {
  name: string;
  url: string;
}

export interface TaxPolicyProfile {
  key: string;
  name: string;
  region: string;
  taxAuthority: string;
  taxpayerType: string;
  vatFrequency: string;
  vatMode: string;
  inputDeductionEnabled: boolean;
  fiscalYearStartMonth: number;
  smallScaleMonthlyVatExemption: number;
  smallScaleQuarterlyVatExemption: number;
  smallScaleVatPolicyValidTo: string;
  generalTaxpayerSalesThreshold: number;
  coreTaxes: string[];
  policySources: TaxPolicySource[];
}

export interface TaxFilingCalendarItem {
  key: string;
  taxType: string;
  taxTypeName: string;
  period: string;
  frequency: string;
  dueDate: string;
  required: boolean;
  zeroDeclarationRequired: boolean;
  matchedTaxItemId?: number | null;
  status: string;
  filingStatus: string;
  paymentStatus: string;
  riskLevel: string;
  policyBasis: string;
  note: string;
}

export interface TaxComplianceRiskItem {
  key: string;
  severity: "high" | "medium" | "low" | string;
  title: string;
  description: string;
  taxType: string;
  taxTypeName: string;
  period: string;
  dueDate?: string | null;
  taxItemId?: number | null;
  action: string;
  policyBasis: string;
}

export interface TaxComplianceMetrics {
  riskCount: number;
  highRiskCount: number;
  mediumRiskCount: number;
  missingPeriodCount: number;
  zeroDeclarationOpenCount: number;
  dueSoonCount: number;
  filingCompletionRate: number;
  receiptGapCount: number;
}

export interface TaxComplianceReport {
  policyProfile: TaxPolicyProfile;
  filingCalendar: TaxFilingCalendarItem[];
  riskItems: TaxComplianceRiskItem[];
  metrics: TaxComplianceMetrics;
  assumptions: string[];
}

export interface EntityTransfer {
  id: number;
  fromEntityId: number;
  toEntityId: number;
  fromEntityName?: string | null;
  toEntityName?: string | null;
  transferType: string;
  amount: number;
  currency: string;
  transferDate: string;
  note?: string | null;
  status: string;
  operatorUserId: number;
  createdAt: string;
  updatedAt: string;
}

export interface PayrollRunItem {
  id: number;
  runId: number;
  companyId: number;
  employeeId: number;
  employeeName: string;
  departmentName?: string | null;
  period: string;
  salary: number;
  payableSalary: number;
  socialPersonalAmount: number;
  socialCompanyAmount: number;
  housingPersonalAmount: number;
  housingCompanyAmount: number;
  taxAmount: number;
  personalDeduction: number;
  netPay: number;
  companyCost: number;
  snapshotJson: string;
  createdAt: string;
}

export interface PayrollRun {
  id: number;
  companyId: number;
  period: string;
  name: string;
  status: "draft" | "closed" | string;
  employeeCount: number;
  salaryTotal: number;
  socialPersonalTotal: number;
  socialCompanyTotal: number;
  housingPersonalTotal: number;
  housingCompanyTotal: number;
  taxTotal: number;
  personalDeductionTotal: number;
  netPayTotal: number;
  companyCostTotal: number;
  createdByUserId: number;
  closedByUserId?: number | null;
  closedAt?: string | null;
  createdAt: string;
  updatedAt: string;
  items?: PayrollRunItem[];
}

export interface EnterpriseSummary {
  company: Company;
  departmentCount: number;
  employeeCount: number;
  activeEmployeeCount: number;
  onboardingCount: number;
  departedCount: number;
  hiresThisMonth: number;
  departuresThisMonth: number;
  monthlyPeopleCost: number;
  pendingTaxAmount: number;
  nextTaxDueDate?: string | null;
}

export interface EmployeePayload {
  companyId?: number;
  userId?: number | null;
  departmentId?: number | null;
  employeeNo?: string | null;
  name: string;
  legalName?: string | null;
  preferredName?: string | null;
  email: string;
  phone?: string | null;
  position: string;
  directManagerEmployeeId?: number | null;
  jobLevel?: string | null;
  workLocation?: string | null;
  employmentType: string;
  status: string;
  accessRole: string;
  accessScope: string;
  hireDate: string;
  leaveDate?: string | null;
  probationStartDate?: string | null;
  probationEndDate?: string | null;
  contractStartDate?: string | null;
  contractEndDate?: string | null;
  contractType?: string | null;
  contractStatus?: string | null;
  educationLevel?: string | null;
  graduationSchool?: string | null;
  major?: string | null;
  graduationDate?: string | null;
  graduationYear?: number | null;
  graduateStatus?: string | null;
  skillTags?: string | null;
  resumeSummary?: string | null;
  materialStatus?: string | null;
  profileVerifiedAt?: string | null;
  profileVerifiedBy?: number | null;
  salary: number;
  overtimeBase?: number;
  weekdayOvertimeHours?: number;
  restDayOvertimeHours?: number;
  holidayOvertimeHours?: number;
  overtimePay?: number;
  overtimePolicyNote?: string | null;
  socialInsurance: number;
  housingFund: number;
  taxEstimate: number;
  socialInsuranceBase?: number;
  socialInsurancePersonalRate?: number;
  socialInsuranceCompanyRate?: number;
  socialInsuranceRegion?: string;
  hukouType?: string;
  medicalTier?: string;
  pensionBase?: number;
  medicalBase?: number;
  unemploymentBase?: number;
  workInjuryBase?: number;
  maternityBase?: number;
  workInjuryCompanyRate?: number;
  socialInsurancePolicyNote?: string | null;
  housingFundBase?: number;
  housingFundPersonalRate?: number;
  housingFundCompanyRate?: number;
  personalDeduction?: number;
  emergencyContact?: string | null;
  certificates?: EmployeeCertificate[];
  experiences?: EmployeeExperience[];
}

export interface PermissionMatrix {
  roles: { key: string; name: string; description: string }[];
  scopes: { key: string; name: string; description: string }[];
  permissions: { key: string; name: string }[];
  matrix: { role: string; defaultScope: string; permissions: string[] }[];
}

export interface ProductModules {
  mode: string;
  enabled: string[];
}

export interface AccessContext {
  actor: import("./user").User;
  company: Company;
  companies: Company[];
  role: AccessRole | string;
  scope: AccessScope | string;
  departmentId: number | null;
  permissions: string[];
  modules: ProductModules;
}
