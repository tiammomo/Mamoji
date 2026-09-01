package com.mamoji.budget.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mamoji.budget.domain.Budget;
import com.mamoji.budget.domain.BudgetPolicy;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcBudgetRepositoryTest {
    @Test
    void rejectsAStaleProjectionWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
            .thenReturn(4L);
        JdbcBudgetRepository repository = new JdbcBudgetRepository(jdbc, new BudgetPolicy());
        Budget budget = budget();

        OptimisticLockingFailureException exception = assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.persistProjection(budget)
        );

        assertEquals("Budget was changed by another request: 42", exception.getMessage());
        assertEquals(3, budget.version);
    }

    @Test
    void keepsVersionWhenTheStoredProjectionIsAlreadyCurrent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
            .thenReturn(3L);
        JdbcBudgetRepository repository = new JdbcBudgetRepository(jdbc, new BudgetPolicy());
        Budget budget = budget();

        repository.persistProjection(budget);

        assertEquals(3, budget.version);
    }

    @Test
    void advancesVersionAfterAStoredProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcBudgetRepository repository = new JdbcBudgetRepository(jdbc, new BudgetPolicy());
        Budget budget = budget();

        repository.persistProjection(budget);

        assertEquals(4, budget.version);
    }

    private Budget budget() {
        Budget budget = new Budget();
        budget.id = 42;
        budget.version = 3;
        budget.spent = new BigDecimal("250.00");
        budget.remainingAmount = new BigDecimal("750.00");
        budget.riskLevel = "low";
        budget.riskMessage = "预算健康";
        budget.status = 1;
        budget.updatedAt = OffsetDateTime.now().toString();
        return budget;
    }
}
