package com.mamoji.finance.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LedgerCreateRequest(
    @Positive Long companyId,
    @NotBlank @Size(max = 120) String name,
    @Size(max = 500) String description,
    @Pattern(regexp = "(?i)[a-z]{3}") String currency
) {
}
