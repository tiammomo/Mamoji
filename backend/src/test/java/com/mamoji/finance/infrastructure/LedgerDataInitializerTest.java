package com.mamoji.finance.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.platform.tenant.Company;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerDataInitializerTest {
    @Test
    void ensuresOneDurableAccountingWorkspaceForEveryInitializedCompany() {
        FinanceRepository finances = mock(FinanceRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        CompanyMembershipRepository memberships = mock(CompanyMembershipRepository.class);
        Company first = company(9, 3, "First company", "cny");
        Company second = company(10, 4, "Second company", "USD");
        when(companies.findAll()).thenReturn(List.of(first, second));
        LedgerDataInitializer initializer = new LedgerDataInitializer(finances, companies, memberships);

        initializer.initialize();

        verify(memberships).ensureOwner(first);
        verify(memberships).ensureOwner(second);
        verify(finances).ensureAccountingLedger(first.ownerId, first.id, first.currency, first.name);
        verify(finances).ensureAccountingLedger(second.ownerId, second.id, second.currency, second.name);
    }

    @Test
    void leavesFinancePersistenceUntouchedWhenNoCompanyWasInitialized() {
        FinanceRepository finances = mock(FinanceRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        CompanyMembershipRepository memberships = mock(CompanyMembershipRepository.class);
        when(companies.findAll()).thenReturn(List.of());
        LedgerDataInitializer initializer = new LedgerDataInitializer(finances, companies, memberships);

        initializer.initialize();

        verifyNoInteractions(finances);
        verifyNoInteractions(memberships);
    }

    private Company company(long id, long ownerId, String name, String currency) {
        Company company = new Company();
        company.id = id;
        company.ownerId = ownerId;
        company.name = name;
        company.currency = currency;
        return company;
    }
}
