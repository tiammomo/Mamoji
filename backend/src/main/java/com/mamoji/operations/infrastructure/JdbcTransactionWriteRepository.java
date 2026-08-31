package com.mamoji.operations.infrastructure;

import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.operations.application.TransactionQueryRepository;
import com.mamoji.operations.application.TransactionWriteRepository;
import com.mamoji.repository.InMemoryStore;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for locked and optimistic transaction writes. */
@Repository
public class JdbcTransactionWriteRepository implements TransactionWriteRepository {
    private final JdbcTemplate jdbc;
    private final TransactionQueryRepository queryRepository;
    private final InMemoryStore compatibilityStore;

    public JdbcTransactionWriteRepository(
        JdbcTemplate jdbc,
        TransactionQueryRepository queryRepository,
        InMemoryStore compatibilityStore
    ) {
        this.jdbc = jdbc;
        this.queryRepository = queryRepository;
        this.compatibilityStore = compatibilityStore;
    }

    @Override
    public void lockIdempotency(long companyId, String idempotencyKey) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> { },
            "transaction:" + companyId + ":" + idempotencyKey
        );
    }

    @Override
    public Optional<TransactionRecord> findByIdempotency(long companyId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return jdbc.query(
            "SELECT id FROM transactions WHERE company_id = ? AND idempotency_key = ?",
            (rs, rowNum) -> rs.getLong("id"),
            companyId,
            idempotencyKey
        ).stream().findFirst().flatMap(queryRepository::findById);
    }

    @Override
    public Optional<TransactionRecord> findForUpdate(long id) {
        return jdbc.query(
            "SELECT id FROM transactions WHERE id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getLong("id"),
            id
        ).stream().findFirst().flatMap(queryRepository::findById);
    }

    @Override
    public boolean hasRefunds(long transactionId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE original_transaction_id = ?",
            Integer.class,
            transactionId
        );
        return count != null && count > 0;
    }

    @Override
    public TransactionRecord insert(TransactionRecord transaction) {
        transaction.version = 0;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                    user_id, family_id, type, amount, category_id, account_id, date, note,
                    original_transaction_id, refunded_amount, is_refundable, budget_id,
                    created_at, updated_at, company_id, idempotency_key
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[] { "id" });
            bindInsert(statement, transaction);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Database did not return a generated transaction id");
        }
        transaction.id = key.longValue();
        compatibilityStore.synchronizeTransactionAfterCommit(transaction);
        return transaction;
    }

    @Override
    public void update(TransactionRecord transaction) {
        int updated = jdbc.update("""
            UPDATE transactions
            SET user_id = ?, family_id = ?, type = ?, amount = ?, category_id = ?, account_id = ?,
                date = ?, note = ?, original_transaction_id = ?, refunded_amount = ?, is_refundable = ?,
                budget_id = ?, created_at = ?, updated_at = ?, company_id = ?, idempotency_key = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """,
            transaction.userId,
            transaction.familyId,
            transaction.type,
            moneyText(transaction.amount),
            transaction.categoryId,
            transaction.accountId,
            transaction.date,
            transaction.note,
            transaction.originalTransactionId,
            moneyText(transaction.refundedAmount),
            transaction.isRefundable ? 1 : 0,
            transaction.budgetId,
            transaction.createdAt,
            transaction.updatedAt,
            transaction.companyId,
            transaction.idempotencyKey,
            transaction.id,
            transaction.version
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                "Transaction was changed by another request: " + transaction.id
            );
        }
        transaction.version++;
        compatibilityStore.synchronizeTransactionAfterCommit(transaction);
    }

    @Override
    public void delete(TransactionRecord transaction) {
        int deleted = jdbc.update(
            "DELETE FROM transactions WHERE id = ? AND version = ?",
            transaction.id,
            transaction.version
        );
        if (deleted != 1) {
            throw new OptimisticLockingFailureException(
                "Transaction was changed by another request: " + transaction.id
            );
        }
        compatibilityStore.removeTransactionFromCompatibilityViewAfterCommit(transaction.id);
    }

    private void bindInsert(PreparedStatement statement, TransactionRecord transaction) throws SQLException {
        statement.setLong(1, transaction.userId);
        setLongOrNull(statement, 2, transaction.familyId);
        statement.setInt(3, transaction.type);
        statement.setString(4, moneyText(transaction.amount));
        statement.setLong(5, transaction.categoryId);
        statement.setLong(6, transaction.accountId);
        statement.setString(7, transaction.date);
        statement.setString(8, transaction.note);
        setLongOrNull(statement, 9, transaction.originalTransactionId);
        statement.setString(10, moneyText(transaction.refundedAmount));
        statement.setInt(11, transaction.isRefundable ? 1 : 0);
        setLongOrNull(statement, 12, transaction.budgetId);
        statement.setString(13, transaction.createdAt);
        statement.setString(14, transaction.updatedAt);
        setLongOrNull(statement, 15, transaction.companyId);
        statement.setString(16, transaction.idempotencyKey);
    }

    private void setLongOrNull(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private String moneyText(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }
}
