package com.mamoji.finance.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.domain.Models.Company;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.repository.EnterpriseStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerDataInitializerTest {
    @Test
    void ensuresOneDurableAccountingWorkspaceForEveryInitializedCompany() {
        FinanceRepository finances = mock(FinanceRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        CompanyMembershipRepository memberships = mock(CompanyMembershipRepository.class);
        Company first = company(9, 3, "First company", "cny");
        Company second = company(10, 4, "Second company", "USD");
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(first, second));
        LedgerDataInitializer initializer = new LedgerDataInitializer(finances, enterpriseStore, memberships);

        initializer.initialize();

        verify(memberships).ensureOwner(first);
        verify(memberships).ensureOwner(second);
        verify(finances).ensureAccountingLedger(first.ownerId, first.id, first.currency, first.name);
        verify(finances).ensureAccountingLedger(second.ownerId, second.id, second.currency, second.name);
    }

    @Test
    void leavesFinancePersistenceUntouchedWhenNoCompanyWasInitialized() {
        FinanceRepository finances = mock(FinanceRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        CompanyMembershipRepository memberships = mock(CompanyMembershipRepository.class);
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of());
        LedgerDataInitializer initializer = new LedgerDataInitializer(finances, enterpriseStore, memberships);

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
