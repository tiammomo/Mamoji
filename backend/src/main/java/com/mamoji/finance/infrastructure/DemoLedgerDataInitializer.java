package com.mamoji.finance.infrastructure;

import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Keeps local demo workspaces repairable without registering a production startup scan. */
@Component("ledgerDataInitializer")
@ConditionalOnProperty(name = "mamoji.bootstrap.mode", havingValue = "demo", matchIfMissing = true)
@DependsOn("enterpriseDataInitializer")
public class DemoLedgerDataInitializer {
    private final FinanceRepository finances;
    private final CompanyRepository companies;
    private final CompanyMembershipRepository companyMemberships;

    public DemoLedgerDataInitializer(
        FinanceRepository finances,
        CompanyRepository companies,
        CompanyMembershipRepository companyMemberships
    ) {
        this.finances = finances;
        this.companies = companies;
        this.companyMemberships = companyMemberships;
    }

    @PostConstruct
    void initialize() {
        companies.findAll().forEach(company -> {
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
