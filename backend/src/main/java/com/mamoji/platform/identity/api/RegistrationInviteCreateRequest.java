package com.mamoji.platform.identity.api;

import com.mamoji.common.Permissions;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Administrator command for issuing a bounded registration invitation. */
public record RegistrationInviteCreateRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @Min(1) @Max(2) Integer role,
    @Min(0) @Max(Permissions.ALL) Integer permissions,
    @Min(1) @Max(30) Integer expiresInDays
) {
    public RegistrationInviteCreateRequest {
        email = email == null ? null : email.trim();
    }
}
