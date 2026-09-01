package com.mamoji.operations.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mamoji.operations.application.TransactionQueryRepository;
import com.mamoji.operations.domain.TransactionRecord;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTransactionWriteRepositoryTest {
    @Test
    void rejectsAStaleTransactionUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcTransactionWriteRepository repository = repository(jdbc);
        TransactionRecord transaction = transaction(42, 3);

        OptimisticLockingFailureException exception = assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.update(transaction)
        );

        assertEquals("Transaction was changed by another request: 42", exception.getMessage());
        assertEquals(3, transaction.version);
    }

    @Test
    void advancesVersionAfterUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcTransactionWriteRepository repository = repository(jdbc);
        TransactionRecord transaction = transaction(42, 3);

        repository.update(transaction);

        assertEquals(4, transaction.version);
    }

    @Test
    void rejectsAStaleTransactionDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcTransactionWriteRepository repository = repository(jdbc);
        TransactionRecord transaction = transaction(42, 3);

        assertThrows(OptimisticLockingFailureException.class, () -> repository.delete(transaction));
    }

    @Test
    void deletesTheExpectedVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcTransactionWriteRepository repository = repository(jdbc);

        repository.delete(transaction(42, 3));
    }

    private JdbcTransactionWriteRepository repository(JdbcTemplate jdbc) {
        return new JdbcTransactionWriteRepository(
            jdbc,
            mock(TransactionQueryRepository.class)
        );
    }

    private TransactionRecord transaction(long id, long version) {
        TransactionRecord transaction = new TransactionRecord();
        transaction.id = id;
        transaction.version = version;
        transaction.companyId = 9L;
        transaction.date = "2026-09-01";
        transaction.createdAt = OffsetDateTime.now().minusMinutes(1).toString();
        transaction.updatedAt = OffsetDateTime.now().toString();
        return transaction;
    }
}
