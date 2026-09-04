DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM categories category
        WHERE category.company_id IS NULL
           OR NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = category.company_id)
           OR NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = category.user_id)
           OR NOT EXISTS (
               SELECT 1
               FROM company_memberships membership
               WHERE membership.company_id = category.company_id
                 AND membership.user_id = category.user_id
           )
    ) THEN
        RAISE EXCEPTION 'categories contains an unscoped or orphaned company member';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM categories
        WHERE BTRIM(name) = ''
           OR LENGTH(BTRIM(name)) > 120
           OR BTRIM(icon) = ''
           OR LENGTH(BTRIM(icon)) > 32
           OR LOWER(BTRIM(color)) !~ '^#[0-9a-f]{6}$'
           OR LOWER(BTRIM(type)) NOT IN ('income', 'expense')
           OR status NOT IN (0, 1)
    ) THEN
        RAISE EXCEPTION 'categories contains invalid descriptive, type or status attributes';
    END IF;

    IF EXISTS (
        SELECT company_id, user_id, LOWER(BTRIM(type)), BTRIM(name)
        FROM categories
        GROUP BY company_id, user_id, LOWER(BTRIM(type)), BTRIM(name)
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'categories contains duplicate normalized names in a personal company scope';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM categories
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'categories contains an invalid lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM categories
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'categories contains a lifecycle timestamp before creation';
    END IF;
END $$;

UPDATE categories
SET name = BTRIM(name),
    icon = BTRIM(icon),
    color = LOWER(BTRIM(color)),
    type = LOWER(BTRIM(type)),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE categories
    ALTER COLUMN name TYPE VARCHAR(120),
    ALTER COLUMN icon TYPE VARCHAR(32),
    ALTER COLUMN color TYPE VARCHAR(7),
    ALTER COLUMN type TYPE VARCHAR(10),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ,
    ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE categories VALIDATE CONSTRAINT fk_categories_company;

ALTER TABLE categories
    ADD CONSTRAINT uq_categories_company_user_type_name
        UNIQUE (company_id, user_id, type, name),
    ADD CONSTRAINT fk_categories_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_categories_company_user
        FOREIGN KEY (company_id, user_id)
        REFERENCES company_memberships(company_id, user_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_categories_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_categories_name
        CHECK (name = BTRIM(name) AND name <> ''),
    ADD CONSTRAINT ck_categories_icon
        CHECK (icon = BTRIM(icon) AND icon <> ''),
    ADD CONSTRAINT ck_categories_color
        CHECK (color ~ '^#[0-9a-f]{6}$'),
    ADD CONSTRAINT ck_categories_type
        CHECK (type IN ('income', 'expense')),
    ADD CONSTRAINT ck_categories_status
        CHECK (status IN (0, 1)),
    ADD CONSTRAINT ck_categories_lifecycle
        CHECK (updated_at >= created_at);

CREATE FUNCTION prevent_category_scope_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.company_id <> OLD.company_id OR NEW.user_id <> OLD.user_id THEN
        RAISE EXCEPTION 'category company and owner cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_category_scope_immutable
BEFORE UPDATE OF company_id, user_id ON categories
FOR EACH ROW
EXECUTE FUNCTION prevent_category_scope_change();

CREATE FUNCTION prevent_referenced_category_type_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.type <> OLD.type AND (
        EXISTS (SELECT 1 FROM transactions transaction_record WHERE transaction_record.category_id = OLD.id)
        OR EXISTS (SELECT 1 FROM budgets budget WHERE budget.category_id = OLD.id)
    ) THEN
        RAISE EXCEPTION 'referenced category type cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_referenced_category_type_immutable
BEFORE UPDATE OF type ON categories
FOR EACH ROW
EXECUTE FUNCTION prevent_referenced_category_type_change();

COMMENT ON TABLE categories IS
    'Authoritative company-scoped personal transaction classifications; process-local compatibility caches are not permitted.';
