package com.mamoji.platform.identity.security.application;

import com.mamoji.platform.identity.security.domain.LoginFailureState;
import com.mamoji.platform.identity.security.domain.LoginThrottleSubject;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;

/** Persistence port for atomic, cross-instance login throttling. */
public interface LoginFailureRepository {
    LoginFailureState lockOrCreate(LoginThrottleSubject subject, OffsetDateTime now);

    void update(LoginFailureState state);

    Optional<OffsetDateTime> findLatestActiveLock(
        Collection<LoginThrottleSubject> subjects,
        OffsetDateTime now
    );

    void delete(LoginThrottleSubject subject);

    int deleteInactiveBefore(OffsetDateTime updatedBefore, OffsetDateTime now);
}
