package com.mamoji.operations.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Public command contract for recording an income or expense transaction. */
public record TransactionCreateRequest(
    Long companyId,
    @Min(1) @Max(2) Integer type,
    @NotNull @DecimalMin("0.01") @Digits(integer = 14, fraction = 2) BigDecimal amount,
    @NotNull @Positive Long categoryId,
    @NotNull @Positive Long accountId,
    LocalDate date,
    @Size(max = 2000) String note,
    @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]*") String idempotencyKey
) {
}
