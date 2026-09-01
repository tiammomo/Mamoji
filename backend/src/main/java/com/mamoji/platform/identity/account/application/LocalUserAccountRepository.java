package com.mamoji.platform.identity.account.application;

import com.mamoji.platform.identity.User;
import java.util.Optional;

/** Authoritative persistence port for locally authenticated user accounts. */
public interface LocalUserAccountRepository {
    long count();

    User insert(User user);

    Optional<User> findByEmail(String email);

    Optional<User> findById(long id);

    Optional<User> findByIdForUpdate(long id);

    void update(User user);

    boolean updatePasswordHashIfCurrent(
        long userId,
        String currentPasswordHash,
        String nextPasswordHash,
        String updatedAt
    );
}
