package com.mamoji.controller;

import com.mamoji.common.status.BudgetStatus;
import com.mamoji.entity.Budget;
import com.mamoji.entity.Category;
import com.mamoji.entity.Transaction;
import com.mamoji.entity.User;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.TransactionRepository;
import com.mamoji.service.BudgetService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test suite for TransactionControllerTest.
 */

class TransactionControllerTest {

    @Test
    void shouldRejectFutureDateWhenCreatingTransaction() {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        TransactionController controller = new TransactionController(transactionRepository, categoryRepository, budgetRepository, budgetService);

        User user = User.builder().id(7L).familyId(3L).build();
        Category category = Category.builder().id(11L).type(2).familyId(3L).build();
        Mockito.when(categoryRepository.findById(11L)).thenReturn(Optional.of(category));

        Map<String, Object> request = new HashMap<>();
        request.put("type", 2);
        request.put("amount", "99.99");
        request.put("categoryId", 11L);
        request.put("date", LocalDate.now().plusDays(1).toString());

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> controller.createTransaction(user, request));

        Assertions.assertTrue(exception.getMessage().contains("Future transaction dates"));
        Mockito.verify(transactionRepository, Mockito.never()).save(ArgumentMatchers.any(Transaction.class));
    }

    @Test
    void shouldRejectCategoryTypeMismatchWhenCreatingTransaction() {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        TransactionController controller = new TransactionController(transactionRepository, categoryRepository, budgetRepository, budgetService);

        User user = User.builder().id(7L).familyId(3L).build();
        Category category = Category.builder().id(12L).type(2).familyId(3L).build();
        Mockito.when(categoryRepository.findById(12L)).thenReturn(Optional.of(category));

        Map<String, Object> request = new HashMap<>();
        request.put("type", 1);
        request.put("amount", "500");
        request.put("categoryId", 12L);
        request.put("date", LocalDate.now().toString());

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> controller.createTransaction(user, request));

        Assertions.assertTrue(exception.getMessage().contains("category type does not match"));
        Mockito.verify(transactionRepository, Mockito.never()).save(ArgumentMatchers.any(Transaction.class));
    }

    @Test
    void shouldReturnRiskAndSyncBudgetWhenCreatingLargeExpense() {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        TransactionController controller = new TransactionController(transactionRepository, categoryRepository, budgetRepository, budgetService);

        User user = User.builder().id(7L).familyId(3L).build();
        LocalDate date = LocalDate.parse("2026-03-10");

        Category category = Category.builder().id(13L).type(2).familyId(3L).name("Food").icon("meal").build();
        Mockito.when(categoryRepository.findById(13L)).thenReturn(Optional.of(category));

        Budget budget = Budget.builder()
            .id(20L)
            .userId(7L)
            .name("March Food")
            .amount(new BigDecimal("1000"))
            .spent(new BigDecimal("860"))
            .warningThreshold(80)
            .status(BudgetStatus.ACTIVE)
            .build();

        Mockito.when(budgetService.matchActiveBudgetForExpense(7L, 13L, date)).thenReturn(Optional.of(budget));
        Mockito.when(budgetRepository.findByIdAndUserId(20L, 7L)).thenReturn(Optional.of(budget));
        Mockito.when(transactionRepository.sumEffectiveExpenseByUserIdAndDateBetween(7L, date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth())))
            .thenReturn(new BigDecimal("6200.00"));
        Mockito.when(transactionRepository.sumByUserIdAndTypeAndDateBetween(7L, 1, date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth())))
            .thenReturn(new BigDecimal("10000.00"));
        Mockito.when(transactionRepository.save(ArgumentMatchers.any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            saved.setId(99L);
            return saved;
        });

        Map<String, Object> request = new HashMap<>();
        request.put("type", 2);
        request.put("amount", "3500");
        request.put("categoryId", 13L);
        request.put("date", "2026-03-10");
        request.put("remark", "team dinner");

        ResponseEntity<Map<String, Object>> response = controller.createTransaction(user, request);

        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(0, response.getBody().get("code"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        Assertions.assertNotNull(data);
        Assertions.assertEquals(20L, data.get("budgetId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> risk = (Map<String, Object>) data.get("risk");
        Assertions.assertNotNull(risk);
        Assertions.assertEquals("high", risk.get("level"));

        @SuppressWarnings("unchecked")
        List<String> flags = (List<String>) risk.get("flags");
        Assertions.assertTrue(flags.contains("large_expense"));
        Assertions.assertTrue(flags.contains("budget_warning"));

        Mockito.verify(budgetService).syncBudgetSnapshot(20L, 7L);
    }

    @Test
    void shouldSyncBudgetWhenDeletingExpenseTransaction() {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        TransactionController controller = new TransactionController(transactionRepository, categoryRepository, budgetRepository, budgetService);

        User user = User.builder().id(7L).familyId(3L).build();

        Transaction existing = Transaction.builder()
            .id(5L)
            .userId(7L)
            .type(2)
            .amount(new BigDecimal("100"))
            .categoryId(13L)
            .date(LocalDate.parse("2026-03-10"))
            .budgetId(20L)
            .build();

        Budget budget = Budget.builder().id(20L).userId(7L).build();

        Mockito.when(transactionRepository.findById(5L)).thenReturn(Optional.of(existing));
        Mockito.when(budgetRepository.findByIdAndUserId(20L, 7L)).thenReturn(Optional.of(budget));
        Mockito.when(budgetService.matchActiveBudgetForExpense(7L, 13L, LocalDate.parse("2026-03-10"))).thenReturn(Optional.of(budget));

        ResponseEntity<Map<String, Object>> response = controller.deleteTransaction(user, 5L);

        Assertions.assertEquals(200, response.getStatusCode().value());
        Mockito.verify(transactionRepository).delete(existing);
        Mockito.verify(budgetService).syncBudgetSnapshot(20L, 7L);
    }

    @Test
    void shouldRejectRefundDateBeforeOriginalTransactionDate() {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        TransactionController controller = new TransactionController(transactionRepository, categoryRepository, budgetRepository, budgetService);

        User user = User.builder().id(7L).familyId(3L).build();
        Transaction existing = Transaction.builder()
            .id(5L)
            .userId(7L)
            .type(2)
            .amount(new BigDecimal("200"))
            .date(LocalDate.parse("2026-03-10"))
            .build();

        Mockito.when(transactionRepository.findById(5L)).thenReturn(Optional.of(existing));

        Map<String, Object> request = new HashMap<>();
        request.put("amount", "50");
        request.put("date", "2026-03-01");

        RuntimeException exception = Assertions.assertThrows(
            RuntimeException.class,
            () -> controller.refundTransaction(user, 5L, request)
        );

        Assertions.assertTrue(exception.getMessage().contains("Refund date cannot be before original transaction date"));
        Mockito.verify(transactionRepository, Mockito.never()).save(ArgumentMatchers.any(Transaction.class));
    }

    @Test
    void shouldRejectDeleteForRefundTransaction() {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        TransactionController controller = new TransactionController(transactionRepository, categoryRepository, budgetRepository, budgetService);

        User user = User.builder().id(7L).familyId(3L).build();
        Transaction refund = Transaction.builder()
            .id(6L)
            .userId(7L)
            .type(3)
            .amount(new BigDecimal("120"))
            .date(LocalDate.parse("2026-03-10"))
            .originalTransactionId(5L)
            .build();
        Mockito.when(transactionRepository.findById(6L)).thenReturn(Optional.of(refund));

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> controller.deleteTransaction(user, 6L));

        Assertions.assertTrue(exception.getMessage().contains("cannot be deleted"));
        Mockito.verify(transactionRepository, Mockito.never()).delete(ArgumentMatchers.any(Transaction.class));
    }

    @Test
    void shouldRejectDeleteForExpenseWithRefundHistory() {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        TransactionController controller = new TransactionController(transactionRepository, categoryRepository, budgetRepository, budgetService);

        User user = User.builder().id(7L).familyId(3L).build();
        Transaction expense = Transaction.builder()
            .id(5L)
            .userId(7L)
            .type(2)
            .amount(new BigDecimal("300"))
            .refundedAmount(new BigDecimal("20"))
            .date(LocalDate.parse("2026-03-10"))
            .build();

        Mockito.when(transactionRepository.findById(5L)).thenReturn(Optional.of(expense));

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> controller.deleteTransaction(user, 5L));

        Assertions.assertTrue(exception.getMessage().contains("refund history"));
        Mockito.verify(transactionRepository, Mockito.never()).delete(ArgumentMatchers.any(Transaction.class));
    }

    @Test
    void shouldExposeMoreRiskFlagsForExpensePressure() {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        TransactionController controller = new TransactionController(transactionRepository, categoryRepository, budgetRepository, budgetService);

        User user = User.builder().id(7L).familyId(3L).build();
        LocalDate date = LocalDate.now().minusDays(1);
        Category category = Category.builder().id(13L).type(2).familyId(3L).name("Food").icon("meal").build();
        Mockito.when(categoryRepository.findById(13L)).thenReturn(Optional.of(category));
        Mockito.when(budgetService.matchActiveBudgetForExpense(7L, 13L, date)).thenReturn(Optional.empty());

        Mockito.when(transactionRepository.sumEffectiveExpenseByUserIdAndDateBetween(7L, date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth())))
            .thenReturn(new BigDecimal("8600.00"));
        Mockito.when(transactionRepository.sumByUserIdAndTypeAndDateBetween(7L, 1, date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth())))
            .thenReturn(BigDecimal.ZERO);
        Mockito.when(transactionRepository.countByUserIdAndTypeAndDate(7L, 2, date))
            .thenReturn(15L);
        Mockito.when(transactionRepository.countDuplicateTransactions(7L, 2, 13L, new BigDecimal("3800.00"), date, 88L))
            .thenReturn(2L);
        Mockito.when(transactionRepository.sumByUserIdAndTypeAndCategoryIdAndDateBetween(
                7L,
                2,
                13L,
                date.withDayOfMonth(1),
                date.withDayOfMonth(date.lengthOfMonth())
            ))
            .thenReturn(new BigDecimal("5200.00"));
        Mockito.when(transactionRepository.sumByUserIdAndTypeAndCategoryIdAndDateBetween(
                7L,
                2,
                13L,
                date.minusMonths(1).withDayOfMonth(1),
                date.minusMonths(1).withDayOfMonth(date.minusMonths(1).lengthOfMonth())
            ))
            .thenReturn(new BigDecimal("1800.00"));

        Mockito.when(transactionRepository.save(ArgumentMatchers.any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            saved.setId(88L);
            return saved;
        });

        Map<String, Object> request = new HashMap<>();
        request.put("type", 2);
        request.put("amount", "3800");
        request.put("categoryId", 13L);
        request.put("date", date.toString());
        request.put("remark", "bulk groceries");

        ResponseEntity<Map<String, Object>> response = controller.createTransaction(user, request);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> risk = (Map<String, Object>) data.get("risk");
        @SuppressWarnings("unchecked")
        List<String> flags = (List<String>) risk.get("flags");

        Assertions.assertEquals("critical", risk.get("level"));
        Assertions.assertTrue(flags.contains("large_expense"));
        Assertions.assertTrue(flags.contains("expense_without_income"));
        Assertions.assertTrue(flags.contains("high_frequency_expense"));
        Assertions.assertTrue(flags.contains("possible_duplicate_expense"));
        Assertions.assertTrue(flags.contains("category_expense_spike"));
        Assertions.assertEquals(15L, risk.get("dailyExpenseCount"));
        Assertions.assertEquals(2L, risk.get("sameDayDuplicateCount"));
    }
}



