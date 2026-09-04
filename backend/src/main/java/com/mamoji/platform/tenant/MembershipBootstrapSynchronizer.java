package com.mamoji.platform.tenant;

import com.mamoji.people.application.EmployeeRepository;
import com.mamoji.repository.EnterpriseStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Keeps demo/bootstrap records aligned with the authoritative membership table. */
@Component
public class MembershipBootstrapSynchronizer {
    private final EnterpriseStore enterpriseStore;
    private final EmployeeRepository employees;
    private final CompanyMembershipRepository memberships;

    public MembershipBootstrapSynchronizer(
        EnterpriseStore enterpriseStore,
        EmployeeRepository employees,
        CompanyMembershipRepository memberships
    ) {
        this.enterpriseStore = enterpriseStore;
        this.employees = employees;
        this.memberships = memberships;
    }

    @PostConstruct
    void synchronize() {
        enterpriseStore.sortedCompanies().forEach(company -> {
            memberships.ensureOwner(company);
            employees.findByCompany(company.id, false).forEach(memberships::synchronize);
        });
    }
}
