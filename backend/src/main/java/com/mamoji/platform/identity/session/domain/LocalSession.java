package com.mamoji.platform.identity.session.domain;

import java.time.OffsetDateTime;

/** Durable local session metadata; the raw bearer token is never stored. */
public record LocalSession(
    SessionTokenDigest tokenDigest,
    long userId,
    OffsetDateTime createdAt,
    OffsetDateTime expiresAt
) {
    public LocalSession {
        if (tokenDigest == null || userId <= 0 || createdAt == null || expiresAt == null) {
            throw new IllegalArgumentException("Session token, user, and timestamps are required");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Session expiry must be after creation");
        }
    }
}
