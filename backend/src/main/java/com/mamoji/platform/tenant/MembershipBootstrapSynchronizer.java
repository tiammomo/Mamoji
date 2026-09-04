package com.mamoji.platform.tenant;

import com.mamoji.people.application.EmployeeRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Keeps demo/bootstrap records aligned with the authoritative membership table. */
@Component
@DependsOn("enterpriseDataInitializer")
public class MembershipBootstrapSynchronizer {
    private final CompanyRepository companies;
    private final EmployeeRepository employees;
    private final CompanyMembershipRepository memberships;

    public MembershipBootstrapSynchronizer(
        CompanyRepository companies,
        EmployeeRepository employees,
        CompanyMembershipRepository memberships
    ) {
        this.companies = companies;
        this.employees = employees;
        this.memberships = memberships;
    }

    @PostConstruct
    void synchronize() {
        companies.findAll().forEach(company -> {
            memberships.ensureOwner(company);
            employees.findByCompany(company.id, false).forEach(memberships::synchronize);
        });
    }
}
