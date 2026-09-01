DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM ledgers ledger
        WHERE ledger.company_id IS NULL
           OR NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = ledger.company_id)
           OR NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = ledger.owner_id)
           OR NOT EXISTS (
               SELECT 1
               FROM company_memberships membership
               WHERE membership.company_id = ledger.company_id
                 AND membership.user_id = ledger.owner_id
           )
    ) THEN
        RAISE EXCEPTION 'ledgers contains an unscoped or orphaned company owner';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ledgers
        WHERE BTRIM(name) = ''
           OR LENGTH(BTRIM(name)) > 120
           OR LENGTH(BTRIM(description)) > 500
           OR UPPER(BTRIM(currency)) !~ '^[A-Z]{3}$'
           OR is_default NOT IN (0, 1)
           OR status NOT IN (0, 1)
    ) THEN
        RAISE EXCEPTION 'ledgers contains invalid descriptive or status attributes';
    END IF;

    IF EXISTS (
        SELECT company_id
        FROM ledgers
        WHERE is_default = 1
        GROUP BY company_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'ledgers contains more than one default ledger for a company';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ledgers
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'ledgers contains an invalid lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ledgers
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'ledgers contains a lifecycle timestamp before creation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ledger_members member
        LEFT JOIN ledgers ledger ON ledger.id = member.ledger_id
        WHERE ledger.id IS NULL
           OR NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = member.user_id)
           OR NOT EXISTS (
               SELECT 1
               FROM company_memberships membership
               WHERE membership.company_id = ledger.company_id
                 AND membership.user_id = member.user_id
           )
    ) THEN
        RAISE EXCEPTION 'ledger_members contains an orphaned or cross-company member';
    END IF;

    IF EXISTS (
        SELECT ledger_id, user_id
        FROM ledger_members
        GROUP BY ledger_id, user_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'ledger_members contains duplicate ledger membership';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ledger_members member
        JOIN ledgers ledger ON ledger.id = member.ledger_id
        WHERE LOWER(BTRIM(member.role)) NOT IN ('owner', 'admin', 'editor', 'viewer')
           OR LENGTH(COALESCE(BTRIM(member.nickname), '')) > 120
           OR LENGTH(COALESCE(BTRIM(member.avatar), '')) > 512
           OR (member.user_id = ledger.owner_id AND LOWER(BTRIM(member.role)) <> 'owner')
           OR (member.user_id <> ledger.owner_id AND LOWER(BTRIM(member.role)) = 'owner')
    ) THEN
        RAISE EXCEPTION 'ledger_members contains invalid role or profile attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ledgers ledger
        WHERE NOT EXISTS (
            SELECT 1
            FROM ledger_members member
            WHERE member.ledger_id = ledger.id
              AND member.user_id = ledger.owner_id
              AND LOWER(BTRIM(member.role)) = 'owner'
        )
    ) THEN
        RAISE EXCEPTION 'ledgers contains an owner without owner membership';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ledger_members
        WHERE NOT pg_input_is_valid(BTRIM(joined_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'ledger_members contains an invalid joined timestamp';
    END IF;
END $$;

UPDATE ledgers
SET name = BTRIM(name),
    description = BTRIM(description),
    currency = UPPER(BTRIM(currency)),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

UPDATE ledger_members
SET role = LOWER(BTRIM(role)),
    nickname = NULLIF(BTRIM(nickname), ''),
    avatar = NULLIF(BTRIM(avatar), ''),
    joined_at = BTRIM(joined_at);

ALTER TABLE ledger_members ADD COLUMN company_id BIGINT;

UPDATE ledger_members member
SET company_id = ledger.company_id
FROM ledgers ledger
WHERE ledger.id = member.ledger_id;

ALTER TABLE ledgers
    ALTER COLUMN name TYPE VARCHAR(120),
    ALTER COLUMN description TYPE VARCHAR(500),
    ALTER COLUMN currency TYPE VARCHAR(3),
    ALTER COLUMN is_default TYPE BOOLEAN USING is_default = 1,
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ,
    ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE ledger_members
    ALTER COLUMN company_id SET NOT NULL,
    ALTER COLUMN role TYPE VARCHAR(20),
    ALTER COLUMN nickname TYPE VARCHAR(120),
    ALTER COLUMN avatar TYPE VARCHAR(512),
    ALTER COLUMN joined_at TYPE TIMESTAMPTZ USING joined_at::TIMESTAMPTZ;

ALTER TABLE ledgers VALIDATE CONSTRAINT fk_ledgers_company;

DROP INDEX idx_ledger_members_ledger_user;

ALTER TABLE ledgers
    ADD CONSTRAINT fk_ledgers_owner
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_ledgers_company_owner
        FOREIGN KEY (company_id, owner_id)
        REFERENCES company_memberships(company_id, user_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_ledgers_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_ledgers_name
        CHECK (name <> ''),
    ADD CONSTRAINT ck_ledgers_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_ledgers_status
        CHECK (status IN (0, 1)),
    ADD CONSTRAINT ck_ledgers_lifecycle
        CHECK (updated_at >= created_at);

CREATE UNIQUE INDEX uq_ledgers_company_default
    ON ledgers(company_id) WHERE is_default;

ALTER TABLE ledger_members
    ADD CONSTRAINT uq_ledger_members_ledger_user
        UNIQUE (ledger_id, user_id),
    ADD CONSTRAINT fk_ledger_members_company
        FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_ledger_members_ledger
        FOREIGN KEY (ledger_id) REFERENCES ledgers(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_ledger_members_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_ledger_members_company_ledger
        FOREIGN KEY (company_id, ledger_id)
        REFERENCES ledgers(company_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_ledger_members_company_user
        FOREIGN KEY (company_id, user_id)
        REFERENCES company_memberships(company_id, user_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_ledger_members_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_ledger_members_role
        CHECK (role IN ('owner', 'admin', 'editor', 'viewer'));

CREATE FUNCTION enforce_ledger_member_owner_invariant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    subject_ledger_id BIGINT;
    expected_owner_id BIGINT;
BEGIN
    subject_ledger_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.ledger_id ELSE NEW.ledger_id END;
    SELECT owner_id
    INTO expected_owner_id
    FROM ledgers
    WHERE id = subject_ledger_id;

    IF NOT FOUND THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP <> 'DELETE' AND (
        (NEW.user_id = expected_owner_id AND NEW.role <> 'owner')
        OR (NEW.user_id <> expected_owner_id AND NEW.role = 'owner')
    ) THEN
        RAISE EXCEPTION 'ledger owner role must match the ledger owner';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM ledger_members member
        WHERE member.ledger_id = subject_ledger_id
          AND member.user_id = expected_owner_id
          AND member.role = 'owner'
    ) THEN
        RAISE EXCEPTION 'ledger owner membership cannot be removed';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_member_owner_invariant
AFTER INSERT OR UPDATE OR DELETE ON ledger_members
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW
EXECUTE FUNCTION enforce_ledger_member_owner_invariant();

CREATE FUNCTION prevent_ledger_owner_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.owner_id <> OLD.owner_id THEN
        RAISE EXCEPTION 'ledger owner cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_owner_immutable
BEFORE UPDATE OF owner_id ON ledgers
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_owner_change();

COMMENT ON TABLE ledgers IS
    'Authoritative company-scoped finance ledgers; at most one default ledger exists per company.';
COMMENT ON TABLE ledger_members IS
    'Authoritative company-scoped ledger access; process-local compatibility caches are not permitted.';
