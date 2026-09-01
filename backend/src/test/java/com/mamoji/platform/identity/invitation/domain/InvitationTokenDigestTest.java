package com.mamoji.platform.identity.invitation.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InvitationTokenDigestTest {
    @Test
    void derivesStableStorageWithoutRetainingTheRawInvitationCredential() {
        String rawToken = "a".repeat(64);

        InvitationTokenDigest digest = InvitationTokenDigest.fromRawToken(rawToken);

        assertEquals(digest, InvitationTokenDigest.fromRawToken(rawToken));
        assertNotEquals(rawToken, digest.value());
        assertTrue(digest.value().matches("sha256:[A-Za-z0-9_-]{43}"));
        assertEquals(digest, InvitationTokenDigest.fromStoredOrLegacyToken(rawToken));
        assertEquals(digest, InvitationTokenDigest.fromStoredOrLegacyToken(digest.value()));
    }

    @Test
    void rejectsMalformedRawAndStoredCredentialsWithoutHashingArbitraryInput() {
        assertTrue(InvitationTokenDigest.tryFromRawToken(null).isEmpty());
        assertTrue(InvitationTokenDigest.tryFromRawToken("short").isEmpty());
        assertTrue(InvitationTokenDigest.tryFromRawToken("A".repeat(64)).isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
            InvitationTokenDigest.fromRawToken("short")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new InvitationTokenDigest("plaintext")
        );
    }
}
