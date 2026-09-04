DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM departments department
        WHERE NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = department.company_id)
    ) THEN
        RAISE EXCEPTION 'departments contains an orphaned company reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM departments
        WHERE BTRIM(name) = ''
           OR LENGTH(BTRIM(name)) > 120
           OR BTRIM(cost_center) = ''
           OR LENGTH(BTRIM(cost_center)) > 64
           OR status NOT IN (0, 1)
    ) THEN
        RAISE EXCEPTION 'departments contains invalid descriptive or status attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM departments
        WHERE NOT pg_input_is_valid(BTRIM(budget), 'numeric')
    ) THEN
        RAISE EXCEPTION 'departments contains an invalid budget value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM departments
        WHERE BTRIM(budget)::NUMERIC < 0
           OR BTRIM(budget)::NUMERIC > 999999999999999999.99
           OR SCALE(BTRIM(budget)::NUMERIC) > 2
    ) THEN
        RAISE EXCEPTION 'departments contains an out-of-range budget value';
    END IF;

    IF EXISTS (
        SELECT company_id, LOWER(BTRIM(name))
        FROM departments
        GROUP BY company_id, LOWER(BTRIM(name))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'departments contains duplicate normalized names in a company';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM departments department
        WHERE department.manager_employee_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM employees employee
              WHERE employee.id = department.manager_employee_id
                AND employee.company_id = department.company_id
          )
    ) THEN
        RAISE EXCEPTION 'departments contains an orphaned or cross-company manager';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees employee
        WHERE employee.department_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM departments department
              WHERE department.id = employee.department_id
                AND department.company_id = employee.company_id
          )
    ) THEN
        RAISE EXCEPTION 'employees contains an orphaned or cross-company department';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM company_memberships membership
        WHERE membership.department_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM departments department
              WHERE department.id = membership.department_id
                AND department.company_id = membership.company_id
          )
    ) THEN
        RAISE EXCEPTION 'company_memberships contains a cross-company department';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM departments
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'departments contains an invalid lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM departments
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'departments contains a lifecycle timestamp before creation';
    END IF;
END $$;

UPDATE departments
SET name = BTRIM(name),
    cost_center = BTRIM(cost_center),
    budget = BTRIM(budget),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE departments
    ALTER COLUMN name TYPE VARCHAR(120),
    ALTER COLUMN cost_center TYPE VARCHAR(64),
    ALTER COLUMN budget TYPE NUMERIC(20, 2) USING budget::NUMERIC(20, 2),
    ALTER COLUMN status TYPE SMALLINT USING status::SMALLINT,
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ;

ALTER TABLE departments
    ADD CONSTRAINT uq_departments_id_company UNIQUE (id, company_id),
    ADD CONSTRAINT fk_departments_company
        FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_departments_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_departments_name
        CHECK (name = BTRIM(name) AND name <> ''),
    ADD CONSTRAINT ck_departments_cost_center
        CHECK (cost_center = BTRIM(cost_center) AND cost_center <> ''),
    ADD CONSTRAINT ck_departments_budget
        CHECK (budget >= 0),
    ADD CONSTRAINT ck_departments_status
        CHECK (status IN (0, 1)),
    ADD CONSTRAINT ck_departments_lifecycle
        CHECK (updated_at >= created_at);

CREATE UNIQUE INDEX uq_departments_company_normalized_name
    ON departments(company_id, LOWER(name));

ALTER TABLE employees
    ADD CONSTRAINT uq_employees_id_company UNIQUE (id, company_id),
    ADD CONSTRAINT fk_employees_department_company
        FOREIGN KEY (department_id, company_id)
        REFERENCES departments(id, company_id)
        ON DELETE SET NULL (department_id);

ALTER TABLE company_memberships
    DROP CONSTRAINT fk_company_memberships_department,
    ADD CONSTRAINT fk_company_memberships_department_company
        FOREIGN KEY (department_id, company_id)
        REFERENCES departments(id, company_id)
        ON DELETE SET NULL (department_id);

ALTER TABLE departments
    ADD CONSTRAINT fk_departments_manager_company
        FOREIGN KEY (manager_employee_id, company_id)
        REFERENCES employees(id, company_id)
        ON DELETE SET NULL (manager_employee_id);

CREATE FUNCTION prevent_department_company_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.company_id <> OLD.company_id THEN
        RAISE EXCEPTION 'department company cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_department_company_immutable
BEFORE UPDATE OF company_id ON departments
FOR EACH ROW
EXECUTE FUNCTION prevent_department_company_change();

COMMENT ON TABLE departments IS
    'Authoritative People Core organization units with company-scoped employee and manager references.';
