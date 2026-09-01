package com.mamoji.platform.identity.api;

import com.mamoji.platform.identity.invitation.domain.RegistrationInvitation;
import java.time.OffsetDateTime;

/** Invitation projection; token is present only in the response that creates it. */
public record RegistrationInviteResponse(
    long id,
    String token,
    String email,
    int role,
    int permissions,
    OffsetDateTime expiresAt,
    OffsetDateTime acceptedAt,
    Long acceptedUserId,
    Long invitedByUserId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static RegistrationInviteResponse summary(RegistrationInvitation invitation) {
        return from(invitation, null);
    }

    public static RegistrationInviteResponse issued(RegistrationInvitation invitation, String rawToken) {
        return from(invitation, rawToken);
    }

    private static RegistrationInviteResponse from(RegistrationInvitation invitation, String token) {
        return new RegistrationInviteResponse(
            invitation.id(),
            token,
            invitation.email(),
            invitation.role(),
            invitation.permissions(),
            invitation.expiresAt(),
            invitation.acceptedAt(),
            invitation.acceptedUserId(),
            invitation.invitedByUserId(),
            invitation.createdAt(),
            invitation.updatedAt()
        );
    }
}
