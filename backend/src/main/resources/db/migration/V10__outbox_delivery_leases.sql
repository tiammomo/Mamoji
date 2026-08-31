ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS lock_token TEXT;
