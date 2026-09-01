package com.mamoji.platform.identity.invitation.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/** One-way storage representation of a 256-bit registration invitation token. */
public record InvitationTokenDigest(String value) {
    private static final String PREFIX = "sha256:";
    private static final String RAW_FORMAT = "[0-9a-f]{64}";
    private static final String DIGEST_FORMAT = "sha256:[A-Za-z0-9_-]{43}";

    public InvitationTokenDigest {
        if (value == null || !value.matches(DIGEST_FORMAT)) {
            throw new IllegalArgumentException("Invitation token digest has an invalid format");
        }
    }

    public static InvitationTokenDigest fromRawToken(String rawToken) {
        if (rawToken == null || !rawToken.matches(RAW_FORMAT)) {
            throw new IllegalArgumentException("Invitation token has an invalid format");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                rawToken.getBytes(StandardCharsets.UTF_8)
            );
            return new InvitationTokenDigest(
                PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static Optional<InvitationTokenDigest> tryFromRawToken(String rawToken) {
        if (rawToken == null || !rawToken.matches(RAW_FORMAT)) {
            return Optional.empty();
        }
        return Optional.of(fromRawToken(rawToken));
    }

    /** Accepts current digest storage and legacy raw tokens from structured backups. */
    public static InvitationTokenDigest fromStoredOrLegacyToken(String value) {
        if (value != null && value.matches(DIGEST_FORMAT)) {
            return new InvitationTokenDigest(value);
        }
        return fromRawToken(value);
    }
}
