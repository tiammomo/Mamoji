DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM budgets budget
        WHERE budget.company_id IS NULL
           OR NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = budget.company_id)
           OR NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = budget.user_id)
    ) THEN
        RAISE EXCEPTION 'budgets contains an unscoped or orphaned owner reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM budgets budget
        LEFT JOIN ledgers ledger ON ledger.id = budget.ledger_id
        LEFT JOIN categories category ON category.id = budget.category_id
        WHERE (budget.ledger_id IS NOT NULL AND (ledger.id IS NULL OR ledger.company_id IS DISTINCT FROM budget.company_id))
           OR (budget.category_id IS NOT NULL AND (
               category.id IS NULL
               OR category.company_id IS DISTINCT FROM budget.company_id
               OR category.type <> 'expense'
           ))
    ) THEN
        RAISE EXCEPTION 'budgets contains a cross-company or invalid accounting reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM budgets
        WHERE BTRIM(name) = ''
           OR LENGTH(BTRIM(name)) > 64
           OR warning_threshold NOT BETWEEN 0 AND 100
           OR status NOT BETWEEN 0 AND 3
           OR warning_reached NOT IN (0, 1)
           OR LOWER(BTRIM(risk_level)) NOT IN ('low', 'medium', 'high', 'critical')
           OR BTRIM(risk_message) = ''
           OR LENGTH(BTRIM(risk_message)) > 255
           OR version < 0
    ) THEN
        RAISE EXCEPTION 'budgets contains invalid definition or projection attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM budgets
        WHERE NOT pg_input_is_valid(BTRIM(amount), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(spent), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(remaining_amount), 'numeric')
    ) THEN
        RAISE EXCEPTION 'budgets contains an invalid monetary value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM budgets
        WHERE BTRIM(amount)::NUMERIC <= 0
           OR BTRIM(amount)::NUMERIC > 99999999999999.99
           OR SCALE(BTRIM(amount)::NUMERIC) > 2
           OR BTRIM(spent)::NUMERIC < 0
           OR ABS(BTRIM(spent)::NUMERIC) > 99999999999999.99
           OR SCALE(BTRIM(spent)::NUMERIC) > 2
           OR ABS(BTRIM(remaining_amount)::NUMERIC) > 99999999999999.99
           OR SCALE(BTRIM(remaining_amount)::NUMERIC) > 2
    ) THEN
        RAISE EXCEPTION 'budgets contains an out-of-range monetary value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM budgets
        WHERE usage_rate < 0
           OR usage_rate::TEXT IN ('NaN', 'Infinity', '-Infinity')
    ) THEN
        RAISE EXCEPTION 'budgets contains an invalid usage rate';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM budgets
        WHERE NOT pg_input_is_valid(BTRIM(start_date), 'date')
           OR NOT pg_input_is_valid(BTRIM(end_date), 'date')
    ) THEN
        RAISE EXCEPTION 'budgets contains an invalid calendar date';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM budgets
        WHERE BTRIM(end_date)::DATE < BTRIM(start_date)::DATE
    ) THEN
        RAISE EXCEPTION 'budgets contains an invalid calendar range';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM budgets
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'budgets contains an invalid lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM budgets
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'budgets contains a lifecycle timestamp before creation';
    END IF;
END $$;

ALTER TABLE budgets
    DROP CONSTRAINT ck_budgets_company_positive,
    DROP CONSTRAINT ck_budgets_amount_format,
    DROP CONSTRAINT ck_budgets_dates_format,
    DROP CONSTRAINT ck_budgets_threshold,
    DROP CONSTRAINT ck_budgets_status;

UPDATE budgets
SET name = BTRIM(name),
    amount = BTRIM(amount),
    spent = BTRIM(spent),
    remaining_amount = BTRIM(remaining_amount),
    start_date = BTRIM(start_date),
    end_date = BTRIM(end_date),
    risk_level = LOWER(BTRIM(risk_level)),
    risk_message = BTRIM(risk_message),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE budgets
    ALTER COLUMN name TYPE VARCHAR(64),
    ALTER COLUMN amount TYPE NUMERIC(16, 2) USING amount::NUMERIC(16, 2),
    ALTER COLUMN start_date TYPE DATE USING start_date::DATE,
    ALTER COLUMN end_date TYPE DATE USING end_date::DATE,
    ALTER COLUMN spent TYPE NUMERIC(16, 2) USING spent::NUMERIC(16, 2),
    ALTER COLUMN remaining_amount TYPE NUMERIC(16, 2) USING remaining_amount::NUMERIC(16, 2),
    ALTER COLUMN usage_rate TYPE DOUBLE PRECISION,
    ALTER COLUMN warning_reached TYPE BOOLEAN USING warning_reached = 1,
    ALTER COLUMN risk_level TYPE VARCHAR(16),
    ALTER COLUMN risk_message TYPE VARCHAR(255),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ,
    ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE budgets
    VALIDATE CONSTRAINT fk_budgets_company,
    VALIDATE CONSTRAINT fk_budgets_ledger,
    VALIDATE CONSTRAINT fk_budgets_category;

ALTER TABLE budgets
    ADD CONSTRAINT fk_budgets_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_budgets_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_budgets_name
        CHECK (name = BTRIM(name) AND name <> ''),
    ADD CONSTRAINT ck_budgets_amount
        CHECK (amount > 0),
    ADD CONSTRAINT ck_budgets_projection
        CHECK (spent >= 0 AND usage_rate >= 0),
    ADD CONSTRAINT ck_budgets_dates
        CHECK (end_date >= start_date),
    ADD CONSTRAINT ck_budgets_threshold
        CHECK (warning_threshold BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_budgets_status
        CHECK (status BETWEEN 0 AND 3),
    ADD CONSTRAINT ck_budgets_risk
        CHECK (
            risk_level IN ('low', 'medium', 'high', 'critical')
            AND risk_message = BTRIM(risk_message)
            AND risk_message <> ''
        ),
    ADD CONSTRAINT ck_budgets_lifecycle
        CHECK (updated_at >= created_at),
    ADD CONSTRAINT ck_budgets_version
        CHECK (version >= 0);

COMMENT ON TABLE budgets IS
    'Authoritative company-scoped budget definitions and projections; runtime caches are not permitted.';
