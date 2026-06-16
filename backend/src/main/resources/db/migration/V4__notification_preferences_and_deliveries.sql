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
);

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
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_notification_deliveries_unique_channel
    ON notification_deliveries(notification_id, user_id, channel);
CREATE INDEX IF NOT EXISTS idx_notification_deliveries_status_next
    ON notification_deliveries(status, next_attempt_at, id);
CREATE INDEX IF NOT EXISTS idx_notification_deliveries_user_created
    ON notification_deliveries(user_id, created_at DESC, id DESC);
