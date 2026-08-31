package com.mamoji.operations.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Validated values required by the transaction write use case. */
public record CreateTransactionCommand(
    Long companyId,
    int type,
    BigDecimal amount,
    long categoryId,
    long accountId,
    LocalDate date,
    String note,
    String idempotencyKey
) {
}
