import client from "./client";
import type {
  Company,
  Department,
  Employee,
  EmployeePayload,
  EntityTransfer,
  EmploymentEvent,
  EnterpriseSummary,
  PermissionMatrix,
  TaxItem,
} from "@/lib/types";

type CompanyScopedParams = { companyId?: number };

const getActiveCompanyId = () => {
  if (typeof window === "undefined") return undefined;
  const value = Number(localStorage.getItem("activeCompanyId"));
  return Number.isFinite(value) && value > 0 ? value : undefined;
};

const withCompany = <T extends CompanyScopedParams>(params?: T): T => {
  const activeCompanyId = getActiveCompanyId();
  if (!activeCompanyId || params?.companyId) {
    return (params || {}) as T;
  }
  return { ...params, companyId: activeCompanyId } as T;
};

const withCompanyBody = <T extends CompanyScopedParams>(data: T): T => {
  const activeCompanyId = getActiveCompanyId();
  if (!activeCompanyId || data.companyId) {
    return data;
  }
  return { ...data, companyId: activeCompanyId };
};

export const enterpriseApi = {
  summary: (params?: { companyId?: number }) => client.get<EnterpriseSummary>("/enterprise/summary", { params: withCompany(params) }),
  permissionMatrix: () => client.get<PermissionMatrix>("/enterprise/permission-matrix"),
  companies: () => client.get<Company[]>("/enterprise/companies"),
  createCompany: (data: Partial<Company>) => client.post<Company>("/enterprise/companies", data),
  company: (params?: { companyId?: number }) => client.get<Company>("/enterprise/company", { params: withCompany(params) }),
  updateCompany: (data: Partial<Company>, params?: { companyId?: number }) =>
    client.put<Company>("/enterprise/company", data, { params: withCompany(params) }),
  departments: (params?: { companyId?: number }) => client.get<Department[]>("/enterprise/departments", { params: withCompany(params) }),
  createDepartment: (data: { companyId?: number; name: string; costCenter: string; budget: number }) =>
    client.post<Department>("/enterprise/departments", withCompanyBody(data)),
  employees: (params?: { companyId?: number; keyword?: string; status?: string; departmentId?: number }) =>
    client.get<Employee[]>("/enterprise/employees", { params: withCompany(params) }),
  createEmployee: (data: EmployeePayload) => client.post<Employee>("/enterprise/employees", withCompanyBody(data)),
  updateEmployee: (id: number, data: Partial<EmployeePayload>) =>
    client.put<Employee>(`/enterprise/employees/${id}`, data),
  employmentEvents: (params?: { companyId?: number }) => client.get<EmploymentEvent[]>("/enterprise/employment-events", { params: withCompany(params) }),
  taxItems: (params?: { companyId?: number }) => client.get<TaxItem[]>("/enterprise/tax-items", { params: withCompany(params) }),
  updateTaxItem: (id: number, data: Partial<TaxItem>) =>
    client.put<TaxItem>(`/enterprise/tax-items/${id}`, data),
  entityTransfers: (params?: { entityId?: number }) =>
    client.get<EntityTransfer[]>("/enterprise/entity-transfers", { params }),
  createEntityTransfer: (data: {
    fromEntityId: number;
    toEntityId: number;
    transferType: string;
    amount: number;
    currency?: string;
    transferDate?: string;
    note?: string | null;
  }) => client.post<EntityTransfer>("/enterprise/entity-transfers", data),
};
