DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM receipt_vouchers voucher
        LEFT JOIN companies company ON company.id = voucher.company_id
        LEFT JOIN users operator_user ON operator_user.id = voucher.operator_user_id
        LEFT JOIN users approver ON approver.id = voucher.approved_by_user_id
        LEFT JOIN transactions transaction_record ON transaction_record.id = voucher.transaction_id
        WHERE company.id IS NULL
           OR operator_user.id IS NULL
           OR (voucher.approved_by_user_id IS NOT NULL AND approver.id IS NULL)
           OR (voucher.transaction_id IS NOT NULL AND (
               transaction_record.id IS NULL
               OR transaction_record.company_id IS DISTINCT FROM voucher.company_id
           ))
    ) THEN
        RAISE EXCEPTION 'receipt_vouchers contains an orphaned or cross-company reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_file_hashes file_hash
        JOIN receipt_vouchers voucher ON voucher.id = file_hash.voucher_id
        WHERE file_hash.company_id IS DISTINCT FROM voucher.company_id
    ) THEN
        RAISE EXCEPTION 'receipt_file_hashes contains a cross-company voucher reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_vouchers
        WHERE LOWER(BTRIM(voucher_type)) NOT IN (
                'sales_invoice', 'purchase_invoice', 'receipt', 'bank_slip',
                'contract', 'reimbursement', 'tax_receipt'
              )
           OR LOWER(BTRIM(direction)) NOT IN ('income', 'expense')
           OR LOWER(BTRIM(status)) NOT IN ('pending_review', 'verified', 'linked', 'archived', 'rejected')
           OR LOWER(BTRIM(invoice_check_status)) NOT IN ('not_required', 'pending', 'verified', 'failed')
           OR LOWER(BTRIM(deduction_status)) NOT IN (
                'not_applicable', 'pending', 'deductible', 'deducted', 'transferred_out'
              )
           OR LOWER(BTRIM(reimbursement_status)) NOT IN (
                'not_applicable', 'submitted', 'approved', 'paid', 'archived', 'rejected'
              )
           OR LOWER(BTRIM(approval_status)) NOT IN (
                'not_required', 'not_submitted', 'pending', 'approved', 'rejected'
              )
           OR LOWER(BTRIM(accounting_status)) NOT IN ('not_started', 'draft', 'posted', 'reversed')
           OR LOWER(BTRIM(risk_level)) NOT IN ('low', 'medium', 'high', 'critical')
           OR LOWER(BTRIM(file_storage_provider)) NOT IN ('none', 'metadata_only', 'minio')
           OR (NULLIF(BTRIM(tax_period), '') IS NOT NULL
               AND BTRIM(tax_period) !~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
    ) THEN
        RAISE EXCEPTION 'receipt_vouchers contains an invalid classification or tax period';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_vouchers
        WHERE BTRIM(voucher_no) = '' OR LENGTH(BTRIM(voucher_no)) > 120
           OR BTRIM(title) = '' OR LENGTH(BTRIM(title)) > 200
           OR BTRIM(counterparty) = '' OR LENGTH(BTRIM(counterparty)) > 200
           OR LENGTH(COALESCE(NULLIF(BTRIM(accounting_voucher_no), ''), '')) > 120
           OR LENGTH(COALESCE(NULLIF(BTRIM(accounting_entry), ''), '')) > 4000
           OR LENGTH(COALESCE(NULLIF(BTRIM(business_purpose), ''), '')) > 1000
           OR LENGTH(COALESCE(NULLIF(BTRIM(expense_owner), ''), '')) > 160
           OR LENGTH(COALESCE(NULLIF(BTRIM(file_name), ''), '')) > 255
           OR LENGTH(COALESCE(NULLIF(BTRIM(file_type), ''), '')) > 128
           OR LENGTH(COALESCE(NULLIF(BTRIM(file_bucket), ''), '')) > 255
           OR LENGTH(COALESCE(NULLIF(BTRIM(file_object_key), ''), '')) > 1024
           OR LENGTH(COALESCE(NULLIF(BTRIM(file_url), ''), '')) > 2000
           OR LENGTH(COALESCE(NULLIF(BTRIM(note), ''), '')) > 2000
           OR LENGTH(COALESCE(NULLIF(BTRIM(idempotency_key), ''), '')) > 128
           OR (NULLIF(BTRIM(idempotency_key), '') IS NOT NULL
               AND BTRIM(idempotency_key) !~ '^[A-Za-z0-9._:-]+$')
    ) THEN
        RAISE EXCEPTION 'receipt_vouchers contains an invalid descriptive or idempotency value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_vouchers
        WHERE NULLIF(BTRIM(idempotency_key), '') IS NOT NULL
        GROUP BY company_id, BTRIM(idempotency_key)
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'receipt_vouchers contains duplicate normalized idempotency keys';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_vouchers
        WHERE NOT pg_input_is_valid(COALESCE(NULLIF(BTRIM(amount), ''), '0'), 'numeric')
           OR NOT pg_input_is_valid(COALESCE(NULLIF(BTRIM(tax_amount), ''), '0'), 'numeric')
           OR NOT pg_input_is_valid(COALESCE(NULLIF(BTRIM(tax_rate), ''), '0'), 'numeric')
    ) THEN
        RAISE EXCEPTION 'receipt_vouchers contains an invalid monetary or tax-rate value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_vouchers
        WHERE COALESCE(NULLIF(BTRIM(amount), ''), '0')::NUMERIC < 0
           OR COALESCE(NULLIF(BTRIM(amount), ''), '0')::NUMERIC > 9999999999999999.9999
           OR SCALE(COALESCE(NULLIF(BTRIM(amount), ''), '0')::NUMERIC) > 4
           OR COALESCE(NULLIF(BTRIM(tax_amount), ''), '0')::NUMERIC < 0
           OR COALESCE(NULLIF(BTRIM(tax_amount), ''), '0')::NUMERIC
                > COALESCE(NULLIF(BTRIM(amount), ''), '0')::NUMERIC
           OR SCALE(COALESCE(NULLIF(BTRIM(tax_amount), ''), '0')::NUMERIC) > 4
           OR COALESCE(NULLIF(BTRIM(tax_rate), ''), '0')::NUMERIC NOT BETWEEN 0 AND 100
           OR SCALE(COALESCE(NULLIF(BTRIM(tax_rate), ''), '0')::NUMERIC) > 4
           OR file_size < 0
           OR version < 0
    ) THEN
        RAISE EXCEPTION 'receipt_vouchers contains an out-of-range amount, rate, file size, or version';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_vouchers
        WHERE BTRIM(issue_date) !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'
           OR NOT pg_input_is_valid(BTRIM(issue_date), 'date')
           OR (NULLIF(BTRIM(due_date), '') IS NOT NULL
               AND (
                   BTRIM(due_date) !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'
                   OR NOT pg_input_is_valid(BTRIM(due_date), 'date')
               ))
           OR NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
           OR (NULLIF(BTRIM(approved_at), '') IS NOT NULL
               AND NOT pg_input_is_valid(BTRIM(approved_at), 'timestamp with time zone'))
           OR (NULLIF(BTRIM(accounted_at), '') IS NOT NULL
               AND NOT pg_input_is_valid(BTRIM(accounted_at), 'timestamp with time zone'))
    ) THEN
        RAISE EXCEPTION 'receipt_vouchers contains an invalid date or lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_vouchers
        WHERE (NULLIF(BTRIM(due_date), '') IS NOT NULL
               AND BTRIM(due_date)::DATE < BTRIM(issue_date)::DATE)
           OR BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
           OR (approved_by_user_id IS NULL)
                IS DISTINCT FROM (NULLIF(BTRIM(approved_at), '') IS NULL)
           OR (LOWER(BTRIM(accounting_status)) = 'posted'
               AND NULLIF(BTRIM(accounted_at), '') IS NULL)
    ) THEN
        RAISE EXCEPTION 'receipt_vouchers contains an inconsistent business or lifecycle boundary';
    END IF;
END $$;

UPDATE receipt_vouchers
SET voucher_no = BTRIM(voucher_no),
    title = BTRIM(title),
    voucher_type = LOWER(BTRIM(voucher_type)),
    direction = LOWER(BTRIM(direction)),
    counterparty = BTRIM(counterparty),
    amount = COALESCE(NULLIF(BTRIM(amount), ''), '0'),
    tax_amount = COALESCE(NULLIF(BTRIM(tax_amount), ''), '0'),
    tax_rate = COALESCE(NULLIF(BTRIM(tax_rate), ''), '0'),
    tax_period = NULLIF(BTRIM(tax_period), ''),
    invoice_check_status = LOWER(BTRIM(invoice_check_status)),
    deduction_status = LOWER(BTRIM(deduction_status)),
    reimbursement_status = LOWER(BTRIM(reimbursement_status)),
    approval_status = LOWER(BTRIM(approval_status)),
    accounting_status = LOWER(BTRIM(accounting_status)),
    accounting_voucher_no = NULLIF(BTRIM(accounting_voucher_no), ''),
    accounting_entry = NULLIF(BTRIM(accounting_entry), ''),
    approved_at = NULLIF(BTRIM(approved_at), ''),
    accounted_at = NULLIF(BTRIM(accounted_at), ''),
    business_purpose = NULLIF(BTRIM(business_purpose), ''),
    expense_owner = NULLIF(BTRIM(expense_owner), ''),
    issue_date = BTRIM(issue_date),
    due_date = NULLIF(BTRIM(due_date), ''),
    status = LOWER(BTRIM(status)),
    file_name = NULLIF(BTRIM(file_name), ''),
    file_type = NULLIF(BTRIM(file_type), ''),
    file_storage_provider = LOWER(BTRIM(file_storage_provider)),
    file_bucket = NULLIF(BTRIM(file_bucket), ''),
    file_object_key = NULLIF(BTRIM(file_object_key), ''),
    file_url = NULLIF(BTRIM(file_url), ''),
    risk_level = LOWER(BTRIM(risk_level)),
    note = NULLIF(BTRIM(note), ''),
    idempotency_key = NULLIF(BTRIM(idempotency_key), ''),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE receipt_vouchers
    ALTER COLUMN tax_rate DROP DEFAULT;

ALTER TABLE receipt_vouchers
    ALTER COLUMN voucher_no TYPE VARCHAR(120),
    ALTER COLUMN title TYPE VARCHAR(200),
    ALTER COLUMN voucher_type TYPE VARCHAR(32),
    ALTER COLUMN direction TYPE VARCHAR(8),
    ALTER COLUMN counterparty TYPE VARCHAR(200),
    ALTER COLUMN amount TYPE NUMERIC(20, 4) USING amount::NUMERIC(20, 4),
    ALTER COLUMN tax_amount TYPE NUMERIC(20, 4) USING tax_amount::NUMERIC(20, 4),
    ALTER COLUMN tax_rate TYPE NUMERIC(7, 4) USING tax_rate::NUMERIC(7, 4),
    ALTER COLUMN tax_period TYPE VARCHAR(7),
    ALTER COLUMN invoice_check_status TYPE VARCHAR(16),
    ALTER COLUMN deduction_status TYPE VARCHAR(20),
    ALTER COLUMN reimbursement_status TYPE VARCHAR(20),
    ALTER COLUMN approval_status TYPE VARCHAR(20),
    ALTER COLUMN accounting_status TYPE VARCHAR(16),
    ALTER COLUMN accounting_voucher_no TYPE VARCHAR(120),
    ALTER COLUMN accounting_entry TYPE VARCHAR(4000),
    ALTER COLUMN approved_at TYPE TIMESTAMPTZ USING approved_at::TIMESTAMPTZ,
    ALTER COLUMN accounted_at TYPE TIMESTAMPTZ USING accounted_at::TIMESTAMPTZ,
    ALTER COLUMN business_purpose TYPE VARCHAR(1000),
    ALTER COLUMN expense_owner TYPE VARCHAR(160),
    ALTER COLUMN issue_date TYPE DATE USING issue_date::DATE,
    ALTER COLUMN due_date TYPE DATE USING due_date::DATE,
    ALTER COLUMN status TYPE VARCHAR(20),
    ALTER COLUMN file_name TYPE VARCHAR(255),
    ALTER COLUMN file_size TYPE BIGINT,
    ALTER COLUMN file_type TYPE VARCHAR(128),
    ALTER COLUMN file_storage_provider TYPE VARCHAR(32),
    ALTER COLUMN file_bucket TYPE VARCHAR(255),
    ALTER COLUMN file_object_key TYPE VARCHAR(1024),
    ALTER COLUMN file_url TYPE VARCHAR(2000),
    ALTER COLUMN risk_level TYPE VARCHAR(16),
    ALTER COLUMN note TYPE VARCHAR(2000),
    ALTER COLUMN idempotency_key TYPE VARCHAR(128),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ;

ALTER TABLE receipt_vouchers
    ALTER COLUMN tax_rate SET DEFAULT 0;

ALTER TABLE receipt_vouchers
    ADD CONSTRAINT uq_receipt_vouchers_company_id UNIQUE (company_id, id),
    ADD CONSTRAINT fk_receipt_vouchers_company
        FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_receipt_vouchers_company_transaction
        FOREIGN KEY (company_id, transaction_id)
        REFERENCES transactions(company_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_receipt_vouchers_operator
        FOREIGN KEY (operator_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_receipt_vouchers_approver
        FOREIGN KEY (approved_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_receipt_vouchers_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_receipt_vouchers_required_text
        CHECK (BTRIM(voucher_no) <> '' AND BTRIM(title) <> '' AND BTRIM(counterparty) <> ''),
    ADD CONSTRAINT ck_receipt_vouchers_voucher_type
        CHECK (voucher_type IN (
            'sales_invoice', 'purchase_invoice', 'receipt', 'bank_slip',
            'contract', 'reimbursement', 'tax_receipt'
        )),
    ADD CONSTRAINT ck_receipt_vouchers_direction
        CHECK (direction IN ('income', 'expense')),
    ADD CONSTRAINT ck_receipt_vouchers_amounts
        CHECK (amount >= 0 AND tax_amount >= 0 AND tax_amount <= amount AND tax_rate BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_receipt_vouchers_tax_period
        CHECK (tax_period IS NULL OR tax_period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    ADD CONSTRAINT ck_receipt_vouchers_invoice_check_status
        CHECK (invoice_check_status IN ('not_required', 'pending', 'verified', 'failed')),
    ADD CONSTRAINT ck_receipt_vouchers_deduction_status
        CHECK (deduction_status IN ('not_applicable', 'pending', 'deductible', 'deducted', 'transferred_out')),
    ADD CONSTRAINT ck_receipt_vouchers_reimbursement_status
        CHECK (reimbursement_status IN ('not_applicable', 'submitted', 'approved', 'paid', 'archived', 'rejected')),
    ADD CONSTRAINT ck_receipt_vouchers_approval_status
        CHECK (approval_status IN ('not_required', 'not_submitted', 'pending', 'approved', 'rejected')),
    ADD CONSTRAINT ck_receipt_vouchers_accounting_status
        CHECK (accounting_status IN ('not_started', 'draft', 'posted', 'reversed')),
    ADD CONSTRAINT ck_receipt_vouchers_status
        CHECK (status IN ('pending_review', 'verified', 'linked', 'archived', 'rejected')),
    ADD CONSTRAINT ck_receipt_vouchers_risk_level
        CHECK (risk_level IN ('low', 'medium', 'high', 'critical')),
    ADD CONSTRAINT ck_receipt_vouchers_file_storage_provider
        CHECK (file_storage_provider IN ('none', 'metadata_only', 'minio')),
    ADD CONSTRAINT ck_receipt_vouchers_dates
        CHECK (due_date IS NULL OR due_date >= issue_date),
    ADD CONSTRAINT ck_receipt_vouchers_file_size
        CHECK (file_size >= 0),
    ADD CONSTRAINT ck_receipt_vouchers_approval_audit
        CHECK ((approved_by_user_id IS NULL) = (approved_at IS NULL)),
    ADD CONSTRAINT ck_receipt_vouchers_accounting_lifecycle
        CHECK (accounting_status <> 'posted' OR accounted_at IS NOT NULL),
    ADD CONSTRAINT ck_receipt_vouchers_idempotency_key
        CHECK (
            idempotency_key IS NULL
            OR (idempotency_key <> '' AND idempotency_key ~ '^[A-Za-z0-9._:-]+$')
        ),
    ADD CONSTRAINT ck_receipt_vouchers_lifecycle
        CHECK (updated_at >= created_at),
    ADD CONSTRAINT ck_receipt_vouchers_version
        CHECK (version >= 0);

ALTER TABLE receipt_file_hashes
    DROP CONSTRAINT fk_receipt_file_hashes_voucher,
    ADD CONSTRAINT fk_receipt_file_hashes_company_voucher
        FOREIGN KEY (company_id, voucher_id)
        REFERENCES receipt_vouchers(company_id, id) ON DELETE CASCADE;

CREATE FUNCTION prevent_receipt_voucher_company_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.company_id <> OLD.company_id THEN
        RAISE EXCEPTION 'receipt voucher company cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_receipt_voucher_company_immutable
BEFORE UPDATE OF company_id ON receipt_vouchers
FOR EACH ROW
EXECUTE FUNCTION prevent_receipt_voucher_company_change();

DROP INDEX idx_receipt_vouchers_company_issue;
DROP INDEX idx_receipt_vouchers_company_status;
DROP INDEX idx_receipt_vouchers_company_tax_period;
DROP INDEX idx_receipt_vouchers_company_deduction;
DROP INDEX idx_receipt_vouchers_company_accounting;

CREATE INDEX idx_receipt_vouchers_company_issue
    ON receipt_vouchers(company_id, issue_date DESC, id);
CREATE INDEX idx_receipt_vouchers_company_status
    ON receipt_vouchers(company_id, status, issue_date DESC, id);
CREATE INDEX idx_receipt_vouchers_company_voucher_type
    ON receipt_vouchers(company_id, voucher_type, issue_date DESC, id);
CREATE INDEX idx_receipt_vouchers_company_tax_period
    ON receipt_vouchers(company_id, tax_period, issue_date DESC, id);
CREATE INDEX idx_receipt_vouchers_company_deduction
    ON receipt_vouchers(company_id, deduction_status, issue_date DESC, id);
CREATE INDEX idx_receipt_vouchers_company_accounting
    ON receipt_vouchers(company_id, accounting_status, issue_date DESC, id);
CREATE INDEX idx_receipt_vouchers_company_missing_transaction
    ON receipt_vouchers(company_id, issue_date DESC, id)
    WHERE transaction_id IS NULL;

COMMENT ON TABLE receipt_vouchers IS
    'Authoritative typed company-scoped receipt evidence and accounting workflow records.';
