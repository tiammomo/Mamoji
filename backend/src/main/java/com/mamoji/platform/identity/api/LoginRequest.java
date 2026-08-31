package com.mamoji.platform.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Credentials accepted by the public session endpoint. */
public record LoginRequest(
    @NotBlank @Size(max = 320) String email,
    @NotBlank @Size(max = 256) String password
) {
    public LoginRequest {
        email = email == null ? null : email.trim();
    }
}
