-- Complete the employee compensation schema used by EnterpriseStore. These
-- columns deliberately match the text-based money representation introduced in
-- V1 so the migration is safe for existing installations.
ALTER TABLE employees ADD COLUMN IF NOT EXISTS overtime_base TEXT NOT NULL DEFAULT '0';
ALTER TABLE employees ADD COLUMN IF NOT EXISTS weekday_overtime_hours TEXT NOT NULL DEFAULT '0';
ALTER TABLE employees ADD COLUMN IF NOT EXISTS rest_day_overtime_hours TEXT NOT NULL DEFAULT '0';
ALTER TABLE employees ADD COLUMN IF NOT EXISTS holiday_overtime_hours TEXT NOT NULL DEFAULT '0';
ALTER TABLE employees ADD COLUMN IF NOT EXISTS overtime_pay TEXT NOT NULL DEFAULT '0';
ALTER TABLE employees ADD COLUMN IF NOT EXISTS overtime_policy_note TEXT;

-- Accounting records were historically scoped only by the owning user. Keep
-- the new subject key nullable while old rows are assigned, then let the
-- application finish rows belonging to users who access a company as an
-- employee rather than as its owner.
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS company_id BIGINT;
ALTER TABLE categories ADD COLUMN IF NOT EXISTS company_id BIGINT;
ALTER TABLE budgets ADD COLUMN IF NOT EXISTS company_id BIGINT;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS company_id BIGINT;
ALTER TABLE ledgers ADD COLUMN IF NOT EXISTS company_id BIGINT;
ALTER TABLE recurring_items ADD COLUMN IF NOT EXISTS company_id BIGINT;

UPDATE ledgers l
SET company_id = (
    SELECT c.id FROM companies c WHERE c.owner_id = l.owner_id ORDER BY c.id LIMIT 1
)
WHERE l.company_id IS NULL;

UPDATE accounts a
SET company_id = COALESCE(
    (SELECT l.company_id FROM ledgers l WHERE l.id = a.ledger_id),
    (SELECT c.id FROM companies c WHERE c.owner_id = a.user_id ORDER BY c.id LIMIT 1)
)
WHERE a.company_id IS NULL;

UPDATE categories category
SET company_id = (
    SELECT c.id FROM companies c WHERE c.owner_id = category.user_id ORDER BY c.id LIMIT 1
)
WHERE category.company_id IS NULL;

UPDATE budgets b
SET company_id = COALESCE(
    (SELECT l.company_id FROM ledgers l WHERE l.id = b.ledger_id),
    (SELECT category.company_id FROM categories category WHERE category.id = b.category_id),
    (SELECT c.id FROM companies c WHERE c.owner_id = b.user_id ORDER BY c.id LIMIT 1)
)
WHERE b.company_id IS NULL;

UPDATE transactions transaction_record
SET company_id = COALESCE(
    (SELECT a.company_id FROM accounts a WHERE a.id = transaction_record.account_id),
    (SELECT category.company_id FROM categories category WHERE category.id = transaction_record.category_id),
    (SELECT l.company_id FROM ledgers l WHERE l.id = transaction_record.family_id),
    (SELECT c.id FROM companies c WHERE c.owner_id = transaction_record.user_id ORDER BY c.id LIMIT 1)
)
WHERE transaction_record.company_id IS NULL;

UPDATE recurring_items recurring
SET company_id = (
    SELECT c.id FROM companies c WHERE c.owner_id = recurring.user_id ORDER BY c.id LIMIT 1
)
WHERE recurring.company_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_accounts_company_user_status
    ON accounts(company_id, user_id, status);
CREATE INDEX IF NOT EXISTS idx_categories_company_user_type
    ON categories(company_id, user_id, type);
CREATE INDEX IF NOT EXISTS idx_budgets_company_user_dates
    ON budgets(company_id, user_id, start_date, end_date);
CREATE INDEX IF NOT EXISTS idx_transactions_company_user_date
    ON transactions(company_id, user_id, date);
CREATE INDEX IF NOT EXISTS idx_ledgers_company_owner_default
    ON ledgers(company_id, owner_id, is_default);
CREATE INDEX IF NOT EXISTS idx_recurring_company_user_status_next
    ON recurring_items(company_id, user_id, status, next_execution);

-- Enforce all new writes immediately while allowing operators to inspect and
-- repair any pre-existing orphan rows before validating the constraints. Null
-- company ids remain temporarily supported because the bootstrap sequence
-- creates the initial accounting workspace before its company subject exists.
ALTER TABLE accounts
    ADD CONSTRAINT fk_accounts_company FOREIGN KEY (company_id) REFERENCES companies(id) NOT VALID;
ALTER TABLE categories
    ADD CONSTRAINT fk_categories_company FOREIGN KEY (company_id) REFERENCES companies(id) NOT VALID;
ALTER TABLE budgets
    ADD CONSTRAINT fk_budgets_company FOREIGN KEY (company_id) REFERENCES companies(id) NOT VALID;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_company FOREIGN KEY (company_id) REFERENCES companies(id) NOT VALID;
ALTER TABLE ledgers
    ADD CONSTRAINT fk_ledgers_company FOREIGN KEY (company_id) REFERENCES companies(id) NOT VALID;
ALTER TABLE recurring_items
    ADD CONSTRAINT fk_recurring_company FOREIGN KEY (company_id) REFERENCES companies(id) NOT VALID;

ALTER TABLE accounts
    ADD CONSTRAINT fk_accounts_ledger FOREIGN KEY (ledger_id) REFERENCES ledgers(id) NOT VALID;
ALTER TABLE budgets
    ADD CONSTRAINT fk_budgets_ledger FOREIGN KEY (ledger_id) REFERENCES ledgers(id) NOT VALID;
ALTER TABLE budgets
    ADD CONSTRAINT fk_budgets_category FOREIGN KEY (category_id) REFERENCES categories(id) NOT VALID;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_ledger FOREIGN KEY (family_id) REFERENCES ledgers(id) NOT VALID;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_category FOREIGN KEY (category_id) REFERENCES categories(id) NOT VALID;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_account FOREIGN KEY (account_id) REFERENCES accounts(id) NOT VALID;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_original FOREIGN KEY (original_transaction_id) REFERENCES transactions(id) NOT VALID;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_budget FOREIGN KEY (budget_id) REFERENCES budgets(id) NOT VALID;
