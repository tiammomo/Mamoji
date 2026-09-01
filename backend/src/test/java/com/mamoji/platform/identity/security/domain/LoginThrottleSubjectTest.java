package com.mamoji.platform.identity.security.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LoginThrottleSubjectTest {
    @Test
    void normalizesEmailWithoutPersistingTheRawIdentifier() {
        LoginThrottleSubject first = LoginThrottleSubject.email(" User@Example.COM ");
        LoginThrottleSubject second = LoginThrottleSubject.email("user@example.com");

        assertEquals(first, second);
        assertEquals(64, first.keyHash().length());
        assertNotEquals("user@example.com", first.keyHash());
        assertNotEquals(first.keyHash(), LoginThrottleSubject.source("user@example.com").keyHash());
    }

    @Test
    void rejectsMalformedPersistentKeys() {
        assertThrows(IllegalArgumentException.class, () ->
            new LoginThrottleSubject(LoginThrottleSubject.Type.EMAIL, "not-a-digest")
        );
    }
}
