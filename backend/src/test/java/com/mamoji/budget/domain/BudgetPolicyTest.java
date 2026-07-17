package com.mamoji.budget.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.domain.Models.Budget;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BudgetPolicyTest {
    private final BudgetPolicy policy = new BudgetPolicy();

    @Test
    void marksActiveBudgetAsCriticalWhenSpentReachesLimit() {
        Budget budget = budget("1000", "1000", 85, 1);

        policy.apply(budget);

        assertEquals(3, budget.status);
        assertEquals("critical", budget.riskLevel);
        assertTrue(budget.warningReached);
        assertEquals(0, budget.remainingAmount.compareTo(BigDecimal.ZERO));
    }

    @Test
    void preservesDisabledLifecycleWhileStillComputingRisk() {
        Budget budget = budget("1000", "1200", 85, 0);

        policy.apply(budget);

        assertEquals(0, budget.status);
        assertEquals("critical", budget.riskLevel);
    }

    @Test
    void normalBudgetHasExactDerivedValues() {
        Budget budget = budget("1000", "400", 85, 1);

        policy.apply(budget);

        assertEquals("low", budget.riskLevel);
        assertFalse(budget.warningReached);
        assertEquals(0.4d, budget.usageRate);
        assertEquals(0, budget.remainingAmount.compareTo(new BigDecimal("600")));
    }

    private Budget budget(String amount, String spent, int threshold, int status) {
        Budget budget = new Budget();
        budget.amount = new BigDecimal(amount);
        budget.spent = new BigDecimal(spent);
        budget.warningThreshold = threshold;
        budget.status = status;
        return budget;
    }
}
