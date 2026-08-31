package com.mamoji.platform.identity.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Patch-style profile command; null fields preserve their current values. */
public record ProfileUpdateRequest(
    @Size(min = 1, max = 100)
    @Pattern(regexp = ".*\\S.*", message = "must contain a non-whitespace character")
    String nickname,
    @Size(max = 64) String avatar
) {
}
