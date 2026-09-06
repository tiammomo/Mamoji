CREATE TABLE accounting_period_controls (
    company_id BIGINT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    closed_through DATE,
    last_action VARCHAR(16) NOT NULL DEFAULT 'INITIAL',
    last_action_at TIMESTAMPTZ NOT NULL,
    last_action_by BIGINT,
    last_action_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_accounting_period_controls_company
        FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE,
    CONSTRAINT fk_accounting_period_controls_actor
        FOREIGN KEY (last_action_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_accounting_period_controls_version CHECK (version >= 0),
    CONSTRAINT ck_accounting_period_controls_month_end CHECK (
        closed_through IS NULL
        OR closed_through = (DATE_TRUNC('month', closed_through) + INTERVAL '1 month - 1 day')::DATE
    ),
    CONSTRAINT ck_accounting_period_controls_action CHECK (
        last_action IN ('INITIAL', 'CLOSE', 'REOPEN')
    ),
    CONSTRAINT ck_accounting_period_controls_action_details CHECK (
        (last_action = 'INITIAL' AND last_action_by IS NULL AND last_action_reason IS NULL)
        OR (last_action = 'CLOSE' AND last_action_by IS NOT NULL AND last_action_reason IS NULL)
        OR (
            last_action = 'REOPEN'
            AND last_action_by IS NOT NULL
            AND last_action_reason IS NOT NULL
            AND last_action_reason = BTRIM(last_action_reason)
            AND LENGTH(last_action_reason) BETWEEN 5 AND 500
        )
    ),
    CONSTRAINT ck_accounting_period_controls_lifecycle CHECK (
        created_at <= updated_at AND last_action_at BETWEEN created_at AND updated_at
    )
);

INSERT INTO accounting_period_controls (
    company_id, version, closed_through, last_action, last_action_at,
    last_action_by, last_action_reason, created_at, updated_at
)
SELECT id, 0, NULL, 'INITIAL', CURRENT_TIMESTAMP, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM companies;

CREATE FUNCTION provision_accounting_period_control()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO accounting_period_controls (
        company_id, version, closed_through, last_action, last_action_at,
        last_action_by, last_action_reason, created_at, updated_at
    ) VALUES (
        NEW.id, 0, NULL, 'INITIAL', CURRENT_TIMESTAMP,
        NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_company_accounting_period_control
AFTER INSERT ON companies
FOR EACH ROW
EXECUTE FUNCTION provision_accounting_period_control();

CREATE FUNCTION enforce_open_accounting_period()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transaction_company_id BIGINT;
    transaction_date DATE;
    closed_date DATE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        transaction_company_id := OLD.company_id;
        transaction_date := OLD.date;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.company_id <> NEW.company_id THEN
            RAISE EXCEPTION 'transaction company cannot be changed from % to %', OLD.company_id, NEW.company_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_transactions_company_immutable';
        END IF;
        transaction_company_id := OLD.company_id;
        transaction_date := LEAST(OLD.date, NEW.date);
    ELSE
        transaction_company_id := NEW.company_id;
        transaction_date := NEW.date;
    END IF;

    SELECT closed_through
    INTO closed_date
    FROM accounting_period_controls
    WHERE company_id = transaction_company_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'accounting period control is missing for company %', transaction_company_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_transactions_accounting_period_control';
    END IF;

    IF closed_date IS NOT NULL AND transaction_date <= closed_date THEN
        RAISE EXCEPTION 'accounting period is closed through % for transaction date %', closed_date, transaction_date
            USING ERRCODE = '23514', CONSTRAINT = 'ck_transactions_open_accounting_period';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_transactions_open_accounting_period
BEFORE INSERT OR UPDATE OR DELETE ON transactions
FOR EACH ROW
EXECUTE FUNCTION enforce_open_accounting_period();

COMMENT ON TABLE accounting_period_controls IS
    'Company-scoped continuous close watermark serialized with every transaction mutation.';

COMMENT ON COLUMN accounting_period_controls.closed_through IS
    'Inclusive month-end date at or before which transaction rows are immutable.';
