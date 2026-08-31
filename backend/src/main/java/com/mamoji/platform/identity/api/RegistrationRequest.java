package com.mamoji.platform.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Public registration command; deployment-specific password policy remains in the application service. */
public record RegistrationRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(max = 256) String password,
    @Size(max = 100) String nickname,
    @Size(max = 64) String avatar,
    @Size(max = 128) String inviteToken
) {
    public RegistrationRequest {
        email = email == null ? null : email.trim();
        inviteToken = inviteToken == null ? null : inviteToken.trim();
    }
}
