package com.mamoji.finance.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record LedgerMemberCreateRequest(
    @NotNull @Positive Long userId,
    @Pattern(regexp = "admin|editor|viewer") String role
) {
}
