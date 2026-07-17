package com.mamoji.budget.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record BudgetCreateRequest(
    Long companyId,
    @NotBlank @Size(max = 64) String name,
    @NotNull @DecimalMin("0.01") @Digits(integer = 14, fraction = 2) BigDecimal amount,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @Min(0) @Max(100) Integer warningThreshold,
    Long categoryId
) {
}
