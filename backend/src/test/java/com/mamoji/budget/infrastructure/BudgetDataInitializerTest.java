package com.mamoji.budget.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.budget.application.BudgetRepository;
import com.mamoji.budget.domain.Budget;
import com.mamoji.budget.domain.BudgetPolicy;
import com.mamoji.domain.Models.Company;
import com.mamoji.repository.EnterpriseStore;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BudgetDataInitializerTest {
    @Test
    void bootstrapModeDoesNotCreateDemoBudget() {
        BudgetRepository budgets = mock(BudgetRepository.class);
        BudgetPolicy policy = mock(BudgetPolicy.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        BudgetDataInitializer initializer = new BudgetDataInitializer(
            budgets,
            policy,
            enterpriseStore,
            "bootstrap"
        );

        initializer.initialize();

        verifyNoInteractions(budgets, policy, enterpriseStore);
    }

    @Test
    void demoModeCreatesAndProjectsCompanyBudgetOnlyOnce() {
        BudgetRepository budgets = mock(BudgetRepository.class);
        BudgetPolicy policy = new BudgetPolicy();
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        Company company = company();
        Budget projected = new Budget();
        projected.id = 42;
        projected.version = 0;
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(company));
        when(budgets.findByCompany(company.id))
            .thenReturn(List.of())
            .thenReturn(List.of(projected));
        when(budgets.findById(company.id, 0)).thenReturn(Optional.of(projected));
        BudgetDataInitializer initializer = new BudgetDataInitializer(
            budgets,
            policy,
            enterpriseStore,
            "demo"
        );

        initializer.initialize();
        initializer.initialize();

        ArgumentCaptor<Budget> inserted = ArgumentCaptor.forClass(Budget.class);
        verify(budgets, times(1)).insert(inserted.capture());
        Budget budget = inserted.getValue();
        assertEquals(company.id, budget.companyId);
        assertEquals(company.ownerId, budget.userId);
        assertEquals("本月经营预算", budget.name);
        assertEquals("6000.00", budget.amount.toPlainString());
        assertEquals(LocalDate.now().withDayOfMonth(1).toString(), budget.startDate);
        assertEquals(LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()).toString(), budget.endDate);
        assertEquals("low", budget.riskLevel);
        assertNotNull(budget.createdAt);
        verify(budgets).persistProjection(projected);
    }

    @Test
    void demoModeLeavesExistingBudgetsUntouched() {
        BudgetRepository budgets = mock(BudgetRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        Company company = company();
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(company));
        when(budgets.findByCompany(company.id)).thenReturn(List.of(new Budget()));
        BudgetDataInitializer initializer = new BudgetDataInitializer(
            budgets,
            new BudgetPolicy(),
            enterpriseStore,
            "demo"
        );

        initializer.initialize();

        verify(budgets, never()).insert(any());
        verify(budgets, never()).persistProjection(any());
    }

    private Company company() {
        Company company = new Company();
        company.id = 9;
        company.ownerId = 3;
        company.entityType = "company";
        return company;
    }
}
