CREATE TABLE login_failure_states (
    subject_key VARCHAR(64) PRIMARY KEY,
    subject_type VARCHAR(16) NOT NULL,
    failed_attempts INTEGER NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_login_failure_subject_key CHECK (subject_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_login_failure_subject_type CHECK (subject_type IN ('email', 'source')),
    CONSTRAINT ck_login_failure_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT ck_login_failure_lock_time CHECK (locked_until IS NULL OR locked_until >= window_started_at)
);

CREATE INDEX idx_login_failure_states_cleanup
    ON login_failure_states(updated_at)
    WHERE locked_until IS NULL;

CREATE INDEX idx_login_failure_states_locked
    ON login_failure_states(locked_until)
    WHERE locked_until IS NOT NULL;

COMMENT ON TABLE login_failure_states IS
    'Durable cross-instance login throttling keyed by pseudonymous email and source digests.';
