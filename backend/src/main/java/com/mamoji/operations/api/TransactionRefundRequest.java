package com.mamoji.operations.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Public command contract for refunding an expense transaction. */
public record TransactionRefundRequest(
    @Positive Long companyId,
    @NotNull @DecimalMin("0.01") @Digits(integer = 14, fraction = 4) BigDecimal amount,
    LocalDate date,
    @Size(max = 2000) String note
) {
}
