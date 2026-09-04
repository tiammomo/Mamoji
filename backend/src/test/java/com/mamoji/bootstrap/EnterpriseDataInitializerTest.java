package com.mamoji.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.people.application.DepartmentRepository;
import com.mamoji.people.application.EmployeeRepository;
import com.mamoji.people.application.EmploymentEventRepository;
import com.mamoji.people.domain.Department;
import com.mamoji.people.domain.Employee;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.platform.identity.account.application.UserDirectory;
import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EnterpriseDataInitializerTest {
    @Test
    void bootstrapStartupWithExistingCompanyDoesNotScanOrRewriteBusinessData() {
        CompanyRepository companies = mock(CompanyRepository.class);
        EmployeeRepository employees = mock(EmployeeRepository.class);
        CompanyMembershipRepository memberships = mock(CompanyMembershipRepository.class);
        when(companies.existsAny()).thenReturn(true);

        initializer(
            companies,
            employees,
            memberships,
            mock(UserDirectory.class),
            mock(DepartmentRepository.class),
            mock(EmploymentEventRepository.class)
        ).initialize();

        verify(companies).existsAny();
        verify(companies, never()).findAll();
        verify(companies, never()).update(any());
        verifyNoInteractions(employees, memberships);
    }

    @Test
    void bootstrapFirstRunCreatesMembershipsAtTheSameBoundaryAsSeedRecords() {
        CompanyRepository companies = mock(CompanyRepository.class);
        EmployeeRepository employees = mock(EmployeeRepository.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        EmploymentEventRepository events = mock(EmploymentEventRepository.class);
        CompanyMembershipRepository memberships = mock(CompanyMembershipRepository.class);
        UserDirectory users = mock(UserDirectory.class);
        when(companies.existsAny()).thenReturn(false);
        when(users.findAll()).thenReturn(List.of(
            new UserDirectory.Entry(7L, "owner@mamoji.test", "Owner", "", null, 1, 15)
        ));
        when(companies.insert(any())).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            company.id = 101L;
            return company;
        });
        when(departments.insert(any())).thenAnswer(invocation -> {
            Department department = invocation.getArgument(0);
            department.id = 201L;
            return department;
        });
        when(employees.insert(any())).thenAnswer(invocation -> {
            Employee employee = invocation.getArgument(0);
            employee.id = 301L;
            return employee;
        });
        when(events.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        initializer(companies, employees, memberships, users, departments, events).initialize();

        ArgumentCaptor<Company> company = ArgumentCaptor.forClass(Company.class);
        verify(memberships).ensureOwner(company.capture());
        assertEquals(101L, company.getValue().id);
        assertEquals(7L, company.getValue().ownerId);

        ArgumentCaptor<Employee> employee = ArgumentCaptor.forClass(Employee.class);
        verify(memberships).synchronize(employee.capture());
        assertEquals(301L, employee.getValue().id);
        assertEquals(7L, employee.getValue().userId);
        assertEquals("founder", employee.getValue().accessRole);
        assertEquals("company", employee.getValue().accessScope);
        verify(employees, never()).findAll();
    }

    @Test
    void legacyFullTableMembershipSynchronizerStaysRemoved() {
        assertThrows(
            ClassNotFoundException.class,
            () -> Class.forName("com.mamoji.platform.tenant.MembershipBootstrapSynchronizer")
        );
    }

    private EnterpriseDataInitializer initializer(
        CompanyRepository companies,
        EmployeeRepository employees,
        CompanyMembershipRepository memberships,
        UserDirectory users,
        DepartmentRepository departments,
        EmploymentEventRepository events
    ) {
        return new EnterpriseDataInitializer(
            users,
            mock(AuditTrailService.class),
            departments,
            employees,
            events,
            companies,
            memberships,
            "Company",
            null,
            "Industry",
            "Taxpayer",
            "CNY"
        );
    }
}
