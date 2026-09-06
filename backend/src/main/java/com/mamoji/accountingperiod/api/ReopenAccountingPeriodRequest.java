package com.mamoji.accountingperiod.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ReopenAccountingPeriodRequest(
    @Positive Long companyId,
    @NotNull @PositiveOrZero Long version,
    @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String throughMonth,
    @NotBlank @Size(min = 5, max = 500) String reason
) {
}
