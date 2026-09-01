package com.mamoji.platform.identity.session.application;

import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.session.domain.LocalSession;
import com.mamoji.platform.identity.session.domain.SessionTokenDigest;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Persistence port for local bearer sessions. */
public interface LocalSessionRepository {
    void insert(LocalSession session);

    Optional<User> findActiveUser(SessionTokenDigest tokenDigest, OffsetDateTime now);

    void delete(SessionTokenDigest tokenDigest);

    int deleteExpired(OffsetDateTime now);
}
