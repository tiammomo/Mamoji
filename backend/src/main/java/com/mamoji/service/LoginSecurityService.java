package com.mamoji.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LoginSecurityService {
    private final Map<String, LoginFailureState> failures = new ConcurrentHashMap<>();
    private final int maxFailedAttempts;
    private final int maxFailedAttemptsPerSource;
    private final Duration failureWindow;
    private final Duration lockDuration;

    public LoginSecurityService(
        @Value("${mamoji.security.auth.max-failed-attempts:5}") int maxFailedAttempts,
        @Value("${mamoji.security.auth.max-failed-attempts-per-source:50}") int maxFailedAttemptsPerSource,
        @Value("${mamoji.security.auth.failure-window-minutes:15}") long failureWindowMinutes,
        @Value("${mamoji.security.auth.lock-minutes:15}") long lockMinutes
    ) {
        this.maxFailedAttempts = Math.max(1, maxFailedAttempts);
        this.maxFailedAttemptsPerSource = Math.max(this.maxFailedAttempts, maxFailedAttemptsPerSource);
        this.failureWindow = Duration.ofMinutes(Math.max(1, failureWindowMinutes));
        this.lockDuration = Duration.ofMinutes(Math.max(1, lockMinutes));
    }

    public void requireLoginAllowed(String email, String clientIp) {
        lockedUntil(email, clientIp).ifPresent(until -> {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Try again later.");
        });
    }

    public Optional<OffsetDateTime> recordFailure(String email, String clientIp) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<OffsetDateTime> emailLockedUntil = recordFailureForKey(emailKey(email), maxFailedAttempts, now);
        Optional<OffsetDateTime> ipLockedUntil = recordFailureForKey(ipKey(clientIp), maxFailedAttemptsPerSource, now);
        return emailLockedUntil.or(() -> ipLockedUntil);
    }

    public void recordSuccess(String email, String clientIp) {
        failures.remove(emailKey(email));
        failures.remove(ipKey(clientIp));
    }

    public Optional<OffsetDateTime> lockedUntil(String email, String clientIp) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<OffsetDateTime> emailLockedUntil = lockedUntil(emailKey(email), now);
        Optional<OffsetDateTime> ipLockedUntil = lockedUntil(ipKey(clientIp), now);
        return emailLockedUntil.or(() -> ipLockedUntil);
    }

    private Optional<OffsetDateTime> recordFailureForKey(String key, int threshold, OffsetDateTime now) {
        LoginFailureState next = failures.compute(key, (ignored, current) -> {
            if (current == null || current.windowStartedAt.plus(failureWindow).isBefore(now)) {
                return new LoginFailureState(1, now, null);
            }
            int failedAttempts = current.failedAttempts + 1;
            OffsetDateTime lockedUntil = failedAttempts >= threshold ? now.plus(lockDuration) : current.lockedUntil;
            return new LoginFailureState(failedAttempts, current.windowStartedAt, lockedUntil);
        });
        return next == null ? Optional.empty() : Optional.ofNullable(next.lockedUntil);
    }

    private Optional<OffsetDateTime> lockedUntil(String key, OffsetDateTime now) {
        LoginFailureState state = failures.get(key);
        if (state == null || state.lockedUntil == null) {
            return Optional.empty();
        }
        if (state.lockedUntil.isBefore(now)) {
            failures.remove(key);
            return Optional.empty();
        }
        return Optional.of(state.lockedUntil);
    }

    private String emailKey(String email) {
        return "email:" + (email == null || email.isBlank() ? "blank" : email.trim().toLowerCase());
    }

    private String ipKey(String clientIp) {
        return "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim());
    }

    private record LoginFailureState(int failedAttempts, OffsetDateTime windowStartedAt, OffsetDateTime lockedUntil) {
    }
}
