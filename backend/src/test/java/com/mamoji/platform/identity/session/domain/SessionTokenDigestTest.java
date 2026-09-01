package com.mamoji.platform.identity.session.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionTokenDigestTest {
    @Test
    void derivesAStableStorageKeyWithoutRetainingTheBearerToken() {
        String rawToken = "a".repeat(43);

        SessionTokenDigest digest = SessionTokenDigest.fromRawToken(rawToken);

        assertEquals(digest, SessionTokenDigest.fromRawToken(rawToken));
        assertNotEquals(rawToken, digest.value());
        assertTrue(digest.value().matches("sha256:[A-Za-z0-9_-]{43}"));
        assertEquals(digest, SessionTokenDigest.fromAuthorization("Bearer " + rawToken).orElseThrow());
    }

    @Test
    void rejectsMissingOrMalformedTokens() {
        assertTrue(SessionTokenDigest.fromAuthorization(null).isEmpty());
        assertTrue(SessionTokenDigest.fromAuthorization("Basic credentials").isEmpty());
        assertTrue(SessionTokenDigest.fromAuthorization("Bearer ").isEmpty());
        assertTrue(SessionTokenDigest.fromAuthorization("Bearer short-token").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SessionTokenDigest.fromRawToken("short-token"));
        assertThrows(IllegalArgumentException.class, () -> new SessionTokenDigest("plaintext"));
    }
}
