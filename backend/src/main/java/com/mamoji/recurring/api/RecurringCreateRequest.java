package com.mamoji.recurring.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringCreateRequest(
    @Positive Long companyId,
    @NotBlank @Size(max = 160) String name,
    @NotNull @Min(1) @Max(2) Integer type,
    @NotNull @DecimalMin("0.01") @Digits(integer = 14, fraction = 2) BigDecimal amount,
    @NotBlank @Pattern(regexp = "daily|weekly|monthly|yearly") String frequency,
    @NotNull @Min(1) @Max(3650) Integer interval,
    @Min(1) @Max(7) Integer dayOfWeek,
    @Min(1) @Max(31) Integer dayOfMonth,
    @Min(1) @Max(12) Integer monthOfYear,
    @NotNull LocalDate startDate,
    LocalDate endDate,
    @Size(max = 1000) String note
) {
}
