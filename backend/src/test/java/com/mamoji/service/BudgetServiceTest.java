package com.mamoji.service;

import com.mamoji.dto.BudgetDTO;
import com.mamoji.entity.Budget;
import com.mamoji.repository.BudgetRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock
    private BudgetRepository budgetRepository;

    @InjectMocks
    private BudgetService budgetService;

    @Test
    void shouldRejectNegativeAmountWhenCreatingBudget() {
        BudgetDTO dto = new BudgetDTO();
        dto.setName("invalid");
        dto.setAmount(BigDecimal.valueOf(-1));
        dto.setStartDate(LocalDate.parse("2026-03-01"));
        dto.setEndDate(LocalDate.parse("2026-03-31"));

        RuntimeException exception = Assertions.assertThrows(
            RuntimeException.class,
            () -> budgetService.createBudget(dto, 7L)
        );

        Assertions.assertNotNull(exception.getMessage());
        Assertions.assertFalse(exception.getMessage().isBlank());
        Mockito.verifyNoInteractions(budgetRepository);
    }

    @Test
    void shouldApplyDefaultsWhenCreatingBudget() {
        BudgetDTO dto = new BudgetDTO();
        dto.setName("food");
        dto.setAmount(BigDecimal.valueOf(1200));
        dto.setStartDate(LocalDate.parse("2026-03-01"));
        dto.setEndDate(LocalDate.parse("2026-03-31"));

        Mockito.when(budgetRepository.save(Mockito.any(Budget.class)))
            .thenAnswer(invocation -> {
                Budget budget = invocation.getArgument(0, Budget.class);
                budget.setId(77L);
                return budget;
            });

        BudgetDTO created = budgetService.createBudget(dto, 7L);

        ArgumentCaptor<Budget> captor = ArgumentCaptor.forClass(Budget.class);
        Mockito.verify(budgetRepository).save(captor.capture());
        Budget saved = captor.getValue();
        Assertions.assertEquals(BigDecimal.ZERO, saved.getSpent());
        Assertions.assertEquals(85, saved.getWarningThreshold());
        Assertions.assertEquals(1, saved.getStatus());
        Assertions.assertEquals(7L, saved.getUserId());
        Assertions.assertEquals(77L, created.getId());
    }

    @Test
    void shouldRejectInvalidWarningThresholdOnUpdate() {
        Budget existing = Budget.builder()
            .id(9L)
            .name("month")
            .amount(BigDecimal.valueOf(100))
            .startDate(LocalDate.parse("2026-03-01"))
            .endDate(LocalDate.parse("2026-03-31"))
            .warningThreshold(80)
            .spent(BigDecimal.TEN)
            .userId(7L)
            .status(1)
            .build();
        Mockito.when(budgetRepository.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(existing));

        BudgetDTO patch = new BudgetDTO();
        patch.setWarningThreshold(120);

        RuntimeException exception = Assertions.assertThrows(
            RuntimeException.class,
            () -> budgetService.updateBudget(9L, patch, 7L)
        );

        Assertions.assertTrue(exception.getMessage().contains("0-100"));
        Mockito.verify(budgetRepository, Mockito.never()).save(Mockito.any(Budget.class));
    }

    @Test
    void shouldMarkBudgetAsOverrunWhenSpentExceedsAmount() {
        Budget budget = Budget.builder()
            .id(9L)
            .name("month")
            .amount(BigDecimal.valueOf(100))
            .startDate(LocalDate.now().minusDays(1))
            .endDate(LocalDate.now().plusDays(1))
            .warningThreshold(80)
            .spent(BigDecimal.valueOf(90))
            .userId(7L)
            .status(1)
            .build();
        Mockito.when(budgetRepository.findById(9L)).thenReturn(Optional.of(budget));
        Mockito.when(budgetRepository.save(Mockito.any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0, Budget.class));

        budgetService.updateBudgetSpent(9L, BigDecimal.valueOf(20));

        ArgumentCaptor<Budget> captor = ArgumentCaptor.forClass(Budget.class);
        Mockito.verify(budgetRepository).save(captor.capture());
        Budget saved = captor.getValue();
        Assertions.assertEquals(BigDecimal.valueOf(110), saved.getSpent());
        Assertions.assertEquals(3, saved.getStatus());
    }
}
