package com.mamoji.platform.identity.security.domain;

import java.time.OffsetDateTime;

/** Durable failed-login window for one pseudonymous email or network source. */
public record LoginFailureState(
    LoginThrottleSubject subject,
    int failedAttempts,
    OffsetDateTime windowStartedAt,
    OffsetDateTime lockedUntil,
    OffsetDateTime updatedAt
) {
    public LoginFailureState {
        if (subject == null || windowStartedAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Login failure state identity and timestamps are required");
        }
        if (failedAttempts < 0) {
            throw new IllegalArgumentException("Failed login attempts must not be negative");
        }
    }
}
