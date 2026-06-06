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

export interface Employee {
  id: number;
  companyId: number;
  userId: number | null;
  departmentId: number | null;
  departmentName?: string | null;
  name: string;
  email: string;
  phone?: string | null;
  position: string;
  employmentType: EmploymentType | string;
  status: EmployeeStatus | string;
  accessRole: AccessRole | string;
  accessScope: AccessScope | string;
  hireDate: string;
  leaveDate?: string | null;
  salary: number;
  socialInsurance: number;
  housingFund: number;
  taxEstimate: number;
  monthlyCost: number;
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
  dueDate: string;
  status: string;
  note?: string | null;
  createdAt: string;
  updatedAt: string;
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
  name: string;
  email: string;
  phone?: string | null;
  position: string;
  employmentType: string;
  status: string;
  accessRole: string;
  accessScope: string;
  hireDate: string;
  leaveDate?: string | null;
  salary: number;
  socialInsurance: number;
  housingFund: number;
  taxEstimate: number;
  emergencyContact?: string | null;
}

export interface PermissionMatrix {
  roles: { key: string; name: string; description: string }[];
  scopes: { key: string; name: string; description: string }[];
  permissions: { key: string; name: string }[];
  matrix: { role: string; defaultScope: string; permissions: string[] }[];
}
