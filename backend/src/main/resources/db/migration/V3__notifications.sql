CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL DEFAULT 0,
    type TEXT NOT NULL,
    severity TEXT NOT NULL DEFAULT 'info',
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    target_url TEXT,
    source_type TEXT,
    source_id BIGINT,
    dedupe_key TEXT,
    read_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notifications(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications(user_id, read_at, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_company_created ON notifications(company_id, created_at DESC, id DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_notifications_user_dedupe
    ON notifications(user_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL AND dedupe_key <> '';
