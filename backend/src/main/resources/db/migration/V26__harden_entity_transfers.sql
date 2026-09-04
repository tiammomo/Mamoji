DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM entity_transfers transfer
        WHERE NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = transfer.from_entity_id)
           OR NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = transfer.to_entity_id)
    ) THEN
        RAISE EXCEPTION 'entity_transfers contains an orphaned subject';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM entity_transfers transfer
        WHERE NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = transfer.operator_user_id)
    ) THEN
        RAISE EXCEPTION 'entity_transfers contains an orphaned operator';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM entity_transfers
        WHERE from_entity_id <= 0 OR to_entity_id <= 0 OR from_entity_id = to_entity_id
           OR LOWER(BTRIM(transfer_type)) NOT IN (
               'inter_entity_transfer',
               'shareholder_advance',
               'advance_repayment',
               'expense_reimbursement',
               'reimbursement_payment'
           )
           OR UPPER(BTRIM(currency)) !~ '^[A-Z]{3}$'
           OR LOWER(BTRIM(status)) <> 'recorded'
           OR LENGTH(COALESCE(NULLIF(BTRIM(note), ''), '')) > 1000
           OR operator_user_id <= 0
    ) THEN
        RAISE EXCEPTION 'entity_transfers contains invalid transfer attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM entity_transfers
        WHERE NOT pg_input_is_valid(BTRIM(amount), 'numeric')
           OR NOT pg_input_is_valid(BTRIM(transfer_date), 'date')
           OR NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'entity_transfers contains an invalid amount, date, or timestamp';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM entity_transfers
        WHERE BTRIM(amount)::NUMERIC <= 0
           OR BTRIM(amount)::NUMERIC >= 10000000000000000
           OR BTRIM(amount)::NUMERIC <> ROUND(BTRIM(amount)::NUMERIC, 4)
    ) THEN
        RAISE EXCEPTION 'entity_transfers contains an out-of-range amount';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM entity_transfers
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'entity_transfers contains an invalid lifecycle sequence';
    END IF;
END $$;

UPDATE entity_transfers
SET transfer_type = LOWER(BTRIM(transfer_type)),
    amount = BTRIM(amount),
    currency = UPPER(BTRIM(currency)),
    transfer_date = BTRIM(transfer_date),
    note = NULLIF(BTRIM(note), ''),
    status = LOWER(BTRIM(status)),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE entity_transfers
    ALTER COLUMN transfer_type TYPE VARCHAR(64),
    ALTER COLUMN amount TYPE NUMERIC(20,4) USING amount::NUMERIC(20,4),
    ALTER COLUMN currency TYPE VARCHAR(3),
    ALTER COLUMN transfer_date TYPE DATE USING transfer_date::DATE,
    ALTER COLUMN note TYPE VARCHAR(1000),
    ALTER COLUMN status TYPE VARCHAR(32),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ;

ALTER TABLE entity_transfers
    ADD CONSTRAINT fk_entity_transfers_source
        FOREIGN KEY (from_entity_id) REFERENCES companies(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_entity_transfers_target
        FOREIGN KEY (to_entity_id) REFERENCES companies(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_entity_transfers_operator
        FOREIGN KEY (operator_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_entity_transfers_subjects_positive
        CHECK (from_entity_id > 0 AND to_entity_id > 0),
    ADD CONSTRAINT ck_entity_transfers_distinct_subjects CHECK (from_entity_id <> to_entity_id),
    ADD CONSTRAINT ck_entity_transfers_type CHECK (transfer_type IN (
        'inter_entity_transfer',
        'shareholder_advance',
        'advance_repayment',
        'expense_reimbursement',
        'reimbursement_payment'
    )),
    ADD CONSTRAINT ck_entity_transfers_amount CHECK (amount > 0),
    ADD CONSTRAINT ck_entity_transfers_currency CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_entity_transfers_note CHECK (
        note IS NULL OR (note = BTRIM(note) AND note <> '')
    ),
    ADD CONSTRAINT ck_entity_transfers_status CHECK (status = 'recorded'),
    ADD CONSTRAINT ck_entity_transfers_operator_positive CHECK (operator_user_id > 0),
    ADD CONSTRAINT ck_entity_transfers_lifecycle CHECK (updated_at >= created_at);

CREATE INDEX idx_entity_transfers_pair_date
    ON entity_transfers(
        LEAST(from_entity_id, to_entity_id),
        GREATEST(from_entity_id, to_entity_id),
        transfer_date DESC,
        id
    );

CREATE INDEX idx_entity_transfers_operator_created
    ON entity_transfers(operator_user_id, created_at DESC, id);

CREATE FUNCTION prevent_entity_transfer_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'entity transfers are append-only';
END;
$$;

CREATE TRIGGER trg_entity_transfer_append_only
BEFORE UPDATE OR DELETE ON entity_transfers
FOR EACH ROW
EXECUTE FUNCTION prevent_entity_transfer_change();

COMMENT ON TABLE entity_transfers IS
    'Append-only, tenant-scoped records of funds moving between distinct company subjects.';
