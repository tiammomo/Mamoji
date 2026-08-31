package com.mamoji.budget.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Values used to find and reserve capacity from the most specific active budget. */
public record BudgetReservationCommand(
    long companyId,
    long userId,
    Long ledgerId,
    long categoryId,
    LocalDate transactionDate,
    BigDecimal amount,
    String referenceKey,
    Long replacedTransactionId
) {
}
