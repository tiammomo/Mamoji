package com.mamoji.platform.identity.session.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class LocalSessionTest {
    @Test
    void acceptsABoundedSessionWindow() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

        LocalSession session = new LocalSession(
            SessionTokenDigest.fromRawToken("a".repeat(43)), 7, createdAt, createdAt.plusHours(12)
        );

        assertEquals(7, session.userId());
        assertEquals(createdAt.plusHours(12), session.expiresAt());
    }

    @Test
    void rejectsInvalidIdentityOrExpiry() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");
        SessionTokenDigest digest = SessionTokenDigest.fromRawToken("a".repeat(43));

        assertThrows(IllegalArgumentException.class, () ->
            new LocalSession(digest, 0, createdAt, createdAt.plusHours(1))
        );
        assertThrows(IllegalArgumentException.class, () ->
            new LocalSession(digest, 1, createdAt, createdAt)
        );
    }
}
