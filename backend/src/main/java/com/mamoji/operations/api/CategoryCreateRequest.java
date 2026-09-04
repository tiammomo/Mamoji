package com.mamoji.operations.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CategoryCreateRequest(
    @Positive Long companyId,
    @NotBlank @Size(max = 120) String name,
    @NotBlank @Pattern(regexp = "(?i)\\s*(income|expense)\\s*") String type,
    @Size(max = 32) @Pattern(regexp = "(?s).*\\S.*") String icon,
    @Pattern(regexp = "(?i)#[0-9a-f]{6}") String color
) {
}
