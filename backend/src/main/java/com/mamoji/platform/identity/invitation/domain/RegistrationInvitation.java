package com.mamoji.platform.identity.invitation.domain;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import java.time.OffsetDateTime;
import java.util.Locale;

/** Immutable registration invitation; raw credentials never enter this domain object. */
public record RegistrationInvitation(
    long id,
    InvitationTokenDigest tokenDigest,
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
    public RegistrationInvitation {
        if (id < 0 || tokenDigest == null || email == null || expiresAt == null
            || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Invitation identity and timestamps are required");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!email.equals(normalizedEmail) || email.length() > 320 || email.indexOf('@') <= 0) {
            throw new IllegalArgumentException("Invitation email must be normalized");
        }
        if (role != Roles.ADMIN && role != Roles.USER) {
            throw new IllegalArgumentException("Invitation role is invalid");
        }
        if (permissions <= 0 || permissions > Permissions.ALL) {
            throw new IllegalArgumentException("Invitation permissions are invalid");
        }
        if (!expiresAt.isAfter(createdAt) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Invitation timestamps are invalid");
        }
        if (acceptedUserId != null && (acceptedUserId <= 0 || acceptedAt == null)) {
            throw new IllegalArgumentException("Accepted invitation identity is invalid");
        }
        if (invitedByUserId != null && invitedByUserId <= 0) {
            throw new IllegalArgumentException("Invitation issuer is invalid");
        }
        if (id == 0 && invitedByUserId == null) {
            throw new IllegalArgumentException("New invitations require an issuer");
        }
    }

    public static RegistrationInvitation pending(
        InvitationTokenDigest tokenDigest,
        String email,
        int role,
        int permissions,
        OffsetDateTime expiresAt,
        long invitedByUserId,
        OffsetDateTime now
    ) {
        return new RegistrationInvitation(
            0, tokenDigest, email, role, permissions, expiresAt, null, null,
            invitedByUserId, now, now
        );
    }

    public RegistrationInvitation withId(long persistedId) {
        if (id != 0 || persistedId <= 0) {
            throw new IllegalStateException("Only a new invitation can receive a persistence identity");
        }
        return new RegistrationInvitation(
            persistedId, tokenDigest, email, role, permissions, expiresAt, acceptedAt,
            acceptedUserId, invitedByUserId, createdAt, updatedAt
        );
    }

    public boolean canBeAcceptedBy(String candidateEmail, OffsetDateTime now) {
        return candidateEmail != null
            && now != null
            && acceptedAt == null
            && email.equalsIgnoreCase(candidateEmail)
            && expiresAt.isAfter(now);
    }

    public RegistrationInvitation accept(long userId, OffsetDateTime acceptedTime) {
        if (id <= 0 || userId <= 0 || acceptedTime == null || acceptedAt != null
            || !expiresAt.isAfter(acceptedTime)) {
            throw new IllegalStateException("Invitation cannot be accepted");
        }
        return new RegistrationInvitation(
            id, tokenDigest, email, role, permissions, expiresAt, acceptedTime, userId,
            invitedByUserId, createdAt, acceptedTime
        );
    }
}
