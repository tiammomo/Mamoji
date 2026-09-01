DELETE FROM registration_invites
WHERE NOT pg_input_is_valid(created_at, 'timestamp with time zone')
   OR NOT pg_input_is_valid(updated_at, 'timestamp with time zone')
   OR NOT pg_input_is_valid(expires_at, 'timestamp with time zone')
   OR (accepted_at IS NOT NULL AND NOT pg_input_is_valid(accepted_at, 'timestamp with time zone'))
   OR LENGTH(BTRIM(email)) > 320
   OR POSITION('@' IN BTRIM(email)) <= 1
   OR role NOT IN (1, 2)
   OR permissions NOT BETWEEN 1 AND 15;

DELETE FROM registration_invites
WHERE expires_at::TIMESTAMPTZ <= created_at::TIMESTAMPTZ
   OR updated_at::TIMESTAMPTZ < created_at::TIMESTAMPTZ
   OR (accepted_at IS NOT NULL AND (
        accepted_at::TIMESTAMPTZ < created_at::TIMESTAMPTZ
        OR accepted_at::TIMESTAMPTZ > expires_at::TIMESTAMPTZ
   ));

UPDATE registration_invites
SET email = LOWER(BTRIM(email));

ALTER TABLE registration_invites
    ALTER COLUMN invited_by_user_id DROP NOT NULL;

UPDATE registration_invites invitation
SET accepted_user_id = NULL
WHERE accepted_user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM users WHERE users.id = invitation.accepted_user_id);

UPDATE registration_invites invitation
SET invited_by_user_id = NULL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE users.id = invitation.invited_by_user_id);

UPDATE registration_invites
SET token = 'sha256:' || RTRIM(
    TRANSLATE(ENCODE(SHA256(CONVERT_TO(token, 'UTF8')), 'base64'), '+/', '-_'),
    '='
)
WHERE token !~ '^sha256:[A-Za-z0-9_-]{43}$';

ALTER TABLE registration_invites
    ALTER COLUMN token TYPE VARCHAR(50),
    ALTER COLUMN email TYPE VARCHAR(320),
    ALTER COLUMN expires_at TYPE TIMESTAMPTZ USING expires_at::TIMESTAMPTZ,
    ALTER COLUMN accepted_at TYPE TIMESTAMPTZ USING accepted_at::TIMESTAMPTZ,
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ;

ALTER TABLE registration_invites
    ADD CONSTRAINT fk_registration_invites_accepted_user
        FOREIGN KEY (accepted_user_id) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_registration_invites_inviter
        FOREIGN KEY (invited_by_user_id) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_registration_invites_digest
        CHECK (token ~ '^sha256:[A-Za-z0-9_-]{43}$'),
    ADD CONSTRAINT ck_registration_invites_email
        CHECK (email = LOWER(BTRIM(email)) AND POSITION('@' IN email) > 1),
    ADD CONSTRAINT ck_registration_invites_role
        CHECK (role IN (1, 2)),
    ADD CONSTRAINT ck_registration_invites_permissions
        CHECK (permissions BETWEEN 1 AND 15),
    ADD CONSTRAINT ck_registration_invites_expiry
        CHECK (expires_at > created_at),
    ADD CONSTRAINT ck_registration_invites_acceptance
        CHECK (
            (accepted_user_id IS NULL OR accepted_at IS NOT NULL)
            AND (accepted_at IS NULL OR (accepted_at >= created_at AND accepted_at <= expires_at))
        );

COMMENT ON TABLE registration_invites IS
    'Registration invitations storing only SHA-256 token digests; raw credentials are disclosed once.';
