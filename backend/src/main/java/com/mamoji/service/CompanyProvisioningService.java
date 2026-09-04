package com.mamoji.service;

import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a tenant root together with the durable accounting workspace required by online use. */
@Service
public class CompanyProvisioningService {
    private final CompanyRepository companies;
    private final CompanyMembershipRepository memberships;
    private final FinanceRepository finances;
    private final CategoryRepository categories;

    public CompanyProvisioningService(
        CompanyRepository companies,
        CompanyMembershipRepository memberships,
        FinanceRepository finances,
        CategoryRepository categories
    ) {
        this.companies = companies;
        this.memberships = memberships;
        this.finances = finances;
        this.categories = categories;
    }

    @Transactional
    public Company create(Company company) {
        companies.insert(company);
        memberships.ensureOwner(company);
        finances.ensureAccountingLedger(company.ownerId, company.id, company.currency, company.name);
        categories.ensureCompanyDefaults(company.ownerId, company.id);
        return company;
    }
}
