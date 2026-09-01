package com.mamoji.platform.identity.security.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Pseudonymous login-throttle subject; raw email and source addresses are never persisted. */
public record LoginThrottleSubject(Type type, String keyHash) {
    public LoginThrottleSubject {
        if (type == null) {
            throw new IllegalArgumentException("Login throttle subject type is required");
        }
        if (keyHash == null || !keyHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Login throttle key must be a SHA-256 hex digest");
        }
    }

    public static LoginThrottleSubject email(String email) {
        String normalized = email == null || email.isBlank()
            ? "blank"
            : email.trim().toLowerCase(Locale.ROOT);
        return hashed(Type.EMAIL, normalized);
    }

    public static LoginThrottleSubject source(String clientAddress) {
        String normalized = clientAddress == null || clientAddress.isBlank()
            ? "unknown"
            : clientAddress.trim().toLowerCase(Locale.ROOT);
        return hashed(Type.SOURCE, normalized);
    }

    private static LoginThrottleSubject hashed(Type type, String normalizedValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (type.databaseValue + "\0" + normalizedValue).getBytes(StandardCharsets.UTF_8)
            );
            return new LoginThrottleSubject(type, HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public enum Type {
        EMAIL("email"),
        SOURCE("source");

        private final String databaseValue;

        Type(String databaseValue) {
            this.databaseValue = databaseValue;
        }

        public String databaseValue() {
            return databaseValue;
        }

        public static Type fromDatabase(String value) {
            for (Type type : values()) {
                if (type.databaseValue.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unsupported login throttle subject type: " + value);
        }
    }
}
