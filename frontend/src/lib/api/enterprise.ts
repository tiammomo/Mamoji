import client from "./client";
import type {
  Company,
  Department,
  Employee,
  EmployeePayload,
  EmploymentEvent,
  EnterpriseSummary,
  PermissionMatrix,
  TaxItem,
} from "@/lib/types";

export const enterpriseApi = {
  summary: (params?: { companyId?: number }) => client.get<EnterpriseSummary>("/enterprise/summary", { params }),
  permissionMatrix: () => client.get<PermissionMatrix>("/enterprise/permission-matrix"),
  companies: () => client.get<Company[]>("/enterprise/companies"),
  createCompany: (data: Partial<Company>) => client.post<Company>("/enterprise/companies", data),
  company: (params?: { companyId?: number }) => client.get<Company>("/enterprise/company", { params }),
  updateCompany: (data: Partial<Company>, params?: { companyId?: number }) => client.put<Company>("/enterprise/company", data, { params }),
  departments: (params?: { companyId?: number }) => client.get<Department[]>("/enterprise/departments", { params }),
  createDepartment: (data: { companyId?: number; name: string; costCenter: string; budget: number }) =>
    client.post<Department>("/enterprise/departments", data),
  employees: (params?: { companyId?: number; keyword?: string; status?: string; departmentId?: number }) =>
    client.get<Employee[]>("/enterprise/employees", { params }),
  createEmployee: (data: EmployeePayload) => client.post<Employee>("/enterprise/employees", data),
  updateEmployee: (id: number, data: Partial<EmployeePayload>) =>
    client.put<Employee>(`/enterprise/employees/${id}`, data),
  employmentEvents: (params?: { companyId?: number }) => client.get<EmploymentEvent[]>("/enterprise/employment-events", { params }),
  taxItems: (params?: { companyId?: number }) => client.get<TaxItem[]>("/enterprise/tax-items", { params }),
  updateTaxItem: (id: number, data: Partial<TaxItem>) =>
    client.put<TaxItem>(`/enterprise/tax-items/${id}`, data),
};
