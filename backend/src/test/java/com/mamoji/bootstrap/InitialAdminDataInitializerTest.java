package com.mamoji.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import com.mamoji.service.support.PasswordHasher;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InitialAdminDataInitializerTest {
    @Test
    void leavesExistingUserAccountsUntouched() {
        LocalUserAccountRepository users = mock(LocalUserAccountRepository.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        when(users.count()).thenReturn(1L);

        initializer(users, passwordHasher, "bootstrap", "ops@example.com", "Strong-pass-123!", true)
            .initialize();

        verify(users, never()).insert(any());
        verifyNoInteractions(passwordHasher);
    }

    @Test
    void seedsTheConfiguredDemoAdministrator() {
        LocalUserAccountRepository users = mock(LocalUserAccountRepository.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        when(passwordHasher.hash("123456")).thenReturn("demo-hash");

        initializer(users, passwordHasher, "demo", "test@mamoji.com", "123456", false)
            .initialize();

        User inserted = captureInsertedUser(users);
        assertEquals("test@mamoji.com", inserted.email);
        assertEquals("Administrator", inserted.nickname);
        assertEquals("demo-hash", inserted.passwordHash);
        assertEquals(Roles.ADMIN, inserted.role);
        assertEquals(Permissions.ALL, inserted.permissions);
        assertEquals(inserted.createdAt, inserted.updatedAt);
        OffsetDateTime.parse(inserted.createdAt);
    }

    @Test
    void rejectsWeakProductionBootstrapCredentialsBeforeHashingOrWriting() {
        LocalUserAccountRepository users = mock(LocalUserAccountRepository.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        InitialAdminDataInitializer initializer = initializer(
            users,
            passwordHasher,
            "bootstrap",
            "ops@example.com",
            "123456",
            true
        );

        assertThrows(IllegalStateException.class, initializer::initialize);

        verify(users, never()).insert(any());
        verifyNoInteractions(passwordHasher);
    }

    @Test
    void normalizesAndSeedsStrongProductionBootstrapCredentials() {
        LocalUserAccountRepository users = mock(LocalUserAccountRepository.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        when(passwordHasher.hash("Strong-pass-123!")).thenReturn("production-hash");

        initializer(users, passwordHasher, "BOOTSTRAP", " Ops@Example.COM ", "Strong-pass-123!", true)
            .initialize();

        User inserted = captureInsertedUser(users);
        assertEquals("ops@example.com", inserted.email);
        assertEquals("production-hash", inserted.passwordHash);
    }

    private InitialAdminDataInitializer initializer(
        LocalUserAccountRepository users,
        PasswordHasher passwordHasher,
        String mode,
        String email,
        String password,
        boolean requireComplexity
    ) {
        return new InitialAdminDataInitializer(
            users,
            passwordHasher,
            mode,
            email,
            password,
            "Administrator",
            12,
            requireComplexity
        );
    }

    private User captureInsertedUser(LocalUserAccountRepository users) {
        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(users).insert(user.capture());
        return user.getValue();
    }
}
