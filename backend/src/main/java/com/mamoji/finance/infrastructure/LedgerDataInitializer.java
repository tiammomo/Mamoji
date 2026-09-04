package com.mamoji.finance.infrastructure;

import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Ensures every initialized company has one durable accounting workspace. */
@Component
@DependsOn("enterpriseDataInitializer")
public class LedgerDataInitializer {
    private final FinanceRepository finances;
    private final CompanyRepository companies;
    private final CompanyMembershipRepository companyMemberships;

    public LedgerDataInitializer(
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
