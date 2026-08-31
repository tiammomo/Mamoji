package com.mamoji.operations.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateTransactionCommand(
    Long companyId,
    BigDecimal amount,
    Long categoryId,
    Long accountId,
    LocalDate date,
    String note
) {
}
