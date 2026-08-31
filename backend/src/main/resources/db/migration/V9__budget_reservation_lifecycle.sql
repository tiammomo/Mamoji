CREATE TABLE budget_reservations (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    company_id BIGINT NOT NULL,
    budget_id BIGINT NOT NULL,
    transaction_id BIGINT,
    source_transaction_id BIGINT,
    reference_key VARCHAR(160) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    confirmed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    release_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_budget_reservations_company_reference UNIQUE (company_id, reference_key),
    CONSTRAINT uq_budget_reservations_transaction UNIQUE (transaction_id),
    CONSTRAINT fk_budget_reservations_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE,
    CONSTRAINT fk_budget_reservations_budget FOREIGN KEY (budget_id) REFERENCES budgets(id) ON DELETE CASCADE,
    CONSTRAINT fk_budget_reservations_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_reservations_user FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_budget_reservations_amount CHECK (amount > 0),
    CONSTRAINT ck_budget_reservations_status CHECK (status IN ('reserved', 'confirmed', 'released')),
    CONSTRAINT ck_budget_reservations_transaction_trace CHECK (
        transaction_id IS NULL OR source_transaction_id = transaction_id
    ),
    CONSTRAINT ck_budget_reservations_terminal_data CHECK (
        (status = 'reserved' AND transaction_id IS NULL AND source_transaction_id IS NULL
            AND confirmed_at IS NULL AND released_at IS NULL)
        OR (status = 'confirmed' AND transaction_id IS NOT NULL AND source_transaction_id IS NOT NULL
            AND confirmed_at IS NOT NULL AND released_at IS NULL)
        OR (status = 'released' AND transaction_id IS NULL AND confirmed_at IS NULL AND released_at IS NOT NULL)
    )
);

CREATE INDEX idx_budget_reservations_budget_status
    ON budget_reservations(budget_id, status, id);
CREATE INDEX idx_budget_reservations_company_created
    ON budget_reservations(company_id, created_at DESC, id DESC);
CREATE INDEX idx_budget_reservations_source_transaction
    ON budget_reservations(company_id, source_transaction_id)
    WHERE source_transaction_id IS NOT NULL;

COMMENT ON TABLE budget_reservations IS
    'Concurrency-safe budget capacity ledger; reserved amounts consume availability until confirmed or released.';
COMMENT ON COLUMN budget_reservations.source_transaction_id IS
    'Immutable audit trace retained after the mutable transaction link is released or the transaction is deleted.';
