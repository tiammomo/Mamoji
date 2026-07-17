package com.mamoji.platform.tenant;

import com.mamoji.repository.EnterpriseStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Keeps demo/bootstrap records aligned with the authoritative membership table. */
@Component
public class MembershipBootstrapSynchronizer {
    private final EnterpriseStore enterpriseStore;
    private final CompanyMembershipRepository memberships;

    public MembershipBootstrapSynchronizer(
        EnterpriseStore enterpriseStore,
        CompanyMembershipRepository memberships
    ) {
        this.enterpriseStore = enterpriseStore;
        this.memberships = memberships;
    }

    @PostConstruct
    void synchronize() {
        enterpriseStore.sortedCompanies().forEach(company -> {
            memberships.ensureOwner(company);
            enterpriseStore.sortedEmployees(company.id, false).forEach(memberships::synchronize);
        });
    }
}
