DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM transactions transaction_record
        WHERE transaction_record.company_id IS NULL
           OR NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = transaction_record.company_id)
           OR NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = transaction_record.user_id)
    ) THEN
        RAISE EXCEPTION 'transactions contains an unscoped or orphaned owner reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM transactions transaction_record
        LEFT JOIN accounts account ON account.id = transaction_record.account_id
        LEFT JOIN categories category ON category.id = transaction_record.category_id
        LEFT JOIN ledgers ledger ON ledger.id = transaction_record.family_id
        LEFT JOIN budgets budget ON budget.id = transaction_record.budget_id
        LEFT JOIN transactions original ON original.id = transaction_record.original_transaction_id
        WHERE account.id IS NULL
           OR account.company_id IS DISTINCT FROM transaction_record.company_id
           OR category.id IS NULL
           OR category.company_id IS DISTINCT FROM transaction_record.company_id
           OR (transaction_record.family_id IS NOT NULL AND (
               ledger.id IS NULL OR ledger.company_id IS DISTINCT FROM transaction_record.company_id
           ))
           OR (transaction_record.budget_id IS NOT NULL AND (
               budget.id IS NULL OR budget.company_id IS DISTINCT FROM transaction_record.company_id
           ))
           OR (transaction_record.original_transaction_id IS NOT NULL AND (
               original.id IS NULL OR original.company_id IS DISTINCT FROM transaction_record.company_id
           ))
    ) THEN
        RAISE EXCEPTION 'transactions contains a cross-company or invalid accounting reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM transactions transaction_record
        JOIN categories category ON category.id = transaction_record.category_id
        LEFT JOIN transactions original ON original.id = transaction_record.original_transaction_id
        WHERE transaction_record.type NOT IN (1, 2, 3)
           OR (transaction_record.type = 1 AND category.type <> 'income')
           OR (transaction_record.type IN (2, 3) AND category.type <> 'expense')
           OR (transaction_record.type = 3 AND (
               original.id IS NULL
               OR original.id = transaction_record.id
               OR original.type <> 2
               OR original.user_id <> transaction_record.user_id
           ))
           OR (transaction_record.type <> 3 AND transaction_record.original_transaction_id IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'transactions contains an invalid type or refund relationship';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM transactions
        WHERE NOT pg_input_is_valid(BTRIM(amount), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(refunded_amount), 'numeric')
    ) THEN
        RAISE EXCEPTION 'transactions contains an invalid monetary value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM transactions
        WHERE BTRIM(amount)::NUMERIC <= 0
           OR BTRIM(amount)::NUMERIC > 99999999999999.9999
           OR SCALE(BTRIM(amount)::NUMERIC) > 4
           OR BTRIM(refunded_amount)::NUMERIC < 0
           OR BTRIM(refunded_amount)::NUMERIC > BTRIM(amount)::NUMERIC
           OR SCALE(BTRIM(refunded_amount)::NUMERIC) > 4
    ) THEN
        RAISE EXCEPTION 'transactions contains an out-of-range monetary value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM transactions
        WHERE is_refundable NOT IN (0, 1)
           OR LENGTH(note) > 2000
           OR version < 0
           OR (type = 2 AND (is_refundable = 1) <> (BTRIM(refunded_amount)::NUMERIC < BTRIM(amount)::NUMERIC))
           OR (type <> 2 AND (is_refundable <> 0 OR BTRIM(refunded_amount)::NUMERIC <> 0))
           OR (idempotency_key IS NOT NULL AND (
               idempotency_key = ''
               OR LENGTH(idempotency_key) > 128
               OR idempotency_key !~ '^[A-Za-z0-9._:-]+$'
           ))
    ) THEN
        RAISE EXCEPTION 'transactions contains invalid lifecycle attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM transactions
        WHERE NOT pg_input_is_valid(BTRIM(date), 'date')
    ) THEN
        RAISE EXCEPTION 'transactions contains an invalid calendar date';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM transactions
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'transactions contains an invalid lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM transactions
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'transactions contains a lifecycle timestamp before creation';
    END IF;
END $$;

ALTER TABLE transactions
    DROP CONSTRAINT ck_transactions_company_positive,
    DROP CONSTRAINT ck_transactions_type,
    DROP CONSTRAINT ck_transactions_amount_format,
    DROP CONSTRAINT ck_transactions_date_format;

UPDATE transactions
SET amount = BTRIM(amount),
    refunded_amount = BTRIM(refunded_amount),
    date = BTRIM(date),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE transactions
    ALTER COLUMN amount TYPE NUMERIC(18, 4) USING amount::NUMERIC(18, 4),
    ALTER COLUMN refunded_amount TYPE NUMERIC(18, 4) USING refunded_amount::NUMERIC(18, 4),
    ALTER COLUMN date TYPE DATE USING date::DATE,
    ALTER COLUMN note TYPE VARCHAR(2000),
    ALTER COLUMN is_refundable TYPE BOOLEAN USING is_refundable = 1,
    ALTER COLUMN idempotency_key TYPE VARCHAR(128),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ,
    ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE transactions
    VALIDATE CONSTRAINT fk_transactions_company,
    VALIDATE CONSTRAINT fk_transactions_ledger,
    VALIDATE CONSTRAINT fk_transactions_category,
    VALIDATE CONSTRAINT fk_transactions_account,
    VALIDATE CONSTRAINT fk_transactions_original,
    VALIDATE CONSTRAINT fk_transactions_budget;

ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_company_id UNIQUE (company_id, id);
ALTER TABLE categories
    ADD CONSTRAINT uq_categories_company_id UNIQUE (company_id, id);
ALTER TABLE ledgers
    ADD CONSTRAINT uq_ledgers_company_id UNIQUE (company_id, id);
ALTER TABLE budgets
    ADD CONSTRAINT uq_budgets_company_id UNIQUE (company_id, id);
ALTER TABLE transactions
    ADD CONSTRAINT uq_transactions_company_id UNIQUE (company_id, id),
    ADD CONSTRAINT uq_transactions_company_id_user UNIQUE (company_id, id, user_id);

ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transactions_company_account
        FOREIGN KEY (company_id, account_id) REFERENCES accounts(company_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transactions_company_category
        FOREIGN KEY (company_id, category_id) REFERENCES categories(company_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transactions_company_ledger
        FOREIGN KEY (company_id, family_id) REFERENCES ledgers(company_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transactions_company_budget
        FOREIGN KEY (company_id, budget_id) REFERENCES budgets(company_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transactions_company_original
        FOREIGN KEY (company_id, original_transaction_id) REFERENCES transactions(company_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transactions_company_original_user
        FOREIGN KEY (company_id, original_transaction_id, user_id)
        REFERENCES transactions(company_id, id, user_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_transactions_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_transactions_type
        CHECK (type IN (1, 2, 3)),
    ADD CONSTRAINT ck_transactions_amount
        CHECK (amount > 0),
    ADD CONSTRAINT ck_transactions_refund_amount
        CHECK (refunded_amount >= 0 AND refunded_amount <= amount),
    ADD CONSTRAINT ck_transactions_refund_state
        CHECK (
            (type = 2 AND original_transaction_id IS NULL
                AND is_refundable = (refunded_amount < amount))
            OR (type IN (1, 3) AND refunded_amount = 0 AND is_refundable = FALSE)
        ),
    ADD CONSTRAINT ck_transactions_original
        CHECK (
            (type = 3 AND original_transaction_id IS NOT NULL AND original_transaction_id <> id)
            OR (type <> 3 AND original_transaction_id IS NULL)
        ),
    ADD CONSTRAINT ck_transactions_note
        CHECK (LENGTH(note) <= 2000),
    ADD CONSTRAINT ck_transactions_idempotency_key
        CHECK (
            idempotency_key IS NULL
            OR (idempotency_key <> '' AND idempotency_key ~ '^[A-Za-z0-9._:-]+$')
        ),
    ADD CONSTRAINT ck_transactions_lifecycle
        CHECK (updated_at >= created_at),
    ADD CONSTRAINT ck_transactions_version
        CHECK (version >= 0);

COMMENT ON TABLE transactions IS
    'Authoritative company-scoped operating ledger; process-local compatibility caches are not permitted.';
