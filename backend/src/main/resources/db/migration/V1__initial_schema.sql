CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    nickname TEXT NOT NULL,
    avatar TEXT NOT NULL,
    family_id BIGINT,
    role INTEGER NOT NULL,
    permissions INTEGER NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS accounts (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    sub_type TEXT,
    bank TEXT,
    account_no TEXT,
    opening_bank TEXT,
    currency TEXT NOT NULL DEFAULT 'CNY',
    balance TEXT NOT NULL,
    available_balance TEXT NOT NULL DEFAULT '0',
    credit_limit TEXT NOT NULL DEFAULT '0',
    frozen_amount TEXT NOT NULL DEFAULT '0',
    include_in_net_worth INTEGER NOT NULL,
    user_id BIGINT NOT NULL,
    ledger_id BIGINT,
    status INTEGER NOT NULL,
    opened_at TEXT,
    last_reconciled_at TEXT,
    owner_name TEXT,
    purpose TEXT,
    reconciliation_status TEXT NOT NULL DEFAULT 'pending',
    risk_level TEXT NOT NULL DEFAULT 'low',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS categories (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    icon TEXT NOT NULL,
    color TEXT NOT NULL,
    type TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    status INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS budgets (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    amount TEXT NOT NULL,
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL,
    warning_threshold INTEGER NOT NULL,
    status INTEGER NOT NULL,
    spent TEXT NOT NULL,
    remaining_amount TEXT NOT NULL,
    usage_rate REAL NOT NULL,
    warning_reached INTEGER NOT NULL,
    risk_level TEXT NOT NULL,
    risk_message TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    ledger_id BIGINT,
    category_id BIGINT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    family_id BIGINT,
    type INTEGER NOT NULL,
    amount TEXT NOT NULL,
    category_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    date TEXT NOT NULL,
    note TEXT NOT NULL,
    original_transaction_id BIGINT,
    refunded_amount TEXT NOT NULL,
    is_refundable INTEGER NOT NULL,
    budget_id BIGINT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ledgers (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    currency TEXT NOT NULL,
    owner_id BIGINT NOT NULL,
    is_default INTEGER NOT NULL,
    status INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ledger_members (
    id BIGSERIAL PRIMARY KEY,
    ledger_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role TEXT NOT NULL,
    nickname TEXT,
    avatar TEXT,
    joined_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS recurring_items (
    id TEXT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name TEXT NOT NULL,
    type INTEGER NOT NULL,
    amount TEXT NOT NULL,
    frequency TEXT NOT NULL,
    interval_value INTEGER NOT NULL,
    day_of_week INTEGER,
    day_of_month INTEGER,
    month_of_year INTEGER,
    start_date TEXT NOT NULL,
    end_date TEXT,
    last_executed TEXT,
    next_execution TEXT NOT NULL,
    status INTEGER NOT NULL,
    execution_count INTEGER NOT NULL,
    note TEXT
);

CREATE TABLE IF NOT EXISTS auth_tokens (
    token TEXT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS registration_invites (
    id BIGSERIAL PRIMARY KEY,
    token TEXT NOT NULL UNIQUE,
    email TEXT NOT NULL,
    role INTEGER NOT NULL DEFAULT 2,
    permissions INTEGER NOT NULL DEFAULT 15,
    expires_at TEXT NOT NULL,
    accepted_at TEXT,
    accepted_user_id BIGINT,
    invited_by_user_id BIGINT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS companies (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    entity_type TEXT NOT NULL DEFAULT 'company',
    credit_code TEXT,
    industry TEXT NOT NULL,
    taxpayer_type TEXT NOT NULL,
    currency TEXT NOT NULL,
    country TEXT NOT NULL DEFAULT '中国',
    province TEXT NOT NULL DEFAULT '',
    city TEXT NOT NULL DEFAULT '',
    district TEXT NOT NULL DEFAULT '',
    registered_address TEXT,
    operating_region TEXT NOT NULL DEFAULT '',
    tax_authority TEXT,
    policy_profile_key TEXT NOT NULL DEFAULT 'CN-DEFAULT-DEMO-POLICY',
    fiscal_year_start_month INTEGER NOT NULL DEFAULT 1,
    owner_id BIGINT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS departments (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    name TEXT NOT NULL,
    cost_center TEXT NOT NULL,
    manager_employee_id BIGINT,
    budget TEXT NOT NULL,
    status INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS employees (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    user_id BIGINT,
    department_id BIGINT,
    employee_no TEXT,
    name TEXT NOT NULL,
    legal_name TEXT,
    preferred_name TEXT,
    email TEXT NOT NULL,
    phone TEXT,
    position TEXT NOT NULL,
    direct_manager_employee_id BIGINT,
    job_level TEXT,
    work_location TEXT,
    employment_type TEXT NOT NULL,
    status TEXT NOT NULL,
    access_role TEXT NOT NULL DEFAULT 'employee',
    access_scope TEXT NOT NULL DEFAULT 'self',
    hire_date TEXT NOT NULL,
    leave_date TEXT,
    probation_start_date TEXT,
    probation_end_date TEXT,
    contract_start_date TEXT,
    contract_end_date TEXT,
    contract_type TEXT,
    contract_status TEXT,
    education_level TEXT,
    graduation_school TEXT,
    major TEXT,
    graduation_date TEXT,
    graduation_year INTEGER,
    graduate_status TEXT,
    skill_tags TEXT,
    resume_summary TEXT,
    material_status TEXT,
    profile_verified_at TEXT,
    profile_verified_by BIGINT,
    salary TEXT NOT NULL,
    social_insurance TEXT NOT NULL,
    housing_fund TEXT NOT NULL,
    tax_estimate TEXT NOT NULL,
    social_insurance_base TEXT NOT NULL DEFAULT '0',
    social_insurance_personal_rate TEXT NOT NULL DEFAULT '0',
    social_insurance_company_rate TEXT NOT NULL DEFAULT '0',
    social_insurance_personal_amount TEXT NOT NULL DEFAULT '0',
    social_insurance_company_amount TEXT NOT NULL DEFAULT '0',
    housing_fund_base TEXT NOT NULL DEFAULT '0',
    housing_fund_personal_rate TEXT NOT NULL DEFAULT '0',
    housing_fund_company_rate TEXT NOT NULL DEFAULT '0',
    housing_fund_personal_amount TEXT NOT NULL DEFAULT '0',
    housing_fund_company_amount TEXT NOT NULL DEFAULT '0',
    personal_deduction TEXT NOT NULL DEFAULT '0',
    net_pay_estimate TEXT NOT NULL DEFAULT '0',
    social_insurance_region TEXT NOT NULL DEFAULT '深圳',
    hukou_type TEXT NOT NULL DEFAULT 'non_local',
    medical_tier TEXT NOT NULL DEFAULT 'tier1',
    pension_base TEXT NOT NULL DEFAULT '0',
    medical_base TEXT NOT NULL DEFAULT '0',
    unemployment_base TEXT NOT NULL DEFAULT '0',
    work_injury_base TEXT NOT NULL DEFAULT '0',
    maternity_base TEXT NOT NULL DEFAULT '0',
    work_injury_company_rate TEXT NOT NULL DEFAULT '0.2',
    social_insurance_policy_note TEXT,
    monthly_cost TEXT NOT NULL,
    emergency_contact TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS employee_certificates (
    id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL,
    name TEXT NOT NULL,
    category TEXT,
    level TEXT,
    issuer TEXT,
    certificate_no TEXT,
    issue_date TEXT,
    expiry_date TEXT,
    verification_status TEXT NOT NULL DEFAULT 'unverified',
    material_status TEXT NOT NULL DEFAULT 'missing',
    note TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS employee_experiences (
    id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL,
    type TEXT NOT NULL DEFAULT 'work',
    organization TEXT NOT NULL,
    title TEXT,
    start_date TEXT,
    end_date TEXT,
    description TEXT,
    achievements TEXT,
    skills TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS employment_events (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    type TEXT NOT NULL,
    effective_date TEXT NOT NULL,
    note TEXT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tax_items (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    name TEXT NOT NULL,
    period TEXT NOT NULL,
    tax_type TEXT NOT NULL,
    taxable_amount TEXT NOT NULL,
    tax_amount TEXT NOT NULL,
    paid_amount TEXT NOT NULL,
    deductible_amount TEXT NOT NULL DEFAULT '0',
    tax_rate TEXT NOT NULL DEFAULT '0',
    due_date TEXT NOT NULL,
    status TEXT NOT NULL,
    filing_status TEXT NOT NULL DEFAULT 'not_started',
    payment_status TEXT NOT NULL DEFAULT 'unpaid',
    frequency TEXT NOT NULL DEFAULT 'monthly',
    declaration_date TEXT,
    payment_date TEXT,
    responsible_person TEXT,
    risk_level TEXT NOT NULL DEFAULT 'medium',
    policy_basis TEXT,
    source_type TEXT NOT NULL DEFAULT 'manual',
    note TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS entity_transfers (
    id BIGSERIAL PRIMARY KEY,
    from_entity_id BIGINT NOT NULL,
    to_entity_id BIGINT NOT NULL,
    transfer_type TEXT NOT NULL,
    amount TEXT NOT NULL,
    currency TEXT NOT NULL,
    transfer_date TEXT NOT NULL,
    note TEXT,
    status TEXT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS receipt_vouchers (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    transaction_id BIGINT,
    voucher_no TEXT NOT NULL,
    title TEXT NOT NULL,
    voucher_type TEXT NOT NULL,
    direction TEXT NOT NULL,
    counterparty TEXT NOT NULL,
    amount TEXT NOT NULL,
    tax_amount TEXT NOT NULL,
    tax_rate TEXT NOT NULL DEFAULT '0',
    tax_period TEXT,
    invoice_check_status TEXT NOT NULL DEFAULT 'not_required',
    deduction_status TEXT NOT NULL DEFAULT 'not_applicable',
    reimbursement_status TEXT NOT NULL DEFAULT 'not_applicable',
    approval_status TEXT NOT NULL DEFAULT 'not_required',
    accounting_status TEXT NOT NULL DEFAULT 'not_started',
    accounting_voucher_no TEXT,
    accounting_entry TEXT,
    approved_by_user_id BIGINT,
    approved_at TEXT,
    accounted_at TEXT,
    business_purpose TEXT,
    expense_owner TEXT,
    issue_date TEXT NOT NULL,
    due_date TEXT,
    status TEXT NOT NULL,
    file_name TEXT,
    file_size INTEGER NOT NULL,
    file_type TEXT,
    file_storage_provider TEXT NOT NULL DEFAULT 'metadata_only',
    file_bucket TEXT,
    file_object_key TEXT,
    file_url TEXT,
    risk_level TEXT NOT NULL,
    note TEXT,
    operator_user_id BIGINT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id BIGINT NOT NULL,
    action TEXT NOT NULL,
    summary TEXT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_name TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS payroll_runs (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    period TEXT NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    employee_count INTEGER NOT NULL,
    salary_total TEXT NOT NULL,
    social_personal_total TEXT NOT NULL,
    social_company_total TEXT NOT NULL,
    housing_personal_total TEXT NOT NULL,
    housing_company_total TEXT NOT NULL,
    tax_total TEXT NOT NULL,
    personal_deduction_total TEXT NOT NULL,
    net_pay_total TEXT NOT NULL,
    company_cost_total TEXT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    closed_by_user_id BIGINT,
    closed_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS payroll_run_items (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    employee_name TEXT NOT NULL,
    department_name TEXT,
    period TEXT NOT NULL,
    salary TEXT NOT NULL,
    payable_salary TEXT NOT NULL,
    social_personal_amount TEXT NOT NULL,
    social_company_amount TEXT NOT NULL,
    housing_personal_amount TEXT NOT NULL,
    housing_company_amount TEXT NOT NULL,
    tax_amount TEXT NOT NULL,
    personal_deduction TEXT NOT NULL,
    net_pay TEXT NOT NULL,
    company_cost TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_accounts_user_status ON accounts(user_id, status);
CREATE INDEX IF NOT EXISTS idx_accounts_user_type ON accounts(user_id, type);
CREATE INDEX IF NOT EXISTS idx_accounts_user_reconciliation ON accounts(user_id, reconciliation_status);
CREATE INDEX IF NOT EXISTS idx_accounts_user_risk ON accounts(user_id, risk_level);
CREATE INDEX IF NOT EXISTS idx_categories_user_type ON categories(user_id, type);
CREATE INDEX IF NOT EXISTS idx_budgets_user_status_dates ON budgets(user_id, status, start_date, end_date);
CREATE INDEX IF NOT EXISTS idx_budgets_category_dates ON budgets(category_id, start_date, end_date);
CREATE INDEX IF NOT EXISTS idx_transactions_user_date ON transactions(user_id, date);
CREATE INDEX IF NOT EXISTS idx_transactions_user_type_date ON transactions(user_id, type, date);
CREATE INDEX IF NOT EXISTS idx_transactions_category_date ON transactions(category_id, date);
CREATE INDEX IF NOT EXISTS idx_transactions_account_date ON transactions(account_id, date);
CREATE INDEX IF NOT EXISTS idx_transactions_budget ON transactions(budget_id);
CREATE INDEX IF NOT EXISTS idx_ledgers_owner_default ON ledgers(owner_id, is_default);
CREATE INDEX IF NOT EXISTS idx_ledger_members_ledger_user ON ledger_members(ledger_id, user_id);
CREATE INDEX IF NOT EXISTS idx_recurring_user_status_next ON recurring_items(user_id, status, next_execution);
CREATE INDEX IF NOT EXISTS idx_auth_tokens_user ON auth_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_auth_tokens_expires ON auth_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_registration_invites_email ON registration_invites(email, accepted_at, expires_at);
CREATE INDEX IF NOT EXISTS idx_registration_invites_inviter ON registration_invites(invited_by_user_id, created_at);

CREATE INDEX IF NOT EXISTS idx_companies_owner ON companies(owner_id);
CREATE INDEX IF NOT EXISTS idx_companies_entity_type ON companies(entity_type);
CREATE INDEX IF NOT EXISTS idx_departments_company ON departments(company_id);
CREATE INDEX IF NOT EXISTS idx_employees_company_status ON employees(company_id, status);
CREATE INDEX IF NOT EXISTS idx_employees_user ON employees(user_id);
CREATE INDEX IF NOT EXISTS idx_employees_department ON employees(department_id);
CREATE INDEX IF NOT EXISTS idx_employees_graduation_year ON employees(company_id, graduation_year);
CREATE INDEX IF NOT EXISTS idx_employee_certificates_employee ON employee_certificates(employee_id);
CREATE INDEX IF NOT EXISTS idx_employee_certificates_expiry ON employee_certificates(employee_id, expiry_date);
CREATE INDEX IF NOT EXISTS idx_employee_experiences_employee ON employee_experiences(employee_id);
CREATE INDEX IF NOT EXISTS idx_employment_events_company_date ON employment_events(company_id, effective_date);
CREATE INDEX IF NOT EXISTS idx_tax_items_company_due_status ON tax_items(company_id, due_date, status);
CREATE INDEX IF NOT EXISTS idx_tax_items_company_type_period ON tax_items(company_id, tax_type, period);
CREATE INDEX IF NOT EXISTS idx_tax_items_company_risk ON tax_items(company_id, risk_level);
CREATE INDEX IF NOT EXISTS idx_entity_transfers_from_date ON entity_transfers(from_entity_id, transfer_date);
CREATE INDEX IF NOT EXISTS idx_entity_transfers_to_date ON entity_transfers(to_entity_id, transfer_date);
CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_company_issue ON receipt_vouchers(company_id, issue_date);
CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_company_status ON receipt_vouchers(company_id, status);
CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_transaction ON receipt_vouchers(transaction_id);
CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_company_tax_period ON receipt_vouchers(company_id, tax_period);
CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_company_deduction ON receipt_vouchers(company_id, deduction_status);
CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_company_accounting ON receipt_vouchers(company_id, accounting_status);
CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_file_object ON receipt_vouchers(file_storage_provider, file_object_key);
CREATE INDEX IF NOT EXISTS idx_audit_logs_entity ON audit_logs(company_id, entity_type, entity_id, id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs(created_at, id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor ON audit_logs(actor_user_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_payroll_runs_company_period ON payroll_runs(company_id, period);
CREATE INDEX IF NOT EXISTS idx_payroll_runs_company_status ON payroll_runs(company_id, status, period);
CREATE INDEX IF NOT EXISTS idx_payroll_run_items_run ON payroll_run_items(run_id);
CREATE INDEX IF NOT EXISTS idx_payroll_run_items_employee_period ON payroll_run_items(employee_id, period);
