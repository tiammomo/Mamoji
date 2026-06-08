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
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_status_next ON outbox_events(status, next_attempt_at, id);
CREATE INDEX IF NOT EXISTS idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id, id);
CREATE INDEX IF NOT EXISTS idx_outbox_events_created ON outbox_events(created_at, id);
