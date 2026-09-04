package com.mamoji.tax.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.tenant.CompanyRepository;
import com.mamoji.tax.application.TaxItemRepository;
import com.mamoji.tax.domain.TaxItemPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class TaxItemDataInitializerTest {
    @Test
    void bootstrapModeDoesNotCreateDemoTaxItems() {
        TaxItemRepository taxItems = mock(TaxItemRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TaxItemDataInitializer initializer = new TaxItemDataInitializer(
            taxItems, new TaxItemPolicy(), companies, transactions, "bootstrap"
        );

        initializer.initialize();

        verifyNoInteractions(taxItems, companies, transactions);
    }

    @Test
    void demoModeDoesNotRestoreTaxItemsAfterTheirLifecycleWasAudited() {
        TaxItemRepository taxItems = mock(TaxItemRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        Company company = new Company();
        company.id = 42;
        company.entityType = "company";
        company.policyProfileKey = "CN-TEST";
        when(transactions.getTransaction(any())).thenReturn(status);
        when(companies.findAll()).thenReturn(List.of(company));
        when(taxItems.findByCompany(42)).thenReturn(List.of());
        when(taxItems.hasLifecycleHistory(42)).thenReturn(true);
        TaxItemDataInitializer initializer = new TaxItemDataInitializer(
            taxItems, new TaxItemPolicy(), companies, transactions, "demo"
        );

        initializer.initialize();

        verify(taxItems, never()).insert(any());
        verify(transactions).commit(status);
    }
}
