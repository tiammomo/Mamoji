package com.mamoji.operations.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** Transaction deletion preconditions bound from query parameters. */
public record TransactionDeleteRequest(
    @Positive Long companyId,
    @NotNull @PositiveOrZero Long version
) {
}
