package com.mamoji.recurring.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.domain.Models.Company;
import com.mamoji.recurring.application.RecurringItemRepository;
import com.mamoji.recurring.domain.RecurringItem;
import com.mamoji.repository.EnterpriseStore;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecurringItemDataInitializerTest {
    @Test
    void bootstrapModeDoesNotCreateDemoRules() {
        RecurringItemRepository recurringItems = mock(RecurringItemRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        RecurringItemDataInitializer initializer = new RecurringItemDataInitializer(
            recurringItems,
            enterpriseStore,
            "bootstrap"
        );

        initializer.initialize();

        verifyNoInteractions(recurringItems, enterpriseStore);
    }

    @Test
    void demoModeCreatesCompanyScopedRuleOnlyOnce() {
        RecurringItemRepository recurringItems = mock(RecurringItemRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        Company company = new Company();
        company.id = 9;
        company.ownerId = 3;
        company.entityType = "company";
        RecurringItem existing = new RecurringItem();
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(company));
        when(recurringItems.findByOwnerAndCompany(3, 9))
            .thenReturn(List.of())
            .thenReturn(List.of(existing));
        RecurringItemDataInitializer initializer = new RecurringItemDataInitializer(
            recurringItems,
            enterpriseStore,
            "demo"
        );

        initializer.initialize();
        initializer.initialize();

        ArgumentCaptor<RecurringItem> inserted = ArgumentCaptor.forClass(RecurringItem.class);
        verify(recurringItems, times(1)).insert(inserted.capture());
        RecurringItem item = inserted.getValue();
        assertNotNull(item.id);
        assertEquals(3, item.userId);
        assertEquals(9, item.companyId);
        assertEquals("办公室租金", item.name);
        assertEquals("3200.00", item.amount.toPlainString());
        assertEquals("monthly", item.frequency);
        assertEquals(5, item.dayOfMonth);
        assertEquals(LocalDate.parse(item.startDate).plusMonths(1).withDayOfMonth(5), LocalDate.parse(item.nextExecution));
    }

    @Test
    void demoModeLeavesExistingRulesUntouched() {
        RecurringItemRepository recurringItems = mock(RecurringItemRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        Company company = new Company();
        company.id = 9;
        company.ownerId = 3;
        company.entityType = "company";
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(company));
        when(recurringItems.findByOwnerAndCompany(3, 9)).thenReturn(List.of(new RecurringItem()));
        RecurringItemDataInitializer initializer = new RecurringItemDataInitializer(
            recurringItems,
            enterpriseStore,
            "demo"
        );

        initializer.initialize();

        verify(recurringItems, never()).insert(any());
    }
}
