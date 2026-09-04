package com.mamoji.notification.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Applies fenced terminal transitions for an actively leased external notification delivery. */
@Repository
public class NotificationDeliveryStatusRepository {
    private final JdbcTemplate jdbc;

    public NotificationDeliveryStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean markDelivered(long id, String lockToken, String now) {
        return jdbc.update("""
            UPDATE notification_deliveries
            SET status = 'delivered', delivered_at = ?, next_attempt_at = NULL,
                locked_at = NULL, lock_token = NULL, last_error = NULL,
                response_status = 200, updated_at = ?
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
            throw new IllegalArgumentException("Notification delivery failure must target failed or dead status");
        }
        return jdbc.update("""
            UPDATE notification_deliveries
            SET status = ?, next_attempt_at = ?, locked_at = NULL, lock_token = NULL,
                last_error = ?, updated_at = ?
            WHERE id = ? AND status = 'processing' AND lock_token = ?
            """, status, nextAttemptAt, lastError, now, id, lockToken) == 1;
    }
}
