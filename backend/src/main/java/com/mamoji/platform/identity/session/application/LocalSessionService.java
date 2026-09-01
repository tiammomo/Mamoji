package com.mamoji.platform.identity.session.application;

import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.session.domain.LocalSession;
import com.mamoji.platform.identity.session.domain.SessionTokenDigest;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalSessionService {
    private final LocalSessionRepository repository;

    public LocalSessionService(LocalSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void create(String rawToken, long userId, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        repository.insert(new LocalSession(
            SessionTokenDigest.fromRawToken(rawToken), userId, createdAt, expiresAt
        ));
    }

    @Transactional(readOnly = true)
    public Optional<User> authenticate(String authorization) {
        return SessionTokenDigest.fromAuthorization(authorization)
            .flatMap(token -> repository.findActiveUser(token, OffsetDateTime.now()));
    }

    @Transactional
    public void revoke(String authorization) {
        SessionTokenDigest.fromAuthorization(authorization).ifPresent(repository::delete);
    }

    @Scheduled(
        fixedDelayString = "${mamoji.security.auth.session-cleanup-interval-ms:3600000}",
        initialDelayString = "${mamoji.security.auth.session-cleanup-initial-delay-ms:3600000}"
    )
    @Transactional
    public void purgeExpiredSessions() {
        repository.deleteExpired(OffsetDateTime.now());
    }
}
