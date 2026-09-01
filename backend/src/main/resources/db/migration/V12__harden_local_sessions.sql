DELETE FROM auth_tokens
WHERE token !~ '^sha256:[A-Za-z0-9_-]{43}$'
   OR NOT EXISTS (SELECT 1 FROM users WHERE users.id = auth_tokens.user_id)
   OR NOT pg_input_is_valid(created_at, 'timestamp with time zone')
   OR NOT pg_input_is_valid(expires_at, 'timestamp with time zone');

DELETE FROM auth_tokens
WHERE expires_at::TIMESTAMPTZ <= created_at::TIMESTAMPTZ;

ALTER TABLE auth_tokens
    ALTER COLUMN token TYPE VARCHAR(50),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN expires_at TYPE TIMESTAMPTZ USING expires_at::TIMESTAMPTZ;

ALTER TABLE auth_tokens
    ADD CONSTRAINT fk_auth_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_auth_tokens_digest
        CHECK (token ~ '^sha256:[A-Za-z0-9_-]{43}$'),
    ADD CONSTRAINT ck_auth_tokens_expiry
        CHECK (expires_at > created_at);

COMMENT ON TABLE auth_tokens IS
    'PostgreSQL-backed local sessions storing only SHA-256 bearer-token digests.';
