ALTER TABLE notification_deliveries
    ADD COLUMN IF NOT EXISTS lock_token TEXT;

COMMENT ON COLUMN notification_deliveries.lock_token IS
    'Unique claim token required to fence terminal writes from stale webhook delivery workers.';
