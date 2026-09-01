package com.mamoji.platform.identity.invitation.application;

import com.mamoji.platform.identity.invitation.domain.RegistrationInvitation;

/** Newly issued invitation plus the raw credential that is disclosed exactly once. */
public record IssuedRegistrationInvitation(
    RegistrationInvitation invitation,
    String rawToken
) {
    public IssuedRegistrationInvitation {
        if (invitation == null || rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Issued invitation and raw token are required");
        }
    }
}
