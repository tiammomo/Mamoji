CREATE TABLE company_memberships (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    department_id BIGINT,
    role TEXT NOT NULL,
    scope TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_company_memberships_company_user UNIQUE (company_id, user_id),
    CONSTRAINT fk_company_memberships_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE,
    CONSTRAINT fk_company_memberships_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_company_memberships_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE SET NULL,
    CONSTRAINT ck_company_memberships_role CHECK (
        role IN ('founder', 'finance_admin', 'hr_admin', 'department_manager', 'employee', 'viewer')
    ),
    CONSTRAINT ck_company_memberships_scope CHECK (
        scope IN ('group', 'company', 'company_set', 'department', 'self', 'readonly')
    ),
    CONSTRAINT ck_company_memberships_status CHECK (status IN ('active', 'inactive'))
);

CREATE INDEX idx_company_memberships_user_status
    ON company_memberships(user_id, status, company_id);
CREATE INDEX idx_company_memberships_company_role
    ON company_memberships(company_id, role, status);

INSERT INTO company_memberships (company_id, user_id, role, scope, status)
SELECT id, owner_id, 'founder', 'company', 'active'
FROM companies
ON CONFLICT (company_id, user_id) DO NOTHING;

INSERT INTO company_memberships (company_id, user_id, department_id, role, scope, status)
SELECT company_id, user_id, department_id, access_role, access_scope,
       CASE WHEN status = 'departed' THEN 'inactive' ELSE 'active' END
FROM employees
WHERE user_id IS NOT NULL
ON CONFLICT (company_id, user_id) DO UPDATE SET
    department_id = EXCLUDED.department_id,
    role = CASE
        WHEN company_memberships.role = 'founder' THEN company_memberships.role
        ELSE EXCLUDED.role
    END,
    scope = CASE
        WHEN company_memberships.role = 'founder' THEN company_memberships.scope
        ELSE EXCLUDED.scope
    END,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;

ALTER TABLE accounts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE budgets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE transactions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE receipt_vouchers ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE approval_requests ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE transactions ADD COLUMN idempotency_key TEXT;
ALTER TABLE receipt_vouchers ADD COLUMN idempotency_key TEXT;
ALTER TABLE approval_requests ADD COLUMN idempotency_key TEXT;

CREATE UNIQUE INDEX uq_transactions_company_idempotency
    ON transactions(company_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uq_receipt_vouchers_company_idempotency
    ON receipt_vouchers(company_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uq_approval_requests_company_idempotency
    ON approval_requests(company_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

ALTER TABLE budgets ADD CONSTRAINT ck_budgets_company_positive
    CHECK (company_id IS NULL OR company_id > 0) NOT VALID;
ALTER TABLE budgets ADD CONSTRAINT ck_budgets_amount_format
    CHECK (amount ~ '^[0-9]+([.][0-9]{1,4})?$') NOT VALID;
ALTER TABLE budgets ADD CONSTRAINT ck_budgets_dates_format
    CHECK (start_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND end_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$') NOT VALID;
ALTER TABLE budgets ADD CONSTRAINT ck_budgets_threshold
    CHECK (warning_threshold BETWEEN 0 AND 100) NOT VALID;
ALTER TABLE budgets ADD CONSTRAINT ck_budgets_status
    CHECK (status BETWEEN 0 AND 3) NOT VALID;

ALTER TABLE transactions ADD CONSTRAINT ck_transactions_company_positive
    CHECK (company_id IS NULL OR company_id > 0) NOT VALID;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_type
    CHECK (type IN (1, 2, 3)) NOT VALID;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_amount_format
    CHECK (amount ~ '^[0-9]+([.][0-9]{1,4})?$') NOT VALID;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_date_format
    CHECK (date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$') NOT VALID;

ALTER TABLE accounts ADD CONSTRAINT ck_accounts_company_positive
    CHECK (company_id IS NULL OR company_id > 0) NOT VALID;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_currency
    CHECK (currency ~ '^[A-Z]{3}$') NOT VALID;

COMMENT ON TABLE company_memberships IS
    'Authoritative account-to-company authorization boundary; employee profiles are optional business records.';
COMMENT ON COLUMN transactions.idempotency_key IS
    'Caller supplied request key used to make create/import commands retry-safe inside one company.';
