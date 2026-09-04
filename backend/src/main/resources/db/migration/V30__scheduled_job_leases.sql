CREATE TABLE scheduled_job_leases (
    job_name VARCHAR(120) PRIMARY KEY,
    lock_token VARCHAR(64),
    locked_until TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ NOT NULL,
    last_started_at TIMESTAMPTZ NOT NULL,
    last_completed_at TIMESTAMPTZ,
    last_failed_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_scheduled_job_leases_name CHECK (BTRIM(job_name) <> ''),
    CONSTRAINT ck_scheduled_job_leases_lease CHECK (
        (lock_token IS NULL AND locked_until IS NULL)
        OR (
            lock_token IS NOT NULL
            AND locked_until IS NOT NULL
            AND locked_until > last_started_at
        )
    ),
    CONSTRAINT ck_scheduled_job_leases_lifecycle CHECK (created_at <= updated_at)
);

COMMENT ON TABLE scheduled_job_leases IS
    'Database-clock leases and cadence checkpoints for cluster-safe scheduled jobs.';

COMMENT ON COLUMN scheduled_job_leases.lock_token IS
    'Unique claim token required to fence completion and failure writes from stale workers.';
