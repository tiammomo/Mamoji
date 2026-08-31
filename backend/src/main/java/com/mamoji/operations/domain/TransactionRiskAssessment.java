package com.mamoji.operations.domain;

import java.math.BigDecimal;
import java.util.List;

/** Immutable transaction risk projection exposed by the operations module. */
public record TransactionRiskAssessment(
    String level,
    List<String> flags,
    String message,
    BigDecimal monthlyIncome,
    BigDecimal monthlyExpense,
    double expenseIncomeRatio,
    long dailyExpenseCount,
    long duplicateCount,
    BigDecimal categoryCurrentMonth,
    BigDecimal categoryLastMonth
) {
    public TransactionRiskAssessment {
        flags = List.copyOf(flags);
    }
}
