package com.mamoji.platform.identity.security.domain;

import java.time.Duration;
import java.time.OffsetDateTime;

/** Pure transition policy for login-failure windows and temporary locks. */
public class LoginFailurePolicy {
    private final Duration failureWindow;
    private final Duration lockDuration;

    public LoginFailurePolicy(Duration failureWindow, Duration lockDuration) {
        if (failureWindow == null || failureWindow.isNegative() || failureWindow.isZero()) {
            throw new IllegalArgumentException("Login failure window must be positive");
        }
        if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("Login lock duration must be positive");
        }
        this.failureWindow = failureWindow;
        this.lockDuration = lockDuration;
    }

    public LoginFailureState recordFailure(LoginFailureState current, int threshold, OffsetDateTime now) {
        if (current == null || now == null || threshold < 1) {
            throw new IllegalArgumentException("Current state, timestamp, and positive threshold are required");
        }
        boolean expired = current.failedAttempts() == 0
            || current.windowStartedAt().plus(failureWindow).isBefore(now)
            || current.lockedUntil() != null && !current.lockedUntil().isAfter(now);
        int failedAttempts = expired ? 1 : current.failedAttempts() + 1;
        OffsetDateTime windowStartedAt = expired ? now : current.windowStartedAt();
        OffsetDateTime lockedUntil = failedAttempts >= threshold
            ? now.plus(lockDuration)
            : expired ? null : current.lockedUntil();
        return new LoginFailureState(
            current.subject(), failedAttempts, windowStartedAt, lockedUntil, now
        );
    }

    public Duration inactiveRetention() {
        return failureWindow.plus(lockDuration);
    }
}
