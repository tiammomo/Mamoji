package com.mamoji.operations.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.platform.tenant.Company;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.finance.domain.Account;
import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.operations.application.TransactionQueryRepository;
import com.mamoji.operations.application.TransactionWriteRepository;
import com.mamoji.operations.domain.Category;
import com.mamoji.operations.domain.TransactionRecord;
import com.mamoji.platform.tenant.CompanyRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TransactionDataInitializerTest {
    @Test
    void bootstrapModeDoesNotCreateDemoTransactions() {
        TransactionQueryRepository transactions = mock(TransactionQueryRepository.class);
        TransactionWriteRepository writes = mock(TransactionWriteRepository.class);
        FinanceRepository finances = mock(FinanceRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        TransactionDataInitializer initializer = new TransactionDataInitializer(
            transactions,
            writes,
            finances,
            categories,
            companies,
            "bootstrap"
        );

        initializer.initialize();

        verifyNoInteractions(transactions, writes, finances, categories, companies);
    }

    @Test
    void demoModeCreatesCompanyScopedTransactionsOnlyOnce() {
        TransactionQueryRepository transactions = mock(TransactionQueryRepository.class);
        TransactionWriteRepository writes = mock(TransactionWriteRepository.class);
        FinanceRepository finances = mock(FinanceRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        Company company = company();
        Account cash = account(11, "公司现金备用金", 31L);
        Account bank = account(12, "公司基本户", 31L);
        List<Category> demoCategories = List.of(
            category(21, "主营业务收入", "income"),
            category(22, "团队餐饮", "expense"),
            category(23, "差旅交通", "expense"),
            category(24, "办公采购", "expense"),
            category(25, "客户退款", "expense"),
            category(26, "离职补偿", "expense")
        );
        when(companies.findAll()).thenReturn(List.of(company));
        when(transactions.findAll(company.ownerId, company.id))
            .thenReturn(List.of())
            .thenReturn(List.of(new TransactionRecord()));
        when(finances.findAccounts(company.ownerId, company.id)).thenReturn(List.of(cash, bank));
        when(categories.findAll(company.ownerId, company.id, null)).thenReturn(demoCategories);
        TransactionDataInitializer initializer = new TransactionDataInitializer(
            transactions,
            writes,
            finances,
            categories,
            companies,
            "demo"
        );

        initializer.initialize();
        initializer.initialize();

        ArgumentCaptor<TransactionRecord> inserted = ArgumentCaptor.forClass(TransactionRecord.class);
        verify(writes, times(7)).insert(inserted.capture());
        List<TransactionRecord> records = inserted.getAllValues();
        assertEquals(Set.of(
            "demo:income",
            "demo:pending-income",
            "demo:team-meal",
            "demo:travel",
            "demo:procurement",
            "demo:customer-refund",
            "demo:severance"
        ), records.stream().map(record -> record.idempotencyKey).collect(Collectors.toSet()));
        for (TransactionRecord record : records) {
            assertEquals(company.id, record.companyId);
            assertEquals(company.ownerId, record.userId);
            assertEquals(31L, record.familyId);
            assertEquals(BigDecimal.ZERO, record.refundedAmount);
            assertEquals(record.type == 2, record.isRefundable);
            assertNotNull(LocalDate.parse(record.date));
            assertNotNull(OffsetDateTime.parse(record.createdAt));
            assertEquals(record.createdAt, record.updatedAt);
        }
    }

    @Test
    void demoModeWaitsForRequiredAccountingFixtures() {
        TransactionQueryRepository transactions = mock(TransactionQueryRepository.class);
        TransactionWriteRepository writes = mock(TransactionWriteRepository.class);
        FinanceRepository finances = mock(FinanceRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        Company company = company();
        when(companies.findAll()).thenReturn(List.of(company));
        when(transactions.findAll(company.ownerId, company.id)).thenReturn(List.of());
        when(finances.findAccounts(company.ownerId, company.id)).thenReturn(List.of());
        when(categories.findAll(company.ownerId, company.id, null)).thenReturn(List.of());
        TransactionDataInitializer initializer = new TransactionDataInitializer(
            transactions,
            writes,
            finances,
            categories,
            companies,
            "demo"
        );

        initializer.initialize();

        verify(writes, never()).insert(any());
        verify(categories).findAll(company.ownerId, company.id, null);
    }

    private Company company() {
        Company company = new Company();
        company.id = 9;
        company.ownerId = 3;
        company.entityType = "company";
        return company;
    }

    private Account account(long id, String name, Long ledgerId) {
        Account account = new Account();
        account.id = id;
        account.name = name;
        account.ledgerId = ledgerId;
        return account;
    }

    private Category category(long id, String name, String type) {
        Category category = new Category();
        category.id = id;
        category.name = name;
        category.type = type;
        return category;
    }
}
