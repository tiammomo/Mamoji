package com.mamoji.bootstrap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.people.application.DepartmentRepository;
import com.mamoji.people.application.EmployeeRepository;
import com.mamoji.people.application.EmploymentEventRepository;
import com.mamoji.people.domain.Employee;
import com.mamoji.people.domain.EmployeeCompensationPolicy;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.platform.identity.account.application.UserDirectory;
import com.mamoji.platform.tenant.CompanyRepository;
import com.mamoji.platform.tenant.EntityTransferRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class EnterpriseDataInitializerTest {
    @Test
    void bootstrapStartupDoesNotRewriteAlreadyHydratedEmployees() {
        EmployeeRepository employees = mock(EmployeeRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        Employee employee = hydratedEmployee();
        when(companies.existsAny()).thenReturn(true);
        when(companies.findAll()).thenReturn(List.of());
        when(employees.findAll()).thenReturn(List.of(employee));
        EnterpriseDataInitializer initializer = initializer(companies, employees);

        initializer.initialize();

        verify(employees, never()).update(employee);
    }

    @Test
    void bootstrapStartupPersistsAnEmployeeOnlyWhenLegacyDefaultsChange() {
        EmployeeRepository employees = mock(EmployeeRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        Employee employee = hydratedEmployee();
        employee.socialInsuranceRegion = null;
        when(companies.existsAny()).thenReturn(true);
        when(companies.findAll()).thenReturn(List.of());
        when(employees.findAll()).thenReturn(List.of(employee));
        EnterpriseDataInitializer initializer = initializer(companies, employees);

        initializer.initialize();

        verify(employees).update(employee);
    }

    private EnterpriseDataInitializer initializer(CompanyRepository companies, EmployeeRepository employees) {
        return new EnterpriseDataInitializer(
            mock(JdbcTemplate.class),
            mock(UserDirectory.class),
            mock(AuditTrailService.class),
            mock(DepartmentRepository.class),
            employees,
            mock(EmploymentEventRepository.class),
            companies,
            mock(EntityTransferRepository.class),
            "bootstrap",
            "Company",
            null,
            "Industry",
            "Taxpayer",
            "CNY"
        );
    }

    private Employee hydratedEmployee() {
        Employee employee = new Employee();
        employee.salary = new BigDecimal("10000");
        employee.socialInsurance = BigDecimal.ZERO;
        employee.housingFund = BigDecimal.ZERO;
        employee.taxEstimate = BigDecimal.ZERO;
        employee.accessRole = "employee";
        employee.accessScope = "self";
        employee.status = "active";
        EmployeeCompensationPolicy.initialize(employee, BigDecimal.ZERO);
        return employee;
    }
}
