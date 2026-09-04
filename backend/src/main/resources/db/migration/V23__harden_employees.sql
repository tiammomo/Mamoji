DO $$
DECLARE
    numeric_column TEXT;
    invalid_value BOOLEAN;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM employees employee
        WHERE NOT EXISTS (SELECT 1 FROM companies company WHERE company.id = employee.company_id)
    ) THEN
        RAISE EXCEPTION 'employees contains an orphaned company reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees employee
        WHERE employee.user_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = employee.user_id)
    ) THEN
        RAISE EXCEPTION 'employees contains an orphaned user reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees employee
        WHERE employee.profile_verified_by IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = employee.profile_verified_by)
    ) THEN
        RAISE EXCEPTION 'employees contains an orphaned profile verifier';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees employee
        WHERE employee.direct_manager_employee_id IS NOT NULL
          AND (
              employee.direct_manager_employee_id = employee.id
              OR NOT EXISTS (
                  SELECT 1
                  FROM employees manager
                  WHERE manager.id = employee.direct_manager_employee_id
                    AND manager.company_id = employee.company_id
              )
          )
    ) THEN
        RAISE EXCEPTION 'employees contains an invalid or cross-company direct manager';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees
        WHERE BTRIM(name) = '' OR LENGTH(BTRIM(name)) > 120
           OR BTRIM(email) = '' OR LENGTH(BTRIM(email)) > 320
           OR POSITION('@' IN BTRIM(email)) <= 1
           OR BTRIM(position) = '' OR LENGTH(BTRIM(position)) > 160
           OR LENGTH(COALESCE(NULLIF(BTRIM(employee_no), ''), '')) > 64
           OR LENGTH(COALESCE(NULLIF(BTRIM(legal_name), ''), '')) > 120
           OR LENGTH(COALESCE(NULLIF(BTRIM(preferred_name), ''), '')) > 120
           OR LENGTH(COALESCE(NULLIF(BTRIM(phone), ''), '')) > 32
           OR LENGTH(COALESCE(NULLIF(BTRIM(job_level), ''), '')) > 80
           OR LENGTH(COALESCE(NULLIF(BTRIM(work_location), ''), '')) > 160
           OR LENGTH(COALESCE(NULLIF(BTRIM(contract_type), ''), '')) > 40
           OR LENGTH(COALESCE(NULLIF(BTRIM(contract_status), ''), '')) > 40
           OR LENGTH(COALESCE(NULLIF(BTRIM(education_level), ''), '')) > 80
           OR LENGTH(COALESCE(NULLIF(BTRIM(graduation_school), ''), '')) > 160
           OR LENGTH(COALESCE(NULLIF(BTRIM(major), ''), '')) > 160
           OR LENGTH(COALESCE(NULLIF(BTRIM(graduate_status), ''), '')) > 40
           OR LENGTH(COALESCE(NULLIF(BTRIM(skill_tags), ''), '')) > 1000
           OR LENGTH(BTRIM(social_insurance_region)) > 120
           OR BTRIM(social_insurance_region) = ''
           OR LENGTH(COALESCE(NULLIF(BTRIM(emergency_contact), ''), '')) > 255
    ) THEN
        RAISE EXCEPTION 'employees contains invalid descriptive attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees
        WHERE LOWER(BTRIM(employment_type)) NOT IN ('full_time', 'part_time', 'contractor', 'intern', 'probation')
           OR LOWER(BTRIM(status)) NOT IN ('onboarding', 'probation', 'active', 'departed')
           OR LOWER(BTRIM(access_role)) NOT IN (
               'founder', 'finance_admin', 'hr_admin', 'department_manager', 'employee', 'viewer'
           )
           OR LOWER(BTRIM(access_scope)) NOT IN (
               'group', 'company', 'company_set', 'department', 'self', 'readonly'
           )
           OR LOWER(BTRIM(hukou_type)) NOT IN ('local', 'non_local')
           OR LOWER(BTRIM(medical_tier)) NOT IN ('tier1', 'tier2')
           OR (
               NULLIF(BTRIM(material_status), '') IS NOT NULL
               AND LOWER(BTRIM(material_status)) NOT IN ('missing', 'pending', 'verified', 'waived', 'complete')
           )
    ) THEN
        RAISE EXCEPTION 'employees contains invalid workflow or access attributes';
    END IF;

    IF EXISTS (
        SELECT company_id, LOWER(BTRIM(email))
        FROM employees
        GROUP BY company_id, LOWER(BTRIM(email))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'employees contains duplicate normalized emails in a company';
    END IF;

    IF EXISTS (
        SELECT company_id, LOWER(BTRIM(employee_no))
        FROM employees
        WHERE NULLIF(BTRIM(employee_no), '') IS NOT NULL
        GROUP BY company_id, LOWER(BTRIM(employee_no))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'employees contains duplicate normalized employee numbers in a company';
    END IF;

    IF EXISTS (
        SELECT company_id, user_id
        FROM employees
        WHERE user_id IS NOT NULL
        GROUP BY company_id, user_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'employees contains duplicate company user assignments';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees
        WHERE NOT pg_input_is_valid(BTRIM(hire_date), 'date')
           OR (
               NULLIF(BTRIM(leave_date), '') IS NOT NULL
               AND NOT pg_input_is_valid(BTRIM(leave_date), 'date')
           )
           OR (
               NULLIF(BTRIM(probation_start_date), '') IS NOT NULL
               AND NOT pg_input_is_valid(BTRIM(probation_start_date), 'date')
           )
           OR (
               NULLIF(BTRIM(probation_end_date), '') IS NOT NULL
               AND NOT pg_input_is_valid(BTRIM(probation_end_date), 'date')
           )
           OR (
               NULLIF(BTRIM(contract_start_date), '') IS NOT NULL
               AND NOT pg_input_is_valid(BTRIM(contract_start_date), 'date')
           )
           OR (
               NULLIF(BTRIM(contract_end_date), '') IS NOT NULL
               AND NOT pg_input_is_valid(BTRIM(contract_end_date), 'date')
           )
           OR (
               NULLIF(BTRIM(graduation_date), '') IS NOT NULL
               AND NOT pg_input_is_valid(BTRIM(graduation_date), 'date')
           )
    ) THEN
        RAISE EXCEPTION 'employees contains an invalid employment or profile date';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees
        WHERE (graduation_year IS NOT NULL AND graduation_year NOT BETWEEN 1900 AND 9999)
           OR (
               NULLIF(BTRIM(leave_date), '') IS NOT NULL
               AND BTRIM(leave_date)::DATE < BTRIM(hire_date)::DATE
           )
           OR (
               NULLIF(BTRIM(probation_start_date), '') IS NOT NULL
               AND NULLIF(BTRIM(probation_end_date), '') IS NOT NULL
               AND BTRIM(probation_end_date)::DATE < BTRIM(probation_start_date)::DATE
           )
           OR (
               NULLIF(BTRIM(contract_start_date), '') IS NOT NULL
               AND NULLIF(BTRIM(contract_end_date), '') IS NOT NULL
               AND BTRIM(contract_end_date)::DATE < BTRIM(contract_start_date)::DATE
           )
    ) THEN
        RAISE EXCEPTION 'employees contains an invalid date sequence or graduation year';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
           OR (
               NULLIF(BTRIM(profile_verified_at), '') IS NOT NULL
               AND NOT pg_input_is_valid(BTRIM(profile_verified_at), 'timestamp with time zone')
           )
    ) THEN
        RAISE EXCEPTION 'employees contains an invalid lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM employees
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'employees contains a lifecycle timestamp before creation';
    END IF;

    FOREACH numeric_column IN ARRAY ARRAY[
        'salary', 'overtime_base', 'weekday_overtime_hours', 'rest_day_overtime_hours',
        'holiday_overtime_hours', 'overtime_pay', 'social_insurance', 'housing_fund', 'tax_estimate',
        'social_insurance_base', 'social_insurance_personal_rate', 'social_insurance_company_rate',
        'social_insurance_personal_amount', 'social_insurance_company_amount', 'housing_fund_base',
        'housing_fund_personal_rate', 'housing_fund_company_rate', 'housing_fund_personal_amount',
        'housing_fund_company_amount', 'personal_deduction', 'net_pay_estimate', 'pension_base',
        'medical_base', 'unemployment_base', 'work_injury_base', 'maternity_base',
        'work_injury_company_rate', 'monthly_cost'
    ] LOOP
        EXECUTE FORMAT(
            'SELECT EXISTS (SELECT 1 FROM employees WHERE BTRIM(%I) = '''' OR NOT pg_input_is_valid(BTRIM(%I), ''numeric''))',
            numeric_column,
            numeric_column
        ) INTO invalid_value;
        IF invalid_value THEN
            RAISE EXCEPTION 'employees contains an invalid numeric value in %', numeric_column;
        END IF;

        EXECUTE FORMAT(
            'SELECT EXISTS (SELECT 1 FROM employees WHERE BTRIM(%I)::NUMERIC < 0 '
            'OR BTRIM(%I)::NUMERIC > 9999999999999999.9999 '
            'OR SCALE(BTRIM(%I)::NUMERIC) > 4)',
            numeric_column,
            numeric_column,
            numeric_column
        ) INTO invalid_value;
        IF invalid_value THEN
            RAISE EXCEPTION 'employees contains an out-of-range numeric value in %', numeric_column;
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM employees
        WHERE social_insurance_personal_rate::NUMERIC > 100
           OR social_insurance_company_rate::NUMERIC > 100
           OR housing_fund_personal_rate::NUMERIC > 100
           OR housing_fund_company_rate::NUMERIC > 100
           OR work_injury_company_rate::NUMERIC > 100
    ) THEN
        RAISE EXCEPTION 'employees contains a contribution rate above 100 percent';
    END IF;
END $$;

UPDATE employees
SET employee_no = NULLIF(BTRIM(employee_no), ''),
    name = BTRIM(name),
    legal_name = NULLIF(BTRIM(legal_name), ''),
    preferred_name = NULLIF(BTRIM(preferred_name), ''),
    email = LOWER(BTRIM(email)),
    phone = NULLIF(BTRIM(phone), ''),
    position = BTRIM(position),
    job_level = NULLIF(BTRIM(job_level), ''),
    work_location = NULLIF(BTRIM(work_location), ''),
    employment_type = CASE
        WHEN LOWER(BTRIM(employment_type)) = 'probation' THEN 'full_time'
        ELSE LOWER(BTRIM(employment_type))
    END,
    status = LOWER(BTRIM(status)),
    access_role = LOWER(BTRIM(access_role)),
    access_scope = LOWER(BTRIM(access_scope)),
    hire_date = BTRIM(hire_date),
    leave_date = NULLIF(BTRIM(leave_date), ''),
    probation_start_date = NULLIF(BTRIM(probation_start_date), ''),
    probation_end_date = NULLIF(BTRIM(probation_end_date), ''),
    contract_start_date = NULLIF(BTRIM(contract_start_date), ''),
    contract_end_date = NULLIF(BTRIM(contract_end_date), ''),
    contract_type = NULLIF(BTRIM(contract_type), ''),
    contract_status = NULLIF(BTRIM(contract_status), ''),
    education_level = NULLIF(BTRIM(education_level), ''),
    graduation_school = NULLIF(BTRIM(graduation_school), ''),
    major = NULLIF(BTRIM(major), ''),
    graduation_date = NULLIF(BTRIM(graduation_date), ''),
    graduate_status = NULLIF(BTRIM(graduate_status), ''),
    skill_tags = NULLIF(BTRIM(skill_tags), ''),
    material_status = CASE
        WHEN LOWER(BTRIM(material_status)) = 'complete' THEN 'verified'
        ELSE NULLIF(LOWER(BTRIM(material_status)), '')
    END,
    profile_verified_at = NULLIF(BTRIM(profile_verified_at), ''),
    social_insurance_region = BTRIM(social_insurance_region),
    hukou_type = LOWER(BTRIM(hukou_type)),
    medical_tier = LOWER(BTRIM(medical_tier)),
    emergency_contact = NULLIF(BTRIM(emergency_contact), ''),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE employees
    ALTER COLUMN overtime_base DROP DEFAULT,
    ALTER COLUMN weekday_overtime_hours DROP DEFAULT,
    ALTER COLUMN rest_day_overtime_hours DROP DEFAULT,
    ALTER COLUMN holiday_overtime_hours DROP DEFAULT,
    ALTER COLUMN overtime_pay DROP DEFAULT,
    ALTER COLUMN social_insurance_base DROP DEFAULT,
    ALTER COLUMN social_insurance_personal_rate DROP DEFAULT,
    ALTER COLUMN social_insurance_company_rate DROP DEFAULT,
    ALTER COLUMN social_insurance_personal_amount DROP DEFAULT,
    ALTER COLUMN social_insurance_company_amount DROP DEFAULT,
    ALTER COLUMN housing_fund_base DROP DEFAULT,
    ALTER COLUMN housing_fund_personal_rate DROP DEFAULT,
    ALTER COLUMN housing_fund_company_rate DROP DEFAULT,
    ALTER COLUMN housing_fund_personal_amount DROP DEFAULT,
    ALTER COLUMN housing_fund_company_amount DROP DEFAULT,
    ALTER COLUMN personal_deduction DROP DEFAULT,
    ALTER COLUMN net_pay_estimate DROP DEFAULT,
    ALTER COLUMN pension_base DROP DEFAULT,
    ALTER COLUMN medical_base DROP DEFAULT,
    ALTER COLUMN unemployment_base DROP DEFAULT,
    ALTER COLUMN work_injury_base DROP DEFAULT,
    ALTER COLUMN maternity_base DROP DEFAULT,
    ALTER COLUMN work_injury_company_rate DROP DEFAULT;

ALTER TABLE employees
    ALTER COLUMN employee_no TYPE VARCHAR(64),
    ALTER COLUMN name TYPE VARCHAR(120),
    ALTER COLUMN legal_name TYPE VARCHAR(120),
    ALTER COLUMN preferred_name TYPE VARCHAR(120),
    ALTER COLUMN email TYPE VARCHAR(320),
    ALTER COLUMN phone TYPE VARCHAR(32),
    ALTER COLUMN position TYPE VARCHAR(160),
    ALTER COLUMN job_level TYPE VARCHAR(80),
    ALTER COLUMN work_location TYPE VARCHAR(160),
    ALTER COLUMN employment_type TYPE VARCHAR(32),
    ALTER COLUMN status TYPE VARCHAR(32),
    ALTER COLUMN access_role TYPE VARCHAR(32),
    ALTER COLUMN access_scope TYPE VARCHAR(32),
    ALTER COLUMN hire_date TYPE DATE USING hire_date::DATE,
    ALTER COLUMN leave_date TYPE DATE USING leave_date::DATE,
    ALTER COLUMN probation_start_date TYPE DATE USING probation_start_date::DATE,
    ALTER COLUMN probation_end_date TYPE DATE USING probation_end_date::DATE,
    ALTER COLUMN contract_start_date TYPE DATE USING contract_start_date::DATE,
    ALTER COLUMN contract_end_date TYPE DATE USING contract_end_date::DATE,
    ALTER COLUMN contract_type TYPE VARCHAR(40),
    ALTER COLUMN contract_status TYPE VARCHAR(40),
    ALTER COLUMN education_level TYPE VARCHAR(80),
    ALTER COLUMN graduation_school TYPE VARCHAR(160),
    ALTER COLUMN major TYPE VARCHAR(160),
    ALTER COLUMN graduation_date TYPE DATE USING graduation_date::DATE,
    ALTER COLUMN graduate_status TYPE VARCHAR(40),
    ALTER COLUMN skill_tags TYPE VARCHAR(1000),
    ALTER COLUMN material_status TYPE VARCHAR(32),
    ALTER COLUMN profile_verified_at TYPE TIMESTAMPTZ USING profile_verified_at::TIMESTAMPTZ,
    ALTER COLUMN salary TYPE NUMERIC(20, 4) USING salary::NUMERIC(20, 4),
    ALTER COLUMN overtime_base TYPE NUMERIC(20, 4) USING overtime_base::NUMERIC(20, 4),
    ALTER COLUMN weekday_overtime_hours TYPE NUMERIC(20, 4) USING weekday_overtime_hours::NUMERIC(20, 4),
    ALTER COLUMN rest_day_overtime_hours TYPE NUMERIC(20, 4) USING rest_day_overtime_hours::NUMERIC(20, 4),
    ALTER COLUMN holiday_overtime_hours TYPE NUMERIC(20, 4) USING holiday_overtime_hours::NUMERIC(20, 4),
    ALTER COLUMN overtime_pay TYPE NUMERIC(20, 4) USING overtime_pay::NUMERIC(20, 4),
    ALTER COLUMN social_insurance TYPE NUMERIC(20, 4) USING social_insurance::NUMERIC(20, 4),
    ALTER COLUMN housing_fund TYPE NUMERIC(20, 4) USING housing_fund::NUMERIC(20, 4),
    ALTER COLUMN tax_estimate TYPE NUMERIC(20, 4) USING tax_estimate::NUMERIC(20, 4),
    ALTER COLUMN social_insurance_base TYPE NUMERIC(20, 4) USING social_insurance_base::NUMERIC(20, 4),
    ALTER COLUMN social_insurance_personal_rate TYPE NUMERIC(20, 4) USING social_insurance_personal_rate::NUMERIC(20, 4),
    ALTER COLUMN social_insurance_company_rate TYPE NUMERIC(20, 4) USING social_insurance_company_rate::NUMERIC(20, 4),
    ALTER COLUMN social_insurance_personal_amount TYPE NUMERIC(20, 4) USING social_insurance_personal_amount::NUMERIC(20, 4),
    ALTER COLUMN social_insurance_company_amount TYPE NUMERIC(20, 4) USING social_insurance_company_amount::NUMERIC(20, 4),
    ALTER COLUMN housing_fund_base TYPE NUMERIC(20, 4) USING housing_fund_base::NUMERIC(20, 4),
    ALTER COLUMN housing_fund_personal_rate TYPE NUMERIC(20, 4) USING housing_fund_personal_rate::NUMERIC(20, 4),
    ALTER COLUMN housing_fund_company_rate TYPE NUMERIC(20, 4) USING housing_fund_company_rate::NUMERIC(20, 4),
    ALTER COLUMN housing_fund_personal_amount TYPE NUMERIC(20, 4) USING housing_fund_personal_amount::NUMERIC(20, 4),
    ALTER COLUMN housing_fund_company_amount TYPE NUMERIC(20, 4) USING housing_fund_company_amount::NUMERIC(20, 4),
    ALTER COLUMN personal_deduction TYPE NUMERIC(20, 4) USING personal_deduction::NUMERIC(20, 4),
    ALTER COLUMN net_pay_estimate TYPE NUMERIC(20, 4) USING net_pay_estimate::NUMERIC(20, 4),
    ALTER COLUMN social_insurance_region TYPE VARCHAR(120),
    ALTER COLUMN hukou_type TYPE VARCHAR(32),
    ALTER COLUMN medical_tier TYPE VARCHAR(32),
    ALTER COLUMN pension_base TYPE NUMERIC(20, 4) USING pension_base::NUMERIC(20, 4),
    ALTER COLUMN medical_base TYPE NUMERIC(20, 4) USING medical_base::NUMERIC(20, 4),
    ALTER COLUMN unemployment_base TYPE NUMERIC(20, 4) USING unemployment_base::NUMERIC(20, 4),
    ALTER COLUMN work_injury_base TYPE NUMERIC(20, 4) USING work_injury_base::NUMERIC(20, 4),
    ALTER COLUMN maternity_base TYPE NUMERIC(20, 4) USING maternity_base::NUMERIC(20, 4),
    ALTER COLUMN work_injury_company_rate TYPE NUMERIC(20, 4) USING work_injury_company_rate::NUMERIC(20, 4),
    ALTER COLUMN monthly_cost TYPE NUMERIC(20, 4) USING monthly_cost::NUMERIC(20, 4),
    ALTER COLUMN emergency_contact TYPE VARCHAR(255),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ;

ALTER TABLE employees
    ALTER COLUMN overtime_base SET DEFAULT 0,
    ALTER COLUMN weekday_overtime_hours SET DEFAULT 0,
    ALTER COLUMN rest_day_overtime_hours SET DEFAULT 0,
    ALTER COLUMN holiday_overtime_hours SET DEFAULT 0,
    ALTER COLUMN overtime_pay SET DEFAULT 0,
    ALTER COLUMN social_insurance_base SET DEFAULT 0,
    ALTER COLUMN social_insurance_personal_rate SET DEFAULT 0,
    ALTER COLUMN social_insurance_company_rate SET DEFAULT 0,
    ALTER COLUMN social_insurance_personal_amount SET DEFAULT 0,
    ALTER COLUMN social_insurance_company_amount SET DEFAULT 0,
    ALTER COLUMN housing_fund_base SET DEFAULT 0,
    ALTER COLUMN housing_fund_personal_rate SET DEFAULT 0,
    ALTER COLUMN housing_fund_company_rate SET DEFAULT 0,
    ALTER COLUMN housing_fund_personal_amount SET DEFAULT 0,
    ALTER COLUMN housing_fund_company_amount SET DEFAULT 0,
    ALTER COLUMN personal_deduction SET DEFAULT 0,
    ALTER COLUMN net_pay_estimate SET DEFAULT 0,
    ALTER COLUMN pension_base SET DEFAULT 0,
    ALTER COLUMN medical_base SET DEFAULT 0,
    ALTER COLUMN unemployment_base SET DEFAULT 0,
    ALTER COLUMN work_injury_base SET DEFAULT 0,
    ALTER COLUMN maternity_base SET DEFAULT 0,
    ALTER COLUMN work_injury_company_rate SET DEFAULT 0.2;

ALTER TABLE employees
    ADD CONSTRAINT fk_employees_company
        FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_employees_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_employees_profile_verifier
        FOREIGN KEY (profile_verified_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_employees_direct_manager_company
        FOREIGN KEY (direct_manager_employee_id, company_id)
        REFERENCES employees(id, company_id)
        ON DELETE SET NULL (direct_manager_employee_id),
    ADD CONSTRAINT ck_employees_company_positive CHECK (company_id > 0),
    ADD CONSTRAINT ck_employees_name CHECK (name = BTRIM(name) AND name <> ''),
    ADD CONSTRAINT ck_employees_email
        CHECK (email = LOWER(BTRIM(email)) AND POSITION('@' IN email) > 1),
    ADD CONSTRAINT ck_employees_position CHECK (position = BTRIM(position) AND position <> ''),
    ADD CONSTRAINT ck_employees_employment_type
        CHECK (employment_type IN ('full_time', 'part_time', 'contractor', 'intern')),
    ADD CONSTRAINT ck_employees_status
        CHECK (status IN ('onboarding', 'probation', 'active', 'departed')),
    ADD CONSTRAINT ck_employees_access_role
        CHECK (access_role IN ('founder', 'finance_admin', 'hr_admin', 'department_manager', 'employee', 'viewer')),
    ADD CONSTRAINT ck_employees_access_scope
        CHECK (access_scope IN ('group', 'company', 'company_set', 'department', 'self', 'readonly')),
    ADD CONSTRAINT ck_employees_material_status
        CHECK (material_status IS NULL OR material_status IN ('missing', 'pending', 'verified', 'waived')),
    ADD CONSTRAINT ck_employees_hukou_type CHECK (hukou_type IN ('local', 'non_local')),
    ADD CONSTRAINT ck_employees_medical_tier CHECK (medical_tier IN ('tier1', 'tier2')),
    ADD CONSTRAINT ck_employees_direct_manager CHECK (direct_manager_employee_id IS DISTINCT FROM id),
    ADD CONSTRAINT ck_employees_graduation_year
        CHECK (graduation_year IS NULL OR graduation_year BETWEEN 1900 AND 9999),
    ADD CONSTRAINT ck_employees_date_sequence CHECK (
        (leave_date IS NULL OR leave_date >= hire_date)
        AND (probation_start_date IS NULL OR probation_end_date IS NULL OR probation_end_date >= probation_start_date)
        AND (contract_start_date IS NULL OR contract_end_date IS NULL OR contract_end_date >= contract_start_date)
    ),
    ADD CONSTRAINT ck_employees_amounts_nonnegative CHECK (
        salary >= 0 AND overtime_base >= 0 AND weekday_overtime_hours >= 0
        AND rest_day_overtime_hours >= 0 AND holiday_overtime_hours >= 0 AND overtime_pay >= 0
        AND social_insurance >= 0 AND housing_fund >= 0 AND tax_estimate >= 0
        AND social_insurance_base >= 0 AND social_insurance_personal_amount >= 0
        AND social_insurance_company_amount >= 0 AND housing_fund_base >= 0
        AND housing_fund_personal_amount >= 0 AND housing_fund_company_amount >= 0
        AND personal_deduction >= 0 AND net_pay_estimate >= 0 AND pension_base >= 0
        AND medical_base >= 0 AND unemployment_base >= 0 AND work_injury_base >= 0
        AND maternity_base >= 0 AND monthly_cost >= 0
    ),
    ADD CONSTRAINT ck_employees_rate_range CHECK (
        social_insurance_personal_rate BETWEEN 0 AND 100
        AND social_insurance_company_rate BETWEEN 0 AND 100
        AND housing_fund_personal_rate BETWEEN 0 AND 100
        AND housing_fund_company_rate BETWEEN 0 AND 100
        AND work_injury_company_rate BETWEEN 0 AND 100
    ),
    ADD CONSTRAINT ck_employees_lifecycle CHECK (updated_at >= created_at);

CREATE UNIQUE INDEX uq_employees_company_normalized_email
    ON employees(company_id, LOWER(email));

CREATE UNIQUE INDEX uq_employees_company_normalized_employee_no
    ON employees(company_id, LOWER(employee_no)) WHERE employee_no IS NOT NULL;

CREATE UNIQUE INDEX uq_employees_company_user
    ON employees(company_id, user_id) WHERE user_id IS NOT NULL;

CREATE FUNCTION prevent_employee_company_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.company_id <> OLD.company_id THEN
        RAISE EXCEPTION 'employee company cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_employee_company_immutable
BEFORE UPDATE OF company_id ON employees
FOR EACH ROW
EXECUTE FUNCTION prevent_employee_company_change();

COMMENT ON TABLE employees IS
    'Authoritative People Core employee records with typed compensation and company-scoped relationships.';
