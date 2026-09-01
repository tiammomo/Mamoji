DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM recurring_items
        WHERE LOWER(BTRIM(id)) !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ) THEN
        RAISE EXCEPTION 'recurring_items contains an invalid UUID identifier';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM recurring_items
        GROUP BY LOWER(BTRIM(id))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'recurring_items contains duplicate normalized identifiers';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM recurring_items recurring
        WHERE recurring.company_id IS NULL
           OR NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = recurring.company_id)
           OR NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = recurring.user_id)
    ) THEN
        RAISE EXCEPTION 'recurring_items contains an unscoped or orphaned owner reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM recurring_items
        WHERE BTRIM(name) = ''
           OR LENGTH(BTRIM(name)) > 160
           OR LOWER(BTRIM(frequency)) NOT IN ('daily', 'weekly', 'monthly', 'yearly')
           OR type NOT IN (1, 2)
           OR interval_value NOT BETWEEN 1 AND 3650
           OR (day_of_week IS NOT NULL AND day_of_week NOT BETWEEN 1 AND 7)
           OR (day_of_month IS NOT NULL AND day_of_month NOT BETWEEN 1 AND 31)
           OR (month_of_year IS NOT NULL AND month_of_year NOT BETWEEN 1 AND 12)
           OR status NOT IN (0, 1)
           OR execution_count < 0
           OR LENGTH(note) > 1000
    ) THEN
        RAISE EXCEPTION 'recurring_items contains invalid rule attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM recurring_items
        WHERE NOT pg_input_is_valid(BTRIM(amount), 'numeric')
    ) THEN
        RAISE EXCEPTION 'recurring_items contains an invalid amount';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM recurring_items
        WHERE BTRIM(amount)::NUMERIC <= 0
           OR BTRIM(amount)::NUMERIC > 99999999999999.99
           OR SCALE(BTRIM(amount)::NUMERIC) > 2
    ) THEN
        RAISE EXCEPTION 'recurring_items contains an out-of-range amount';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM recurring_items
        WHERE NOT pg_input_is_valid(start_date, 'date')
           OR NOT pg_input_is_valid(next_execution, 'date')
           OR (end_date IS NOT NULL AND NOT pg_input_is_valid(end_date, 'date'))
           OR (last_executed IS NOT NULL AND NOT pg_input_is_valid(last_executed, 'date'))
    ) THEN
        RAISE EXCEPTION 'recurring_items contains an invalid calendar date';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM recurring_items
        WHERE (end_date IS NOT NULL AND end_date::DATE < start_date::DATE)
           OR next_execution::DATE <= COALESCE(last_executed::DATE, start_date::DATE)
    ) THEN
        RAISE EXCEPTION 'recurring_items contains an invalid calendar cursor';
    END IF;
END $$;

UPDATE recurring_items
SET id = LOWER(BTRIM(id)),
    name = BTRIM(name),
    frequency = LOWER(BTRIM(frequency)),
    amount = BTRIM(amount);

ALTER TABLE recurring_items
    ALTER COLUMN id TYPE VARCHAR(36),
    ALTER COLUMN name TYPE VARCHAR(160),
    ALTER COLUMN amount TYPE NUMERIC(16, 2) USING amount::NUMERIC(16, 2),
    ALTER COLUMN frequency TYPE VARCHAR(16),
    ALTER COLUMN start_date TYPE DATE USING start_date::DATE,
    ALTER COLUMN end_date TYPE DATE USING end_date::DATE,
    ALTER COLUMN last_executed TYPE DATE USING last_executed::DATE,
    ALTER COLUMN next_execution TYPE DATE USING next_execution::DATE,
    ALTER COLUMN note TYPE VARCHAR(1000),
    ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE recurring_items
    VALIDATE CONSTRAINT fk_recurring_company;

ALTER TABLE recurring_items
    ADD CONSTRAINT fk_recurring_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_recurring_id
        CHECK (id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    ADD CONSTRAINT ck_recurring_name
        CHECK (name = BTRIM(name) AND name <> ''),
    ADD CONSTRAINT ck_recurring_type
        CHECK (type IN (1, 2)),
    ADD CONSTRAINT ck_recurring_amount
        CHECK (amount > 0),
    ADD CONSTRAINT ck_recurring_frequency
        CHECK (frequency IN ('daily', 'weekly', 'monthly', 'yearly')),
    ADD CONSTRAINT ck_recurring_interval
        CHECK (interval_value BETWEEN 1 AND 3650),
    ADD CONSTRAINT ck_recurring_calendar_fields
        CHECK (
            (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7)
            AND (day_of_month IS NULL OR day_of_month BETWEEN 1 AND 31)
            AND (month_of_year IS NULL OR month_of_year BETWEEN 1 AND 12)
        ),
    ADD CONSTRAINT ck_recurring_status
        CHECK (status IN (0, 1)),
    ADD CONSTRAINT ck_recurring_execution_count
        CHECK (execution_count >= 0),
    ADD CONSTRAINT ck_recurring_date_range
        CHECK (end_date IS NULL OR end_date >= start_date),
    ADD CONSTRAINT ck_recurring_execution_cursor
        CHECK (next_execution > COALESCE(last_executed, start_date));

COMMENT ON TABLE recurring_items IS
    'Authoritative recurring accounting rules owned by the recurring module; runtime caches are not permitted.';
