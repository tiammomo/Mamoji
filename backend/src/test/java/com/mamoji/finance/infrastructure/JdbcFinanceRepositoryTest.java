package com.mamoji.finance.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.finance.domain.Account;
import com.mamoji.repository.InMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcFinanceRepositoryTest {
    @Test
    void rejectsAStaleAccountUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcFinanceRepository repository = new JdbcFinanceRepository(jdbc, compatibilityStore);
        Account account = account(42, 3);

        OptimisticLockingFailureException exception = assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.updateAccount(account)
        );

        assertEquals("Account was changed by another request: 42", exception.getMessage());
        assertEquals(3, account.version);
        verify(compatibilityStore, never()).synchronizeAccountAfterCommit(account);
    }

    @Test
    void advancesVersionAndSynchronizesCompatibilityViewAfterAccountUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcFinanceRepository repository = new JdbcFinanceRepository(jdbc, compatibilityStore);
        Account account = account(42, 3);

        repository.updateAccount(account);

        assertEquals(4, account.version);
        verify(compatibilityStore).synchronizeAccountAfterCommit(account);
    }

    @Test
    void rejectsAStaleAccountDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcFinanceRepository repository = new JdbcFinanceRepository(jdbc, compatibilityStore);

        assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.deleteAccount(account(42, 3))
        );

        verify(compatibilityStore, never()).removeAccountFromCompatibilityViewAfterCommit(42);
    }

    @Test
    void removesCompatibilityViewAfterAccountDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcFinanceRepository repository = new JdbcFinanceRepository(jdbc, compatibilityStore);

        repository.deleteAccount(account(42, 3));

        verify(compatibilityStore).removeAccountFromCompatibilityViewAfterCommit(42);
    }

    private Account account(long id, long version) {
        Account account = new Account();
        account.id = id;
        account.version = version;
        return account;
    }
}
