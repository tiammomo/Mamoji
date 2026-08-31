package com.mamoji.notification.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Applies fenced terminal transitions for an actively leased outbox event. */
@Repository
public class OutboxEventStatusRepository {
    private final JdbcTemplate jdbc;

    public OutboxEventStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean markProcessed(long id, String lockToken, String now) {
        return jdbc.update("""
            UPDATE outbox_events
            SET status = 'processed', processed_at = ?, locked_at = NULL, lock_token = NULL,
                next_attempt_at = NULL, last_error = NULL, updated_at = ?
            WHERE id = ? AND status = 'processing' AND lock_token = ?
            """, now, now, id, lockToken) == 1;
    }

    public boolean markFailed(
        long id,
        String lockToken,
        String status,
        String nextAttemptAt,
        String lastError,
        String now
    ) {
        if (!"failed".equals(status) && !"dead".equals(status)) {
            throw new IllegalArgumentException("Outbox failure transition must target failed or dead status");
        }
        return jdbc.update("""
            UPDATE outbox_events
            SET status = ?, next_attempt_at = ?, locked_at = NULL, lock_token = NULL,
                last_error = ?, updated_at = ?
            WHERE id = ? AND status = 'processing' AND lock_token = ?
            """, status, nextAttemptAt, lastError, now, id, lockToken) == 1;
    }
}
