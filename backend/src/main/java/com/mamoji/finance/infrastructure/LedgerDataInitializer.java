package com.mamoji.finance.infrastructure;

import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.repository.EnterpriseStore;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Ensures every initialized company has one durable accounting workspace. */
@Component
@DependsOn("enterpriseStore")
public class LedgerDataInitializer {
    private final FinanceRepository finances;
    private final EnterpriseStore enterpriseStore;
    private final CompanyMembershipRepository companyMemberships;

    public LedgerDataInitializer(
        FinanceRepository finances,
        EnterpriseStore enterpriseStore,
        CompanyMembershipRepository companyMemberships
    ) {
        this.finances = finances;
        this.enterpriseStore = enterpriseStore;
        this.companyMemberships = companyMemberships;
    }

    @PostConstruct
    void initialize() {
        enterpriseStore.sortedCompanies().forEach(company -> {
            companyMemberships.ensureOwner(company);
            finances.ensureAccountingLedger(
                company.ownerId,
                company.id,
                company.currency,
                company.name
            );
        });
    }
}
