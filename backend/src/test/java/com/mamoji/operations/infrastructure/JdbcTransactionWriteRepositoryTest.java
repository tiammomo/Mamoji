package com.mamoji.operations.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.operations.application.TransactionQueryRepository;
import com.mamoji.operations.domain.TransactionRecord;
import com.mamoji.repository.InMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTransactionWriteRepositoryTest {
    @Test
    void rejectsAStaleTransactionUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcTransactionWriteRepository repository = repository(jdbc, compatibilityStore);
        TransactionRecord transaction = transaction(42, 3);

        OptimisticLockingFailureException exception = assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.update(transaction)
        );

        assertEquals("Transaction was changed by another request: 42", exception.getMessage());
        assertEquals(3, transaction.version);
        verify(compatibilityStore, never()).synchronizeTransactionAfterCommit(transaction);
    }

    @Test
    void advancesVersionAndSynchronizesCompatibilityViewAfterUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcTransactionWriteRepository repository = repository(jdbc, compatibilityStore);
        TransactionRecord transaction = transaction(42, 3);

        repository.update(transaction);

        assertEquals(4, transaction.version);
        verify(compatibilityStore).synchronizeTransactionAfterCommit(transaction);
    }

    @Test
    void rejectsAStaleTransactionDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcTransactionWriteRepository repository = repository(jdbc, compatibilityStore);
        TransactionRecord transaction = transaction(42, 3);

        assertThrows(OptimisticLockingFailureException.class, () -> repository.delete(transaction));

        verify(compatibilityStore, never()).removeTransactionFromCompatibilityViewAfterCommit(42);
    }

    @Test
    void removesCompatibilityViewAfterDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcTransactionWriteRepository repository = repository(jdbc, compatibilityStore);

        repository.delete(transaction(42, 3));

        verify(compatibilityStore).removeTransactionFromCompatibilityViewAfterCommit(42);
    }

    private JdbcTransactionWriteRepository repository(
        JdbcTemplate jdbc,
        InMemoryStore compatibilityStore
    ) {
        return new JdbcTransactionWriteRepository(
            jdbc,
            mock(TransactionQueryRepository.class),
            compatibilityStore
        );
    }

    private TransactionRecord transaction(long id, long version) {
        TransactionRecord transaction = new TransactionRecord();
        transaction.id = id;
        transaction.version = version;
        return transaction;
    }
}
