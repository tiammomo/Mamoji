package com.mamoji.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.domain.Models.OutboxEvent;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.OutboxEventHandler;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxEventService {
    private static final Logger log = LoggerFactory.getLogger(OutboxEventService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final OutboxEventHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final boolean consumerEnabled;
    private final int batchSize;
    private final int maxAttempts;
    private final int staleLockMinutes;

    public OutboxEventService(
        JdbcTemplate jdbc,
        TransactionTemplate transactionTemplate,
        OutboxEventHandler handler,
        @Value("${mamoji.outbox.enabled:true}") boolean enabled,
        @Value("${mamoji.outbox.consumer.enabled:true}") boolean consumerEnabled,
        @Value("${mamoji.outbox.consumer.batch-size:20}") int batchSize,
        @Value("${mamoji.outbox.consumer.max-attempts:8}") int maxAttempts,
        @Value("${mamoji.outbox.consumer.stale-lock-minutes:10}") int staleLockMinutes
    ) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.handler = handler;
        this.enabled = enabled;
        this.consumerEnabled = consumerEnabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.staleLockMinutes = Math.max(1, staleLockMinutes);
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS outbox_events (
                id BIGSERIAL PRIMARY KEY,
                event_id TEXT NOT NULL UNIQUE,
                event_type TEXT NOT NULL,
                aggregate_type TEXT NOT NULL,
                aggregate_id BIGINT NOT NULL,
                company_id BIGINT NOT NULL DEFAULT 0,
                actor_user_id BIGINT NOT NULL DEFAULT 0,
                payload_json TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at TEXT,
                locked_at TEXT,
                processed_at TEXT,
                last_error TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_outbox_events_status_next ON outbox_events(status, next_attempt_at, id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id, id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_outbox_events_created ON outbox_events(created_at, id)");
    }

    public void publish(
        String eventType,
        long companyId,
        String aggregateType,
        long aggregateId,
        long actorUserId,
        Map<String, Object> payload
    ) {
        if (!enabled) {
            return;
        }
        String now = InMemoryStore.now();
        jdbc.update("""
            INSERT INTO outbox_events (
                event_id, event_type, aggregate_type, aggregate_id, company_id, actor_user_id,
                payload_json, status, attempts, next_attempt_at, locked_at, processed_at, last_error, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', 0, NULL, NULL, NULL, NULL, ?, ?)
            """,
            UUID.randomUUID().toString(),
            normalize(eventType, "unknown.event"),
            normalize(aggregateType, "unknown"),
            aggregateId,
            companyId,
            actorUserId,
            payloadJson(payload),
            now,
            now
        );
    }

    @Scheduled(fixedDelayString = "${mamoji.outbox.consumer.fixed-delay-ms:5000}")
    public void dispatchDueEvents() {
        if (!enabled || !consumerEnabled) {
            return;
        }
        List<OutboxEvent> events = claimDueEvents();
        for (OutboxEvent event : events) {
            try {
                handler.handle(event);
                markProcessed(event.id);
            } catch (RuntimeException ex) {
                markFailed(event, ex);
            }
        }
    }

    private List<OutboxEvent> claimDueEvents() {
        return transactionTemplate.execute(status -> {
            recoverStaleProcessingEvents();
            String now = InMemoryStore.now();
            List<OutboxEvent> events = jdbc.query("""
                SELECT * FROM outbox_events
                WHERE status IN ('pending', 'failed')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, this::mapEvent, now, batchSize);
            for (OutboxEvent event : events) {
                event.status = "processing";
                event.attempts = event.attempts + 1;
                event.lockedAt = now;
                event.updatedAt = now;
                jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'processing', attempts = ?, locked_at = ?, updated_at = ?
                    WHERE id = ?
                    """, event.attempts, event.lockedAt, event.updatedAt, event.id);
            }
            return events;
        });
    }

    private void recoverStaleProcessingEvents() {
        String now = InMemoryStore.now();
        String staleBefore = OffsetDateTime.now().minusMinutes(staleLockMinutes).toString();
        int recovered = jdbc.update("""
            UPDATE outbox_events
            SET status = 'failed', next_attempt_at = ?, locked_at = NULL, updated_at = ?,
                last_error = 'Recovered stale processing lock'
            WHERE status = 'processing'
              AND locked_at IS NOT NULL
              AND locked_at < ?
            """, now, now, staleBefore);
        if (recovered > 0) {
            log.warn("Recovered {} stale outbox events", recovered);
        }
    }

    private void markProcessed(long id) {
        String now = InMemoryStore.now();
        jdbc.update("""
            UPDATE outbox_events
            SET status = 'processed', processed_at = ?, locked_at = NULL, next_attempt_at = NULL,
                last_error = NULL, updated_at = ?
            WHERE id = ?
            """, now, now, id);
    }

    private void markFailed(OutboxEvent event, RuntimeException ex) {
        String now = InMemoryStore.now();
        boolean exhausted = event.attempts >= maxAttempts;
        String nextAttemptAt = exhausted ? null : OffsetDateTime.now().plusSeconds(backoffSeconds(event.attempts)).toString();
        String status = exhausted ? "dead" : "failed";
        jdbc.update("""
            UPDATE outbox_events
            SET status = ?, next_attempt_at = ?, locked_at = NULL, last_error = ?, updated_at = ?
            WHERE id = ?
            """, status, nextAttemptAt, truncate(errorMessage(ex), 1000), now, event.id);
        if (exhausted) {
            log.error("Outbox event id={} type={} moved to dead after {} attempts", event.id, event.eventType, event.attempts, ex);
        } else {
            log.warn("Outbox event id={} type={} failed, will retry at {}", event.id, event.eventType, nextAttemptAt, ex);
        }
    }

    private long backoffSeconds(int attempts) {
        int exponent = Math.min(8, Math.max(0, attempts - 1));
        return Math.min(3600, 30L * (1L << exponent));
    }

    private OutboxEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        OutboxEvent event = new OutboxEvent();
        event.id = rs.getLong("id");
        event.eventId = rs.getString("event_id");
        event.eventType = rs.getString("event_type");
        event.aggregateType = rs.getString("aggregate_type");
        event.aggregateId = rs.getLong("aggregate_id");
        event.companyId = rs.getLong("company_id");
        event.actorUserId = rs.getLong("actor_user_id");
        event.payloadJson = rs.getString("payload_json");
        event.status = rs.getString("status");
        event.attempts = rs.getInt("attempts");
        event.nextAttemptAt = rs.getString("next_attempt_at");
        event.lockedAt = rs.getString("locked_at");
        event.processedAt = rs.getString("processed_at");
        event.lastError = rs.getString("last_error");
        event.createdAt = rs.getString("created_at");
        event.updatedAt = rs.getString("updated_at");
        return event;
    }

    private String payloadJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Outbox event payload is not serializable", ex);
        }
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String errorMessage(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
