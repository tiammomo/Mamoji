package com.mamoji.operations.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateTransactionCommand(
    long expectedVersion,
    Long companyId,
    BigDecimal amount,
    Long categoryId,
    Long accountId,
    LocalDate date,
    String note
) {
}
