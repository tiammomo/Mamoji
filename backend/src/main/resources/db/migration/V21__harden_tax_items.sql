DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tax_items tax_item
        WHERE NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = tax_item.company_id)
    ) THEN
        RAISE EXCEPTION 'tax_items contains an orphaned company reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tax_items
        WHERE BTRIM(name) = ''
           OR LENGTH(BTRIM(name)) > 160
           OR UPPER(BTRIM(period)) !~ '^([0-9]{4}-(0[1-9]|1[0-2])|[0-9]{4}-Q[1-4]|[0-9]{4}|[0-9]{4}-(0[1-9]|1[0-2])-([0-2][0-9]|3[01]))$'
           OR LOWER(BTRIM(tax_type)) NOT IN ('vat', 'corporate_income_tax', 'personal_income_tax', 'surcharge', 'stamp_duty')
           OR LOWER(BTRIM(status)) NOT IN ('estimated', 'pending', 'paid', 'overdue')
           OR LOWER(BTRIM(filing_status)) NOT IN ('not_started', 'prepared', 'submitted', 'accepted', 'overdue')
           OR LOWER(BTRIM(payment_status)) NOT IN ('unpaid', 'partial', 'paid')
           OR LOWER(BTRIM(frequency)) NOT IN ('monthly', 'quarterly', 'annual', 'one_time')
           OR LOWER(BTRIM(risk_level)) NOT IN ('low', 'medium', 'high')
           OR COALESCE(BTRIM(responsible_person), '') = ''
           OR LENGTH(BTRIM(responsible_person)) > 120
           OR COALESCE(BTRIM(policy_basis), '') = ''
           OR LENGTH(BTRIM(policy_basis)) > 160
           OR LOWER(BTRIM(source_type)) NOT IN ('manual', 'demo_estimate', 'transaction', 'receipt', 'payroll', 'policy')
           OR LENGTH(COALESCE(BTRIM(note), '')) > 2000
    ) THEN
        RAISE EXCEPTION 'tax_items contains invalid classification or descriptive attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tax_items
        WHERE NOT pg_input_is_valid(BTRIM(taxable_amount), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(tax_amount), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(paid_amount), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(deductible_amount), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(tax_rate), 'numeric')
    ) THEN
        RAISE EXCEPTION 'tax_items contains an invalid monetary or rate value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tax_items
        WHERE BTRIM(taxable_amount)::NUMERIC < 0
           OR BTRIM(taxable_amount)::NUMERIC > 9999999999999999.9999
           OR SCALE(BTRIM(taxable_amount)::NUMERIC) > 4
           OR BTRIM(tax_amount)::NUMERIC < 0
           OR BTRIM(tax_amount)::NUMERIC > 9999999999999999.9999
           OR SCALE(BTRIM(tax_amount)::NUMERIC) > 4
           OR BTRIM(paid_amount)::NUMERIC < 0
           OR BTRIM(paid_amount)::NUMERIC > BTRIM(tax_amount)::NUMERIC
           OR SCALE(BTRIM(paid_amount)::NUMERIC) > 4
           OR BTRIM(deductible_amount)::NUMERIC < 0
           OR BTRIM(deductible_amount)::NUMERIC > 9999999999999999.9999
           OR SCALE(BTRIM(deductible_amount)::NUMERIC) > 4
           OR BTRIM(tax_rate)::NUMERIC < 0
           OR BTRIM(tax_rate)::NUMERIC > 100
           OR SCALE(BTRIM(tax_rate)::NUMERIC) > 4
    ) THEN
        RAISE EXCEPTION 'tax_items contains an out-of-range monetary or rate value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tax_items
        WHERE NOT pg_input_is_valid(BTRIM(due_date), 'date')
           OR (COALESCE(BTRIM(declaration_date), '') <> ''
               AND NOT pg_input_is_valid(BTRIM(declaration_date), 'date'))
           OR (COALESCE(BTRIM(payment_date), '') <> ''
               AND NOT pg_input_is_valid(BTRIM(payment_date), 'date'))
           OR (LOWER(BTRIM(frequency)) = 'one_time'
               AND NOT pg_input_is_valid(UPPER(BTRIM(period)), 'date'))
    ) THEN
        RAISE EXCEPTION 'tax_items contains an invalid calendar date';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tax_items
        WHERE (LOWER(BTRIM(frequency)) = 'monthly'
               AND UPPER(BTRIM(period)) !~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
           OR (LOWER(BTRIM(frequency)) = 'quarterly'
               AND UPPER(BTRIM(period)) !~ '^[0-9]{4}-Q[1-4]$')
           OR (LOWER(BTRIM(frequency)) = 'annual'
               AND UPPER(BTRIM(period)) !~ '^[0-9]{4}$')
           OR (LOWER(BTRIM(frequency)) = 'one_time'
               AND UPPER(BTRIM(period)) !~ '^[0-9]{4}-(0[1-9]|1[0-2])-([0-2][0-9]|3[01])$')
    ) THEN
        RAISE EXCEPTION 'tax_items contains a period inconsistent with its frequency';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tax_items
        WHERE (LOWER(BTRIM(payment_status)) = 'paid')
                  IS DISTINCT FROM (
                      BTRIM(tax_amount)::NUMERIC = 0
                      OR BTRIM(paid_amount)::NUMERIC = BTRIM(tax_amount)::NUMERIC
                  )
           OR (LOWER(BTRIM(payment_status)) = 'partial')
                  IS DISTINCT FROM (
                      BTRIM(paid_amount)::NUMERIC > 0
                      AND BTRIM(paid_amount)::NUMERIC < BTRIM(tax_amount)::NUMERIC
                  )
           OR (LOWER(BTRIM(status)) = 'paid' AND (
               LOWER(BTRIM(payment_status)) <> 'paid'
               OR LOWER(BTRIM(filing_status)) <> 'accepted'
               OR COALESCE(BTRIM(payment_date), '') = ''
           ))
    ) THEN
        RAISE EXCEPTION 'tax_items contains an inconsistent filing or payment lifecycle';
    END IF;

    IF EXISTS (
        SELECT company_id, LOWER(BTRIM(tax_type)), UPPER(BTRIM(period))
        FROM tax_items
        GROUP BY company_id, LOWER(BTRIM(tax_type)), UPPER(BTRIM(period))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'tax_items contains duplicate company tax periods';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tax_items
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'tax_items contains an invalid lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tax_items
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'tax_items contains a lifecycle timestamp before creation';
    END IF;
END $$;

UPDATE tax_items
SET name = BTRIM(name),
    period = UPPER(BTRIM(period)),
    tax_type = LOWER(BTRIM(tax_type)),
    taxable_amount = BTRIM(taxable_amount),
    tax_amount = BTRIM(tax_amount),
    paid_amount = BTRIM(paid_amount),
    deductible_amount = BTRIM(deductible_amount),
    tax_rate = BTRIM(tax_rate),
    due_date = BTRIM(due_date),
    status = LOWER(BTRIM(status)),
    filing_status = LOWER(BTRIM(filing_status)),
    payment_status = LOWER(BTRIM(payment_status)),
    frequency = LOWER(BTRIM(frequency)),
    declaration_date = NULLIF(BTRIM(declaration_date), ''),
    payment_date = NULLIF(BTRIM(payment_date), ''),
    responsible_person = BTRIM(responsible_person),
    risk_level = LOWER(BTRIM(risk_level)),
    policy_basis = BTRIM(policy_basis),
    source_type = LOWER(BTRIM(source_type)),
    note = NULLIF(BTRIM(note), ''),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE tax_items
    ALTER COLUMN deductible_amount DROP DEFAULT,
    ALTER COLUMN tax_rate DROP DEFAULT;

ALTER TABLE tax_items
    ALTER COLUMN name TYPE VARCHAR(160),
    ALTER COLUMN period TYPE VARCHAR(10),
    ALTER COLUMN tax_type TYPE VARCHAR(32),
    ALTER COLUMN taxable_amount TYPE NUMERIC(20, 4) USING taxable_amount::NUMERIC(20, 4),
    ALTER COLUMN tax_amount TYPE NUMERIC(20, 4) USING tax_amount::NUMERIC(20, 4),
    ALTER COLUMN paid_amount TYPE NUMERIC(20, 4) USING paid_amount::NUMERIC(20, 4),
    ALTER COLUMN deductible_amount TYPE NUMERIC(20, 4) USING deductible_amount::NUMERIC(20, 4),
    ALTER COLUMN tax_rate TYPE NUMERIC(7, 4) USING tax_rate::NUMERIC(7, 4),
    ALTER COLUMN due_date TYPE DATE USING due_date::DATE,
    ALTER COLUMN status TYPE VARCHAR(16),
    ALTER COLUMN filing_status TYPE VARCHAR(16),
    ALTER COLUMN payment_status TYPE VARCHAR(16),
    ALTER COLUMN frequency TYPE VARCHAR(16),
    ALTER COLUMN declaration_date TYPE DATE USING declaration_date::DATE,
    ALTER COLUMN payment_date TYPE DATE USING payment_date::DATE,
    ALTER COLUMN responsible_person TYPE VARCHAR(120),
    ALTER COLUMN risk_level TYPE VARCHAR(16),
    ALTER COLUMN policy_basis TYPE VARCHAR(160),
    ALTER COLUMN source_type TYPE VARCHAR(20),
    ALTER COLUMN note TYPE VARCHAR(2000),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ;

ALTER TABLE tax_items
    ALTER COLUMN deductible_amount SET DEFAULT 0,
    ALTER COLUMN tax_rate SET DEFAULT 0,
    ALTER COLUMN responsible_person SET NOT NULL,
    ALTER COLUMN policy_basis SET NOT NULL;

ALTER TABLE tax_items
    ADD CONSTRAINT uq_tax_items_company_type_period
        UNIQUE (company_id, tax_type, period),
    ADD CONSTRAINT fk_tax_items_company
        FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_tax_items_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_tax_items_name
        CHECK (name = BTRIM(name) AND name <> ''),
    ADD CONSTRAINT ck_tax_items_period_frequency
        CHECK (
            (frequency = 'monthly' AND period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
            OR (frequency = 'quarterly' AND period ~ '^[0-9]{4}-Q[1-4]$')
            OR (frequency = 'annual' AND period ~ '^[0-9]{4}$')
            OR (frequency = 'one_time'
                AND CASE
                    WHEN period ~ '^[0-9]{4}-(0[1-9]|1[0-2])-([0-2][0-9]|3[01])$'
                    THEN period = (period::DATE)::TEXT
                    ELSE FALSE
                END)
        ),
    ADD CONSTRAINT ck_tax_items_tax_type
        CHECK (tax_type IN ('vat', 'corporate_income_tax', 'personal_income_tax', 'surcharge', 'stamp_duty')),
    ADD CONSTRAINT ck_tax_items_amounts
        CHECK (
            taxable_amount >= 0
            AND tax_amount >= 0
            AND paid_amount >= 0
            AND paid_amount <= tax_amount
            AND deductible_amount >= 0
            AND tax_rate BETWEEN 0 AND 100
        ),
    ADD CONSTRAINT ck_tax_items_status
        CHECK (status IN ('estimated', 'pending', 'paid', 'overdue')),
    ADD CONSTRAINT ck_tax_items_filing_status
        CHECK (filing_status IN ('not_started', 'prepared', 'submitted', 'accepted', 'overdue')),
    ADD CONSTRAINT ck_tax_items_payment_status
        CHECK (
            payment_status IN ('unpaid', 'partial', 'paid')
            AND (payment_status = 'paid') = (tax_amount = 0 OR paid_amount = tax_amount)
            AND (payment_status = 'partial') = (paid_amount > 0 AND paid_amount < tax_amount)
        ),
    ADD CONSTRAINT ck_tax_items_paid_lifecycle
        CHECK (
            status <> 'paid'
            OR (payment_status = 'paid' AND filing_status = 'accepted' AND payment_date IS NOT NULL)
        ),
    ADD CONSTRAINT ck_tax_items_frequency
        CHECK (frequency IN ('monthly', 'quarterly', 'annual', 'one_time')),
    ADD CONSTRAINT ck_tax_items_responsible_person
        CHECK (responsible_person = BTRIM(responsible_person) AND responsible_person <> ''),
    ADD CONSTRAINT ck_tax_items_risk_level
        CHECK (risk_level IN ('low', 'medium', 'high')),
    ADD CONSTRAINT ck_tax_items_policy_basis
        CHECK (policy_basis = BTRIM(policy_basis) AND policy_basis <> ''),
    ADD CONSTRAINT ck_tax_items_source_type
        CHECK (source_type IN ('manual', 'demo_estimate', 'transaction', 'receipt', 'payroll', 'policy')),
    ADD CONSTRAINT ck_tax_items_note
        CHECK (note IS NULL OR (note = BTRIM(note) AND note <> '')),
    ADD CONSTRAINT ck_tax_items_lifecycle
        CHECK (updated_at >= created_at);

CREATE FUNCTION prevent_tax_item_company_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.company_id <> OLD.company_id THEN
        RAISE EXCEPTION 'tax item company cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_tax_item_company_immutable
BEFORE UPDATE OF company_id ON tax_items
FOR EACH ROW
EXECUTE FUNCTION prevent_tax_item_company_change();

COMMENT ON TABLE tax_items IS
    'Authoritative company-scoped tax filing and payment work items; process-local compatibility caches are not permitted.';
