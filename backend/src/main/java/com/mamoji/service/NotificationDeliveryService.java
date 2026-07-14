package com.mamoji.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.WebhookUrlValidator;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookUrlValidator webhookUrlValidator;
    private final OkHttpClient client;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final int staleLockMinutes;

    public NotificationDeliveryService(
        JdbcTemplate jdbc,
        TransactionTemplate transactionTemplate,
        WebhookUrlValidator webhookUrlValidator,
        @Value("${mamoji.notifications.delivery.enabled:true}") boolean enabled,
        @Value("${mamoji.notifications.delivery.batch-size:20}") int batchSize,
        @Value("${mamoji.notifications.delivery.max-attempts:6}") int maxAttempts,
        @Value("${mamoji.notifications.delivery.stale-lock-minutes:10}") int staleLockMinutes,
        @Value("${mamoji.notifications.delivery.timeout-seconds:5}") int timeoutSeconds
    ) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.webhookUrlValidator = webhookUrlValidator;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.staleLockMinutes = Math.max(1, staleLockMinutes);
        int timeout = Math.max(2, timeoutSeconds);
        this.client = new OkHttpClient.Builder()
            .dns(webhookUrlValidator.validatingDns())
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .callTimeout(timeout + 2L, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS notification_preferences (
                user_id BIGINT PRIMARY KEY,
                enabled BOOLEAN NOT NULL DEFAULT true,
                webhook_enabled BOOLEAN NOT NULL DEFAULT false,
                webhook_provider TEXT NOT NULL DEFAULT 'generic',
                webhook_url TEXT,
                min_severity TEXT NOT NULL DEFAULT 'info',
                muted_types TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS notification_deliveries (
                id BIGSERIAL PRIMARY KEY,
                notification_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                channel TEXT NOT NULL,
                provider TEXT NOT NULL DEFAULT 'generic',
                status TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at TEXT,
                locked_at TEXT,
                delivered_at TEXT,
                last_error TEXT,
                response_status INTEGER,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_notification_deliveries_unique_channel
            ON notification_deliveries(notification_id, user_id, channel)
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_notification_deliveries_status_next ON notification_deliveries(status, next_attempt_at, id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_notification_deliveries_user_created ON notification_deliveries(user_id, created_at DESC, id DESC)");
    }

    public void enqueueWebhook(long notificationId, long userId, String provider) {
        if (!enabled) {
            return;
        }
        String now = InMemoryStore.now();
        jdbc.update("""
            INSERT INTO notification_deliveries (
                notification_id, user_id, channel, provider, status, attempts, next_attempt_at,
                locked_at, delivered_at, last_error, response_status, created_at, updated_at
            ) VALUES (?, ?, 'webhook', ?, 'pending', 0, NULL, NULL, NULL, NULL, NULL, ?, ?)
            ON CONFLICT DO NOTHING
            """, notificationId, userId, normalizeProvider(provider), now, now);
    }

    public void sendTestWebhook(NotificationService.NotificationPreference preference) {
        WebhookTarget target = requireTarget(preference.userId());
        NotificationPayload payload = new NotificationPayload(
            0,
            preference.userId(),
            0,
            "system",
            "info",
            "Mamoji 通知测试",
            "这是一条测试消息，用于确认外部通知 Webhook 可以正常接收。",
            "/settings",
            InMemoryStore.now()
        );
        postWebhook(target, payload);
    }

    @Scheduled(fixedDelayString = "${mamoji.notifications.delivery.fixed-delay-ms:10000}")
    public void dispatchDueDeliveries() {
        if (!enabled) {
            return;
        }
        List<DeliveryTask> tasks = claimDueDeliveries();
        for (DeliveryTask task : tasks) {
            try {
                WebhookTarget target = requireTarget(task.userId());
                NotificationPayload payload = notificationPayload(task.notificationId());
                postWebhook(target, payload);
                markDelivered(task.id());
            } catch (RuntimeException ex) {
                markFailed(task, ex);
            }
        }
    }

    private List<DeliveryTask> claimDueDeliveries() {
        return transactionTemplate.execute(status -> {
            recoverStaleDeliveries();
            String now = InMemoryStore.now();
            List<DeliveryTask> tasks = jdbc.query("""
                SELECT * FROM notification_deliveries
                WHERE status IN ('pending', 'failed')
                  AND channel = 'webhook'
                  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, this::mapDeliveryTask, now, batchSize);
            for (DeliveryTask task : tasks) {
                jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = 'processing', attempts = ?, locked_at = ?, updated_at = ?
                    WHERE id = ?
                    """, task.attempts() + 1, now, now, task.id());
            }
            return tasks.stream()
                .map(task -> new DeliveryTask(task.id(), task.notificationId(), task.userId(), task.channel(), task.provider(), task.attempts() + 1))
                .toList();
        });
    }

    private void recoverStaleDeliveries() {
        String now = InMemoryStore.now();
        String staleBefore = OffsetDateTime.now().minusMinutes(staleLockMinutes).toString();
        int recovered = jdbc.update("""
            UPDATE notification_deliveries
            SET status = 'failed', next_attempt_at = ?, locked_at = NULL, updated_at = ?,
                last_error = 'Recovered stale delivery lock'
            WHERE status = 'processing'
              AND locked_at IS NOT NULL
              AND locked_at < ?
            """, now, now, staleBefore);
        if (recovered > 0) {
            log.warn("Recovered {} stale notification deliveries", recovered);
        }
    }

    private void markDelivered(long id) {
        String now = InMemoryStore.now();
        jdbc.update("""
            UPDATE notification_deliveries
            SET status = 'delivered', delivered_at = ?, next_attempt_at = NULL, locked_at = NULL,
                last_error = NULL, response_status = 200, updated_at = ?
            WHERE id = ?
            """, now, now, id);
    }

    private void markFailed(DeliveryTask task, RuntimeException ex) {
        String now = InMemoryStore.now();
        boolean exhausted = task.attempts() >= maxAttempts;
        String nextAttemptAt = exhausted ? null : OffsetDateTime.now().plusSeconds(backoffSeconds(task.attempts())).toString();
        String status = exhausted ? "dead" : "failed";
        jdbc.update("""
            UPDATE notification_deliveries
            SET status = ?, next_attempt_at = ?, locked_at = NULL, last_error = ?, updated_at = ?
            WHERE id = ?
            """, status, nextAttemptAt, truncate(errorMessage(ex), 1000), now, task.id());
        if (exhausted) {
            log.error("Notification delivery id={} moved to dead after {} attempts", task.id(), task.attempts(), ex);
        } else {
            log.warn("Notification delivery id={} failed, will retry at {}", task.id(), nextAttemptAt, ex);
        }
    }

    private NotificationPayload notificationPayload(long notificationId) {
        List<NotificationPayload> payloads = jdbc.query("""
            SELECT id, user_id, company_id, type, severity, title, content, target_url, created_at
            FROM notifications
            WHERE id = ?
            """, (rs, rowNum) -> new NotificationPayload(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("company_id"),
            rs.getString("type"),
            rs.getString("severity"),
            rs.getString("title"),
            rs.getString("content"),
            rs.getString("target_url"),
            rs.getString("created_at")
        ), notificationId);
        if (payloads.isEmpty()) {
            throw new IllegalStateException("Notification not found: " + notificationId);
        }
        return payloads.get(0);
    }

    private WebhookTarget requireTarget(long userId) {
        List<WebhookTarget> targets = jdbc.query("""
            SELECT user_id, webhook_enabled, webhook_provider, webhook_url
            FROM notification_preferences
            WHERE user_id = ?
            """, (rs, rowNum) -> new WebhookTarget(
            rs.getLong("user_id"),
            rs.getBoolean("webhook_enabled"),
            normalizeProvider(rs.getString("webhook_provider")),
            rs.getString("webhook_url")
        ), userId);
        if (targets.isEmpty() || !targets.get(0).enabled() || isBlank(targets.get(0).url())) {
            throw new IllegalStateException("Webhook is not configured for user " + userId);
        }
        String url = targets.get(0).url().strip();
        try {
            url = webhookUrlValidator.requireSafeUrl(url).toASCIIString();
        } catch (WebhookUrlValidator.UnsafeWebhookUrlException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
        return new WebhookTarget(userId, true, targets.get(0).provider(), url);
    }

    private void postWebhook(WebhookTarget target, NotificationPayload payload) {
        String body = webhookBody(target.provider(), payload);
        Request request = new Request.Builder()
            .url(target.url())
            .post(RequestBody.create(body, JSON))
            .header("Content-Type", "application/json")
            .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Webhook HTTP " + response.code());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Webhook delivery failed: " + ex.getMessage(), ex);
        }
    }

    private String webhookBody(String provider, NotificationPayload payload) {
        String text = "[Mamoji] " + payload.title() + "\n" + payload.content();
        if (!isBlank(payload.targetUrl())) {
            text += "\n" + payload.targetUrl();
        }
        Map<String, Object> body = switch (provider) {
            case "feishu" -> Map.of(
                "msg_type", "text",
                "content", Map.of("text", text)
            );
            case "wecom" -> Map.of(
                "msgtype", "text",
                "text", Map.of("content", text)
            );
            default -> Map.of(
                "source", "mamoji",
                "id", payload.id(),
                "userId", payload.userId(),
                "companyId", payload.companyId(),
                "type", payload.type(),
                "severity", payload.severity(),
                "title", payload.title(),
                "content", payload.content(),
                "targetUrl", payload.targetUrl() == null ? "" : payload.targetUrl(),
                "createdAt", payload.createdAt()
            );
        };
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Webhook payload serialization failed", ex);
        }
    }

    private DeliveryTask mapDeliveryTask(ResultSet rs, int rowNum) throws SQLException {
        return new DeliveryTask(
            rs.getLong("id"),
            rs.getLong("notification_id"),
            rs.getLong("user_id"),
            rs.getString("channel"),
            rs.getString("provider"),
            rs.getInt("attempts")
        );
    }

    private long backoffSeconds(int attempts) {
        int exponent = Math.min(8, Math.max(0, attempts - 1));
        return Math.min(1800, 20L * (1L << exponent));
    }

    private String normalizeProvider(String provider) {
        return switch (provider == null ? "" : provider) {
            case "feishu", "wecom" -> provider;
            default -> "generic";
        };
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record DeliveryTask(long id, long notificationId, long userId, String channel, String provider, int attempts) {
    }

    private record WebhookTarget(long userId, boolean enabled, String provider, String url) {
    }

    private record NotificationPayload(
        long id,
        long userId,
        long companyId,
        String type,
        String severity,
        String title,
        String content,
        String targetUrl,
        String createdAt
    ) {
    }
}
