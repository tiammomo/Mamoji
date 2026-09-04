package com.mamoji.platform.scheduling.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomically claims and fences scheduled work using the PostgreSQL clock. */
@Repository
public class ScheduledJobLeaseRepository {
    private final JdbcTemplate jdbc;

    public ScheduledJobLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> tryAcquire(String jobName, long leaseMillis) {
        String normalizedName = requireJobName(jobName);
        String lockToken = UUID.randomUUID().toString();
        List<String> claimedTokens = jdbc.query("""
            INSERT INTO scheduled_job_leases (
                job_name, lock_token, locked_until, next_run_at, last_started_at,
                last_completed_at, last_failed_at, last_error, created_at, updated_at
            ) VALUES (
                ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (job_name) DO UPDATE SET
                lock_token = excluded.lock_token,
                locked_until = excluded.locked_until,
                last_started_at = excluded.last_started_at,
                updated_at = excluded.updated_at
            WHERE scheduled_job_leases.next_run_at <= CURRENT_TIMESTAMP
              AND (
                  scheduled_job_leases.lock_token IS NULL
                  OR scheduled_job_leases.locked_until <= CURRENT_TIMESTAMP
              )
            RETURNING lock_token
            """, (rs, rowNum) -> rs.getString("lock_token"), normalizedName, lockToken, positive(leaseMillis));
        return claimedTokens.stream().findFirst();
    }

    public boolean markCompleted(String jobName, String lockToken, long cadenceMillis) {
        return jdbc.update("""
            UPDATE scheduled_job_leases
            SET lock_token = NULL,
                locked_until = NULL,
                next_run_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                last_completed_at = CURRENT_TIMESTAMP,
                last_error = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE job_name = ? AND lock_token = ?
            """, positive(cadenceMillis), requireJobName(jobName), requireLockToken(lockToken)) == 1;
    }

    public boolean markFailed(String jobName, String lockToken, long retryMillis, String lastError) {
        return jdbc.update("""
            UPDATE scheduled_job_leases
            SET lock_token = NULL,
                locked_until = NULL,
                next_run_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                last_failed_at = CURRENT_TIMESTAMP,
                last_error = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE job_name = ? AND lock_token = ?
            """,
            positive(retryMillis),
            lastError,
            requireJobName(jobName),
            requireLockToken(lockToken)
        ) == 1;
    }

    private String requireJobName(String jobName) {
        String normalized = jobName == null ? "" : jobName.strip();
        if (normalized.isEmpty() || normalized.length() > 120) {
            throw new IllegalArgumentException("Scheduled job name must contain 1 to 120 characters");
        }
        return normalized;
    }

    private String requireLockToken(String lockToken) {
        if (lockToken == null || lockToken.isBlank()) {
            throw new IllegalArgumentException("Scheduled job lock token is required");
        }
        return lockToken;
    }

    private long positive(long milliseconds) {
        return Math.max(1, milliseconds);
    }
}
