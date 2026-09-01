package com.mamoji.platform.identity.session.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/** One-way storage representation of a bearer session token. */
public record SessionTokenDigest(String value) {
    private static final String PREFIX = "sha256:";
    private static final String RAW_FORMAT = "[A-Za-z0-9_-]{43}";
    private static final String FORMAT = "sha256:[A-Za-z0-9_-]{43}";

    public SessionTokenDigest {
        if (value == null || !value.matches(FORMAT)) {
            throw new IllegalArgumentException("Session token digest has an invalid format");
        }
    }

    public static SessionTokenDigest fromRawToken(String rawToken) {
        if (rawToken == null || !rawToken.matches(RAW_FORMAT)) {
            throw new IllegalArgumentException("Session token has an invalid format");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                rawToken.getBytes(StandardCharsets.UTF_8)
            );
            return new SessionTokenDigest(
                PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static Optional<SessionTokenDigest> fromAuthorization(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authorization.substring("Bearer ".length());
        if (!token.matches(RAW_FORMAT)) {
            return Optional.empty();
        }
        return Optional.of(fromRawToken(token));
    }
}
