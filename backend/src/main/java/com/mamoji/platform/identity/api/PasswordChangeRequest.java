package com.mamoji.platform.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Authenticated password rotation command. */
public record PasswordChangeRequest(
    @NotBlank @Size(max = 256) String oldPassword,
    @NotBlank @Size(max = 256) String newPassword
) {
}
