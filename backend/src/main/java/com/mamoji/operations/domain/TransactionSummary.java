package com.mamoji.operations.domain;

import java.math.BigDecimal;

/** Read projection for the transaction overview cards and review counters. */
public record TransactionSummary(
    BigDecimal income,
    BigDecimal expense,
    BigDecimal refund,
    BigDecimal pendingCollection,
    BigDecimal customerRefund,
    BigDecimal severance,
    BigDecimal netCollectedIncome,
    BigDecimal net,
    long rows,
    long largeCount,
    long reviewCount
) {
    public static TransactionSummary fromTotals(
        BigDecimal income,
        BigDecimal expense,
        BigDecimal refund,
        BigDecimal pendingCollection,
        BigDecimal customerRefund,
        BigDecimal severance,
        long rows,
        long largeCount,
        long reviewCount
    ) {
        return new TransactionSummary(
            income,
            expense,
            refund,
            pendingCollection,
            customerRefund,
            severance,
            income.subtract(customerRefund),
            income.add(refund).subtract(expense),
            rows,
            largeCount,
            reviewCount
        );
    }
}
