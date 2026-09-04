DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM employment_events event
        WHERE NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = event.company_id)
    ) THEN
        RAISE EXCEPTION 'employment_events contains an orphaned company reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employment_events event
        WHERE NOT EXISTS (
            SELECT 1
            FROM employees employee
            WHERE employee.id = event.employee_id
              AND employee.company_id = event.company_id
        )
    ) THEN
        RAISE EXCEPTION 'employment_events contains an orphaned or cross-company employee';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employment_events event
        WHERE NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = event.operator_user_id)
    ) THEN
        RAISE EXCEPTION 'employment_events contains an orphaned operator';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employment_events
        WHERE LOWER(BTRIM(type)) NOT IN ('onboard', 'offboard', 'status_change')
           OR BTRIM(note) = ''
           OR LENGTH(BTRIM(note)) > 1000
    ) THEN
        RAISE EXCEPTION 'employment_events contains an invalid type or note';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employment_events
        WHERE NOT pg_input_is_valid(BTRIM(effective_date), 'date')
           OR NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'employment_events contains an invalid date or timestamp';
    END IF;
END $$;

UPDATE employment_events
SET type = LOWER(BTRIM(type)),
    effective_date = BTRIM(effective_date),
    note = BTRIM(note),
    created_at = BTRIM(created_at);

ALTER TABLE employment_events
    ALTER COLUMN type TYPE VARCHAR(32),
    ALTER COLUMN effective_date TYPE DATE USING effective_date::DATE,
    ALTER COLUMN note TYPE VARCHAR(1000),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ;

ALTER TABLE employment_events
    ADD CONSTRAINT fk_employment_events_company
        FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_employment_events_employee_company
        FOREIGN KEY (employee_id, company_id)
        REFERENCES employees(id, company_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_employment_events_operator
        FOREIGN KEY (operator_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_employment_events_company_positive CHECK (company_id > 0),
    ADD CONSTRAINT ck_employment_events_employee_positive CHECK (employee_id > 0),
    ADD CONSTRAINT ck_employment_events_operator_positive CHECK (operator_user_id > 0),
    ADD CONSTRAINT ck_employment_events_type
        CHECK (type IN ('onboard', 'offboard', 'status_change')),
    ADD CONSTRAINT ck_employment_events_note CHECK (note = BTRIM(note) AND note <> '');

CREATE INDEX idx_employment_events_employee_date
    ON employment_events(employee_id, effective_date DESC, id);

CREATE INDEX idx_employment_events_operator_created
    ON employment_events(operator_user_id, created_at DESC, id);

CREATE FUNCTION prevent_employment_event_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'employment events are append-only';
END;
$$;

CREATE TRIGGER trg_employment_event_append_only
BEFORE UPDATE ON employment_events
FOR EACH ROW
EXECUTE FUNCTION prevent_employment_event_update();

COMMENT ON TABLE employment_events IS
    'Append-only People Core employee lifecycle history with company-scoped employee ownership.';
