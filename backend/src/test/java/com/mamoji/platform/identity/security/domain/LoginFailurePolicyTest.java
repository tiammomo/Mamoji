package com.mamoji.platform.identity.security.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class LoginFailurePolicyTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");
    private final LoginFailurePolicy policy = new LoginFailurePolicy(
        Duration.ofMinutes(15), Duration.ofMinutes(20)
    );

    @Test
    void locksAtTheThresholdWithoutResettingTheFailureWindow() {
        LoginFailureState current = state(4, START, null, START.plusMinutes(4));

        LoginFailureState next = policy.recordFailure(current, 5, START.plusMinutes(5));

        assertEquals(5, next.failedAttempts());
        assertEquals(START, next.windowStartedAt());
        assertEquals(START.plusMinutes(25), next.lockedUntil());
    }

    @Test
    void startsANewWindowAfterTheWindowOrLockExpires() {
        LoginFailureState expiredWindow = state(4, START, null, START.plusMinutes(10));
        LoginFailureState afterWindow = policy.recordFailure(expiredWindow, 5, START.plusMinutes(16));

        assertEquals(1, afterWindow.failedAttempts());
        assertEquals(START.plusMinutes(16), afterWindow.windowStartedAt());
        assertNull(afterWindow.lockedUntil());

        LoginFailureState expiredLock = state(5, START, START.plusMinutes(20), START.plusMinutes(5));
        LoginFailureState afterLock = policy.recordFailure(expiredLock, 5, START.plusMinutes(20));

        assertEquals(1, afterLock.failedAttempts());
        assertEquals(START.plusMinutes(20), afterLock.windowStartedAt());
        assertNull(afterLock.lockedUntil());
    }

    private LoginFailureState state(
        int attempts,
        OffsetDateTime windowStartedAt,
        OffsetDateTime lockedUntil,
        OffsetDateTime updatedAt
    ) {
        return new LoginFailureState(
            LoginThrottleSubject.email("security@example.invalid"),
            attempts,
            windowStartedAt,
            lockedUntil,
            updatedAt
        );
    }
}
