package com.mamoji.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mamoji.bootstrap.DemoEnterpriseDataInitializer;
import com.mamoji.bootstrap.EnterpriseDataInitializer;
import com.mamoji.bootstrap.ProductionBootstrapCommand;
import com.mamoji.budget.application.BudgetRepository;
import com.mamoji.budget.domain.BudgetPolicy;
import com.mamoji.budget.infrastructure.BudgetDataInitializer;
import com.mamoji.evidence.infrastructure.DemoReceiptVoucherDataInitializer;
import com.mamoji.evidence.infrastructure.ReceiptVoucherRepository;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.finance.infrastructure.AccountDataInitializer;
import com.mamoji.finance.infrastructure.DemoLedgerDataInitializer;
import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.operations.application.TransactionQueryRepository;
import com.mamoji.operations.application.TransactionWriteRepository;
import com.mamoji.operations.infrastructure.DemoCategoryDataInitializer;
import com.mamoji.operations.infrastructure.TransactionDataInitializer;
import com.mamoji.people.application.DepartmentRepository;
import com.mamoji.people.application.EmployeeRepository;
import com.mamoji.people.application.EmploymentEventRepository;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.platform.identity.account.application.UserDirectory;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyRepository;
import com.mamoji.platform.tenant.EntityTransferRepository;
import com.mamoji.recurring.application.RecurringItemRepository;
import com.mamoji.recurring.infrastructure.RecurringItemDataInitializer;
import com.mamoji.service.CompanyProvisioningService;
import com.mamoji.tax.application.TaxItemRepository;
import com.mamoji.tax.domain.TaxItemPolicy;
import com.mamoji.tax.infrastructure.TaxItemDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class BootstrapModeIsolationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(
            EnterpriseDataInitializer.class,
            DemoEnterpriseDataInitializer.class,
            DemoLedgerDataInitializer.class,
            DemoCategoryDataInitializer.class,
            AccountDataInitializer.class,
            TransactionDataInitializer.class,
            BudgetDataInitializer.class,
            RecurringItemDataInitializer.class,
            TaxItemDataInitializer.class,
            DemoReceiptVoucherDataInitializer.class
        )
        .withBean("initialAdminDataInitializer", Object.class, Object::new)
        .withBean("productionReadinessGuard", Object.class, Object::new)
        .withBean(ProductionBootstrapCommand.class, () -> mock(ProductionBootstrapCommand.class))
        .withBean(CompanyProvisioningService.class, () -> mock(CompanyProvisioningService.class))
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(UserDirectory.class, () -> mock(UserDirectory.class))
        .withBean(AuditTrailService.class, () -> mock(AuditTrailService.class))
        .withBean(DepartmentRepository.class, () -> mock(DepartmentRepository.class))
        .withBean(EmployeeRepository.class, () -> mock(EmployeeRepository.class))
        .withBean(EmploymentEventRepository.class, () -> mock(EmploymentEventRepository.class))
        .withBean(CompanyRepository.class, () -> mock(CompanyRepository.class))
        .withBean(CompanyMembershipRepository.class, () -> mock(CompanyMembershipRepository.class))
        .withBean(EntityTransferRepository.class, () -> mock(EntityTransferRepository.class))
        .withBean(FinanceRepository.class, () -> mock(FinanceRepository.class))
        .withBean(CategoryRepository.class, () -> mock(CategoryRepository.class))
        .withBean(TransactionQueryRepository.class, () -> mock(TransactionQueryRepository.class))
        .withBean(TransactionWriteRepository.class, () -> mock(TransactionWriteRepository.class))
        .withBean(BudgetRepository.class, () -> mock(BudgetRepository.class))
        .withBean(BudgetPolicy.class, BudgetPolicy::new)
        .withBean(RecurringItemRepository.class, () -> mock(RecurringItemRepository.class))
        .withBean(TaxItemRepository.class, () -> mock(TaxItemRepository.class))
        .withBean(TaxItemPolicy.class, TaxItemPolicy::new)
        .withBean(PlatformTransactionManager.class, this::transactionManager)
        .withBean(ReceiptVoucherRepository.class, () -> mock(ReceiptVoucherRepository.class));

    @Test
    void productionBootstrapContextDoesNotRegisterDemoInitializers() {
        context.withPropertyValues("mamoji.bootstrap.mode=bootstrap").run(application -> {
            assertInstanceOf(EnterpriseDataInitializer.class, application.getBean("enterpriseDataInitializer"));
            assertFalse(application.containsBean("ledgerDataInitializer"));
            assertFalse(application.containsBean("categoryDataInitializer"));
            assertFalse(application.containsBean("receiptVoucherDataInitializer"));
            assertFalse(application.containsBean("demoEnterpriseDataInitializer"));
            assertFalse(application.containsBean("demoCategoryDataInitializer"));
            assertFalse(application.containsBean("accountDataInitializer"));
            assertFalse(application.containsBean("transactionDataInitializer"));
            assertFalse(application.containsBean("budgetDataInitializer"));
            assertFalse(application.containsBean("recurringItemDataInitializer"));
            assertFalse(application.containsBean("taxItemDataInitializer"));
            assertFalse(application.containsBean("demoReceiptVoucherDataInitializer"));
        });
    }

    @Test
    void demoContextRegistersOnlyDemoEnterpriseAndCategoryVariants() {
        context.withPropertyValues("mamoji.bootstrap.mode=demo").run(application -> {
            assertInstanceOf(DemoEnterpriseDataInitializer.class, application.getBean("enterpriseDataInitializer"));
            assertInstanceOf(DemoLedgerDataInitializer.class, application.getBean("ledgerDataInitializer"));
            assertInstanceOf(DemoCategoryDataInitializer.class, application.getBean("categoryDataInitializer"));
            assertTrue(application.containsBean("accountDataInitializer"));
            assertTrue(application.containsBean("transactionDataInitializer"));
            assertTrue(application.containsBean("budgetDataInitializer"));
            assertTrue(application.containsBean("recurringItemDataInitializer"));
            assertTrue(application.containsBean("taxItemDataInitializer"));
            assertTrue(application.containsBean("demoReceiptVoucherDataInitializer"));
        });
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return manager;
    }
}
