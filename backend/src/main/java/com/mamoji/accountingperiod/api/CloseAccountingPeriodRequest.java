package com.mamoji.accountingperiod.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CloseAccountingPeriodRequest(
    @Positive Long companyId,
    @NotNull @PositiveOrZero Long version,
    @NotNull @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String throughMonth
) {
}
