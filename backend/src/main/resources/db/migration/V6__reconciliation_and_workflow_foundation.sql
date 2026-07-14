CREATE TABLE IF NOT EXISTS account_reconciliations (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    statement_date TEXT NOT NULL,
    statement_balance TEXT NOT NULL,
    system_balance TEXT NOT NULL,
    difference TEXT NOT NULL,
    status TEXT NOT NULL,
    note TEXT,
    created_by BIGINT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_account_reconciliations_account_date
    ON account_reconciliations(account_id, statement_date DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_account_reconciliations_company_status
    ON account_reconciliations(company_id, status, statement_date DESC);

CREATE TABLE IF NOT EXISTS approval_requests (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    request_type TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id BIGINT,
    title TEXT NOT NULL,
    amount TEXT NOT NULL DEFAULT '0',
    applicant_user_id BIGINT NOT NULL,
    assignee_user_id BIGINT,
    status TEXT NOT NULL DEFAULT 'pending',
    current_step TEXT NOT NULL DEFAULT 'review',
    description TEXT,
    decided_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS approval_actions (
    id BIGSERIAL PRIMARY KEY,
    request_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    action TEXT NOT NULL,
    comment TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_approval_requests_company_status
    ON approval_requests(company_id, status, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_approval_requests_assignee_status
    ON approval_requests(assignee_user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_approval_actions_request
    ON approval_actions(request_id, id);

CREATE INDEX IF NOT EXISTS idx_transactions_company_user_date_id
    ON transactions(company_id, user_id, date DESC, id DESC);
