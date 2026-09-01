package com.mamoji.finance.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mamoji.finance.domain.Account;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcFinanceRepositoryTest {
    @Test
    void rejectsAStaleAccountUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcFinanceRepository repository = new JdbcFinanceRepository(jdbc);
        Account account = account(42, 3);

        OptimisticLockingFailureException exception = assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.updateAccount(account)
        );

        assertEquals("Account was changed by another request: 42", exception.getMessage());
        assertEquals(3, account.version);
    }

    @Test
    void advancesVersionAfterACommittedAccountUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcFinanceRepository repository = new JdbcFinanceRepository(jdbc);
        Account account = account(42, 3);

        repository.updateAccount(account);

        assertEquals(4, account.version);
    }

    @Test
    void rejectsAStaleAccountDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcFinanceRepository repository = new JdbcFinanceRepository(jdbc);

        assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.deleteAccount(account(42, 3))
        );

    }

    @Test
    void deletesTheExpectedAccountVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcFinanceRepository repository = new JdbcFinanceRepository(jdbc);

        repository.deleteAccount(account(42, 3));
    }

    private Account account(long id, long version) {
        Account account = new Account();
        account.id = id;
        account.version = version;
        account.createdAt = "2026-09-01T09:00:00Z";
        account.updatedAt = "2026-09-01T10:00:00Z";
        return account;
    }
}
