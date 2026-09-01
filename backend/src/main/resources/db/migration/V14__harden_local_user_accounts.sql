DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users
        WHERE LENGTH(BTRIM(email)) > 320
           OR POSITION('@' IN BTRIM(email)) <= 1
           OR BTRIM(email) ~ '[[:space:]]'
    ) THEN
        RAISE EXCEPTION 'users contains an invalid email address';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM users
        GROUP BY LOWER(BTRIM(email))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'users contains duplicate normalized email addresses';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM users
        WHERE role NOT IN (1, 2)
           OR permissions NOT BETWEEN 0 AND 15
           OR BTRIM(password_hash) = ''
    ) THEN
        RAISE EXCEPTION 'users contains invalid access or credential data';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM users
        WHERE NOT pg_input_is_valid(created_at, 'timestamp with time zone')
           OR NOT pg_input_is_valid(updated_at, 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'users contains an invalid timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM users
        WHERE updated_at::TIMESTAMPTZ < created_at::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'users contains an invalid timestamp order';
    END IF;
END $$;

UPDATE users
SET email = LOWER(BTRIM(email));

ALTER TABLE users
    ALTER COLUMN email TYPE VARCHAR(320),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ;

ALTER TABLE users
    ADD CONSTRAINT ck_users_email
        CHECK (
            email = LOWER(BTRIM(email))
            AND POSITION('@' IN email) > 1
            AND email !~ '[[:space:]]'
        ),
    ADD CONSTRAINT ck_users_role
        CHECK (role IN (1, 2)),
    ADD CONSTRAINT ck_users_permissions
        CHECK (permissions BETWEEN 0 AND 15),
    ADD CONSTRAINT ck_users_password_hash
        CHECK (BTRIM(password_hash) <> ''),
    ADD CONSTRAINT ck_users_timestamps
        CHECK (updated_at >= created_at);

COMMENT ON TABLE users IS
    'Authoritative local user accounts owned by Platform Identity; runtime caches are not permitted.';
