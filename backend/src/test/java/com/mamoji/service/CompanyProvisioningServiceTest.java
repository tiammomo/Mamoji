package com.mamoji.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CompanyProvisioningServiceTest {
    @Test
    void createsTenantRootMembershipLedgerAndCategoriesAtOneBoundary() {
        CompanyRepository companies = mock(CompanyRepository.class);
        CompanyMembershipRepository memberships = mock(CompanyMembershipRepository.class);
        FinanceRepository finances = mock(FinanceRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        Company company = new Company();
        company.id = 42;
        company.ownerId = 7;
        company.name = "Provisioned company";
        company.currency = "CNY";
        CompanyProvisioningService provisioning = new CompanyProvisioningService(
            companies,
            memberships,
            finances,
            categories
        );

        Company result = provisioning.create(company);

        assertSame(company, result);
        InOrder order = inOrder(companies, memberships, finances, categories);
        order.verify(companies).insert(company);
        order.verify(memberships).ensureOwner(company);
        order.verify(finances).ensureAccountingLedger(7, 42, "CNY", "Provisioned company");
        order.verify(categories).ensureCompanyDefaults(7, 42);
    }
}
