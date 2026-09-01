package com.mamoji.platform.identity.security.application;

import com.mamoji.platform.identity.security.domain.LoginFailurePolicy;
import com.mamoji.platform.identity.security.domain.LoginFailureState;
import com.mamoji.platform.identity.security.domain.LoginThrottleSubject;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LoginSecurityService {
    private final LoginFailureRepository repository;
    private final LoginFailurePolicy policy;
    private final int maxFailedAttempts;
    private final int maxFailedAttemptsPerSource;

    public LoginSecurityService(
        LoginFailureRepository repository,
        @Value("${mamoji.security.auth.max-failed-attempts:5}") int maxFailedAttempts,
        @Value("${mamoji.security.auth.max-failed-attempts-per-source:50}") int maxFailedAttemptsPerSource,
        @Value("${mamoji.security.auth.failure-window-minutes:15}") long failureWindowMinutes,
        @Value("${mamoji.security.auth.lock-minutes:15}") long lockMinutes
    ) {
        this.repository = repository;
        this.maxFailedAttempts = Math.max(1, maxFailedAttempts);
        this.maxFailedAttemptsPerSource = Math.max(this.maxFailedAttempts, maxFailedAttemptsPerSource);
        this.policy = new LoginFailurePolicy(
            Duration.ofMinutes(Math.max(1, failureWindowMinutes)),
            Duration.ofMinutes(Math.max(1, lockMinutes))
        );
    }

    @Transactional(readOnly = true)
    public void requireLoginAllowed(String email, String clientAddress) {
        lockedUntil(email, clientAddress).ifPresent(until -> {
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed login attempts. Try again later."
            );
        });
    }

    @Transactional
    public Optional<OffsetDateTime> recordFailure(String email, String clientAddress) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<OffsetDateTime> emailLockedUntil = recordFailure(
            LoginThrottleSubject.email(email), maxFailedAttempts, now
        );
        Optional<OffsetDateTime> sourceLockedUntil = recordFailure(
            LoginThrottleSubject.source(clientAddress), maxFailedAttemptsPerSource, now
        );
        return Stream.of(emailLockedUntil, sourceLockedUntil)
            .flatMap(Optional::stream)
            .max(OffsetDateTime::compareTo);
    }

    @Transactional
    public void recordSuccess(String email) {
        repository.delete(LoginThrottleSubject.email(email));
    }

    @Transactional(readOnly = true)
    public Optional<OffsetDateTime> lockedUntil(String email, String clientAddress) {
        return repository.findLatestActiveLock(List.of(
            LoginThrottleSubject.email(email),
            LoginThrottleSubject.source(clientAddress)
        ), OffsetDateTime.now());
    }

    @Scheduled(
        fixedDelayString = "${mamoji.security.auth.cleanup-interval-ms:3600000}",
        initialDelayString = "${mamoji.security.auth.cleanup-initial-delay-ms:3600000}"
    )
    @Transactional
    public void purgeInactiveStates() {
        OffsetDateTime now = OffsetDateTime.now();
        repository.deleteInactiveBefore(now.minus(policy.inactiveRetention()), now);
    }

    private Optional<OffsetDateTime> recordFailure(
        LoginThrottleSubject subject,
        int threshold,
        OffsetDateTime now
    ) {
        LoginFailureState current = repository.lockOrCreate(subject, now);
        LoginFailureState next = policy.recordFailure(current, threshold, now);
        repository.update(next);
        return Optional.ofNullable(next.lockedUntil());
    }
}
