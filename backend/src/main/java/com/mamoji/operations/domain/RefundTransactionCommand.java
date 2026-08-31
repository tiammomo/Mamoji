package com.mamoji.operations.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RefundTransactionCommand(
    Long companyId,
    BigDecimal amount,
    LocalDate date,
    String note
) {
}
