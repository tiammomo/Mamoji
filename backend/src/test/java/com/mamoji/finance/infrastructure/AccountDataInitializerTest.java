package com.mamoji.finance.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.domain.Models.Company;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.finance.domain.Account;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.repository.EnterpriseStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountDataInitializerTest {
    @Test
    void bootstrapModeDoesNotCreateDemoAccounts() {
        FinanceRepository finances = mock(FinanceRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        AccountDataInitializer initializer = new AccountDataInitializer(
            finances,
            enterpriseStore,
            "bootstrap"
        );

        initializer.initialize();

        verifyNoInteractions(finances, enterpriseStore);
    }

    @Test
    void demoModeCreatesTypedCompanyScopedAccountsOnlyOnce() {
        FinanceRepository finances = mock(FinanceRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        Company company = company();
        Ledger ledger = ledger();
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(company));
        when(finances.findAccounts(company.ownerId, company.id))
            .thenReturn(List.of())
            .thenReturn(List.of(new Account()));
        when(finances.ensureAccountingLedger(company.ownerId, company.id, company.currency, company.name))
            .thenReturn(ledger);
        AccountDataInitializer initializer = new AccountDataInitializer(finances, enterpriseStore, "demo");

        initializer.initialize();
        initializer.initialize();

        ArgumentCaptor<Account> inserted = ArgumentCaptor.forClass(Account.class);
        verify(finances, times(3)).insertAccount(inserted.capture());
        Map<String, Account> accounts = inserted.getAllValues().stream()
            .collect(Collectors.toMap(account -> account.type, Function.identity()));
        assertEquals(List.of("bank", "cash", "credit"), accounts.keySet().stream().sorted().toList());
        for (Account account : accounts.values()) {
            assertEquals(company.id, account.companyId);
            assertEquals(company.ownerId, account.userId);
            assertEquals(ledger.id, account.ledgerId);
            assertEquals("CNY", account.currency);
            assertEquals(account.balance, account.availableBalance);
            assertEquals(BigDecimal.ZERO, account.frozenAmount);
            assertTrue(account.includeInNetWorth);
            assertEquals(1, account.status);
            assertNotNull(LocalDate.parse(account.openedAt));
            assertNotNull(LocalDate.parse(account.lastReconciledAt));
            assertNotNull(OffsetDateTime.parse(account.createdAt));
            assertEquals(account.createdAt, account.updatedAt);
        }
        assertEquals(new BigDecimal("1200"), accounts.get("cash").balance);
        assertEquals(new BigDecimal("26300"), accounts.get("bank").balance);
        assertEquals(new BigDecimal("1800"), accounts.get("credit").balance);
        assertEquals(new BigDecimal("20000"), accounts.get("credit").creditLimit);
        verify(finances, times(1)).ensureAccountingLedger(
            company.ownerId,
            company.id,
            company.currency,
            company.name
        );
    }

    @Test
    void demoModeLeavesExistingAccountsUntouched() {
        FinanceRepository finances = mock(FinanceRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        Company company = company();
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(company));
        when(finances.findAccounts(company.ownerId, company.id)).thenReturn(List.of(new Account()));
        AccountDataInitializer initializer = new AccountDataInitializer(finances, enterpriseStore, "demo");

        initializer.initialize();

        verify(finances, never()).ensureAccountingLedger(any(Long.class), any(Long.class), any(), any());
        verify(finances, never()).insertAccount(any());
    }

    private Company company() {
        Company company = new Company();
        company.id = 9;
        company.ownerId = 3;
        company.entityType = "company";
        company.currency = "cny";
        company.name = "Demo company";
        return company;
    }

    private Ledger ledger() {
        Ledger ledger = new Ledger();
        ledger.id = 31;
        ledger.companyId = 9L;
        return ledger;
    }
}
