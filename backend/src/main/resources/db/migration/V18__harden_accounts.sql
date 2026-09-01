DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM accounts account
        WHERE account.company_id IS NULL
           OR NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = account.company_id)
           OR NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = account.user_id)
    ) THEN
        RAISE EXCEPTION 'accounts contains an unscoped or orphaned owner reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM accounts account
        LEFT JOIN ledgers ledger ON ledger.id = account.ledger_id
        WHERE account.ledger_id IS NOT NULL
          AND (ledger.id IS NULL OR ledger.company_id IS DISTINCT FROM account.company_id)
    ) THEN
        RAISE EXCEPTION 'accounts contains a cross-company or invalid ledger reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM accounts
        WHERE BTRIM(name) = ''
           OR LENGTH(BTRIM(name)) > 120
           OR LOWER(BTRIM(type)) NOT IN ('cash', 'bank', 'credit', 'digital', 'investment', 'debt')
           OR UPPER(BTRIM(currency)) !~ '^[A-Z]{3}$'
           OR include_in_net_worth NOT IN (0, 1)
           OR status NOT IN (0, 1)
           OR LOWER(BTRIM(reconciliation_status)) NOT IN ('reconciled', 'pending', 'exception')
           OR LOWER(BTRIM(risk_level)) NOT IN ('low', 'medium', 'high', 'critical')
           OR version < 0
           OR LENGTH(COALESCE(sub_type, '')) > 80
           OR LENGTH(COALESCE(bank, '')) > 120
           OR LENGTH(COALESCE(account_no, '')) > 64
           OR LENGTH(COALESCE(opening_bank, '')) > 120
           OR LENGTH(COALESCE(owner_name, '')) > 100
           OR LENGTH(COALESCE(purpose, '')) > 500
    ) THEN
        RAISE EXCEPTION 'accounts contains invalid classification or descriptive attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM accounts
        WHERE NOT pg_input_is_valid(BTRIM(balance), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(available_balance), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(credit_limit), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(frozen_amount), 'numeric')
    ) THEN
        RAISE EXCEPTION 'accounts contains an invalid monetary value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM accounts
        WHERE ABS(BTRIM(balance)::NUMERIC) > 9999999999999999.9999
           OR ABS(BTRIM(available_balance)::NUMERIC) > 9999999999999999.9999
           OR BTRIM(credit_limit)::NUMERIC < 0
           OR BTRIM(credit_limit)::NUMERIC > 9999999999999999.9999
           OR BTRIM(frozen_amount)::NUMERIC < 0
           OR BTRIM(frozen_amount)::NUMERIC > 9999999999999999.9999
           OR SCALE(BTRIM(balance)::NUMERIC) > 4
           OR SCALE(BTRIM(available_balance)::NUMERIC) > 4
           OR SCALE(BTRIM(credit_limit)::NUMERIC) > 4
           OR SCALE(BTRIM(frozen_amount)::NUMERIC) > 4
    ) THEN
        RAISE EXCEPTION 'accounts contains an out-of-range monetary value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM accounts
        WHERE (opened_at IS NOT NULL AND BTRIM(opened_at) <> ''
               AND NOT pg_input_is_valid(BTRIM(opened_at), 'date'))
           OR (last_reconciled_at IS NOT NULL
               AND BTRIM(last_reconciled_at) <> ''
               AND NOT pg_input_is_valid(BTRIM(last_reconciled_at), 'date'))
    ) THEN
        RAISE EXCEPTION 'accounts contains an invalid calendar date';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM accounts
        WHERE COALESCE(BTRIM(opened_at), '') <> ''
          AND COALESCE(BTRIM(last_reconciled_at), '') <> ''
          AND BTRIM(last_reconciled_at)::DATE < BTRIM(opened_at)::DATE
    ) THEN
        RAISE EXCEPTION 'accounts contains a reconciliation date before opening';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM accounts
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'accounts contains an invalid lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM accounts
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'accounts contains a lifecycle timestamp before creation';
    END IF;
END $$;

ALTER TABLE accounts
    DROP CONSTRAINT ck_accounts_company_positive,
    DROP CONSTRAINT ck_accounts_currency;

UPDATE accounts
SET name = BTRIM(name),
    type = LOWER(BTRIM(type)),
    sub_type = NULLIF(BTRIM(sub_type), ''),
    bank = NULLIF(BTRIM(bank), ''),
    account_no = NULLIF(BTRIM(account_no), ''),
    opening_bank = NULLIF(BTRIM(opening_bank), ''),
    currency = UPPER(BTRIM(currency)),
    balance = BTRIM(balance),
    available_balance = BTRIM(available_balance),
    credit_limit = BTRIM(credit_limit),
    frozen_amount = BTRIM(frozen_amount),
    opened_at = NULLIF(BTRIM(opened_at), ''),
    last_reconciled_at = NULLIF(BTRIM(last_reconciled_at), ''),
    owner_name = NULLIF(BTRIM(owner_name), ''),
    purpose = NULLIF(BTRIM(purpose), ''),
    reconciliation_status = LOWER(BTRIM(reconciliation_status)),
    risk_level = LOWER(BTRIM(risk_level)),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE accounts
    ALTER COLUMN available_balance DROP DEFAULT,
    ALTER COLUMN credit_limit DROP DEFAULT,
    ALTER COLUMN frozen_amount DROP DEFAULT;

ALTER TABLE accounts
    ALTER COLUMN name TYPE VARCHAR(120),
    ALTER COLUMN type TYPE VARCHAR(20),
    ALTER COLUMN sub_type TYPE VARCHAR(80),
    ALTER COLUMN bank TYPE VARCHAR(120),
    ALTER COLUMN account_no TYPE VARCHAR(64),
    ALTER COLUMN opening_bank TYPE VARCHAR(120),
    ALTER COLUMN currency TYPE VARCHAR(3),
    ALTER COLUMN balance TYPE NUMERIC(20, 4) USING balance::NUMERIC(20, 4),
    ALTER COLUMN available_balance TYPE NUMERIC(20, 4) USING available_balance::NUMERIC(20, 4),
    ALTER COLUMN credit_limit TYPE NUMERIC(20, 4) USING credit_limit::NUMERIC(20, 4),
    ALTER COLUMN frozen_amount TYPE NUMERIC(20, 4) USING frozen_amount::NUMERIC(20, 4),
    ALTER COLUMN include_in_net_worth TYPE BOOLEAN USING include_in_net_worth = 1,
    ALTER COLUMN opened_at TYPE DATE USING opened_at::DATE,
    ALTER COLUMN last_reconciled_at TYPE DATE USING last_reconciled_at::DATE,
    ALTER COLUMN owner_name TYPE VARCHAR(100),
    ALTER COLUMN purpose TYPE VARCHAR(500),
    ALTER COLUMN reconciliation_status TYPE VARCHAR(20),
    ALTER COLUMN risk_level TYPE VARCHAR(20),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ,
    ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE accounts
    ALTER COLUMN available_balance SET DEFAULT 0,
    ALTER COLUMN credit_limit SET DEFAULT 0,
    ALTER COLUMN frozen_amount SET DEFAULT 0;

ALTER TABLE accounts
    VALIDATE CONSTRAINT fk_accounts_company,
    VALIDATE CONSTRAINT fk_accounts_ledger;

ALTER TABLE accounts
    ADD CONSTRAINT fk_accounts_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_accounts_company_ledger
        FOREIGN KEY (company_id, ledger_id) REFERENCES ledgers(company_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_accounts_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_accounts_name
        CHECK (name <> ''),
    ADD CONSTRAINT ck_accounts_type
        CHECK (type IN ('cash', 'bank', 'credit', 'digital', 'investment', 'debt')),
    ADD CONSTRAINT ck_accounts_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_accounts_credit_limit
        CHECK (credit_limit >= 0),
    ADD CONSTRAINT ck_accounts_frozen_amount
        CHECK (frozen_amount >= 0),
    ADD CONSTRAINT ck_accounts_status
        CHECK (status IN (0, 1)),
    ADD CONSTRAINT ck_accounts_reconciliation_status
        CHECK (reconciliation_status IN ('reconciled', 'pending', 'exception')),
    ADD CONSTRAINT ck_accounts_risk_level
        CHECK (risk_level IN ('low', 'medium', 'high', 'critical')),
    ADD CONSTRAINT ck_accounts_reconciliation_dates
        CHECK (opened_at IS NULL OR last_reconciled_at IS NULL OR last_reconciled_at >= opened_at),
    ADD CONSTRAINT ck_accounts_lifecycle
        CHECK (updated_at >= created_at),
    ADD CONSTRAINT ck_accounts_version
        CHECK (version >= 0);

COMMENT ON TABLE accounts IS
    'Authoritative company-scoped funding accounts; process-local compatibility caches are not permitted.';
