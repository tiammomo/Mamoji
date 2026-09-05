package com.mamoji.operations.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Partial transaction update contract; null fields retain their current values. */
public record TransactionUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @Positive Long companyId,
    @DecimalMin("0.01") @Digits(integer = 14, fraction = 4) BigDecimal amount,
    @Positive Long categoryId,
    @Positive Long accountId,
    LocalDate date,
    @Size(max = 2000) String note
) {
}
