package com.mamoji.agent.tool.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mamoji.entity.Budget;
import com.mamoji.entity.Category;
import com.mamoji.entity.Transaction;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.TransactionRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

class FinanceToolsTest {

    @Test
    void shouldUseRepositoryFiltersForTransactionQuery() throws Exception {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);

        Transaction tx = new Transaction();
        tx.setUserId(11L);
        tx.setType(2);
        tx.setCategoryId(3L);
        tx.setAmount(new BigDecimal("88.50"));
        tx.setDate(LocalDate.of(2026, 3, 1));
        tx.setRemark("groceries");

        Mockito.when(transactionRepository.findByUserIdAndDateBetweenWithFilters(
            Mockito.eq(11L), Mockito.any(), Mockito.any(), Mockito.eq(3L), Mockito.eq(2)
        )).thenReturn(List.of(tx));
        Mockito.when(categoryRepository.findById(3L)).thenReturn(Optional.of(Category.builder().id(3L).name("Food").build()));

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        FinanceTools tools = new FinanceTools(objectMapper, transactionRepository, categoryRepository, budgetRepository);
        String result = tools.queryTransactions(11L, null, null, 3L, 2);

        JsonNode root = new ObjectMapper().readTree(result);
        Assertions.assertEquals(1, root.get("count").asInt());
        Assertions.assertEquals("expense", root.get("transactions").get(0).get("type").asText());
        Assertions.assertEquals("Food", root.get("transactions").get(0).get("category").asText());

        Mockito.verify(transactionRepository).findByUserIdAndDateBetweenWithFilters(
            Mockito.eq(11L), Mockito.any(), Mockito.any(), Mockito.eq(3L), Mockito.eq(2)
        );
    }

    @Test
    void shouldUsePersistedBudgetAmountInsteadOfHardcodedValue() throws Exception {
        TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
        CategoryRepository categoryRepository = Mockito.mock(CategoryRepository.class);
        BudgetRepository budgetRepository = Mockito.mock(BudgetRepository.class);

        Budget budget = Budget.builder()
            .id(99L)
            .userId(11L)
            .name("March budget")
            .amount(new BigDecimal("1200.00"))
            .warningThreshold(70)
            .startDate(LocalDate.of(2026, 3, 1))
            .endDate(LocalDate.of(2026, 3, 31))
            .build();

        Mockito.when(budgetRepository.findByIdAndUserId(99L, 11L)).thenReturn(Optional.of(budget));
        Mockito.when(transactionRepository.sumByUserIdAndTypeAndDateBetween(11L, 2, budget.getStartDate(), budget.getEndDate()))
            .thenReturn(new BigDecimal("300.00"));

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        FinanceTools tools = new FinanceTools(objectMapper, transactionRepository, categoryRepository, budgetRepository);
        String result = tools.queryBudget(11L, 99L);

        JsonNode root = objectMapper.readTree(result);
        Assertions.assertEquals(0, root.get("budgetAmount").decimalValue().compareTo(new BigDecimal("1200.00")));
        Assertions.assertEquals(0, root.get("spent").decimalValue().compareTo(new BigDecimal("300.00")));
        Assertions.assertEquals(0, root.get("remaining").decimalValue().compareTo(new BigDecimal("900.00")));
        Assertions.assertEquals("normal", root.get("status").asText());
    }
}
