package com.mamoji.operations.infrastructure;

import com.mamoji.operations.application.TransactionLinkQuery;
import com.mamoji.operations.application.TransactionLinkTarget;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter that exposes only transaction ownership needed by cross-module links. */
@Repository
public class JdbcTransactionLinkQuery implements TransactionLinkQuery {
    private final JdbcTemplate jdbc;

    public JdbcTransactionLinkQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TransactionLinkTarget> findById(long transactionId) {
        return jdbc.query("""
            SELECT id, company_id, user_id
            FROM transactions
            WHERE id = ?
            """, (rs, rowNum) -> new TransactionLinkTarget(
                rs.getLong("id"),
                rs.getLong("company_id"),
                rs.getLong("user_id")
            ), transactionId).stream().findFirst();
    }
}
