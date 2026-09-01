package com.mamoji.platform.identity.account.application;

import java.util.List;
import java.util.Optional;

/** Password-free cross-module projection of local users. */
public interface UserDirectory {
    Optional<Entry> findById(long id);

    List<Entry> findAll();

    record Entry(
        long id,
        String email,
        String nickname,
        String avatar,
        Long familyId,
        int role,
        int permissions
    ) {
    }
}
