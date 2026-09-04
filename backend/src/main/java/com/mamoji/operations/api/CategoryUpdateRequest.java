package com.mamoji.operations.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CategoryUpdateRequest(
    @Positive Long companyId,
    @Size(max = 120) @Pattern(regexp = "(?s).*\\S.*") String name,
    @Pattern(regexp = "(?i)\\s*(income|expense)\\s*") String type,
    @Size(max = 32) @Pattern(regexp = "(?s).*\\S.*") String icon,
    @Pattern(regexp = "(?i)#[0-9a-f]{6}") String color
) {
}
