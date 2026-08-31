package com.mamoji.operations.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Validated transaction filters passed from the application layer to persistence. */
public record TransactionSearchCriteria(
    Integer type,
    Long categoryId,
    Long accountId,
    LocalDate startDate,
    LocalDate endDate,
    String keyword,
    BigDecimal minAmount,
    BigDecimal maxAmount
) {
    public TransactionSearchCriteria {
        keyword = keyword == null ? "" : keyword.trim();
    }
}
