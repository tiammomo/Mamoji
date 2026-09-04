-- Preserve the legacy bootstrap repair semantics before enforcing the durable contract.
UPDATE companies
SET entity_type = CASE WHEN BTRIM(entity_type) = '' THEN 'company' ELSE entity_type END,
    country = CASE WHEN BTRIM(country) = '' THEN '中国' ELSE country END,
    province = CASE
        WHEN BTRIM(province) = '' AND name LIKE '%深圳%' THEN '广东省'
        ELSE province
    END,
    city = CASE
        WHEN BTRIM(city) = '' AND name LIKE '%深圳%' THEN '深圳市'
        ELSE city
    END,
    fiscal_year_start_month = CASE
        WHEN fiscal_year_start_month NOT BETWEEN 1 AND 12 THEN 1
        ELSE fiscal_year_start_month
    END;

UPDATE companies
SET policy_profile_key = CASE
        WHEN city LIKE '%深圳%' AND BTRIM(policy_profile_key) IN (
            '', 'CN-DEFAULT-DEMO-POLICY', 'CN-GD-SZ-DEMO-POLICY'
        ) THEN 'CN-GD-SZ-STARTUP-LITE'
        WHEN BTRIM(policy_profile_key) = '' AND LOWER(BTRIM(entity_type)) = 'household'
            THEN 'CN-HOUSEHOLD-ASSET-PROFILE'
        WHEN BTRIM(policy_profile_key) = '' THEN 'CN-DEFAULT-DEMO-POLICY'
        ELSE policy_profile_key
    END,
    operating_region = CASE
        WHEN BTRIM(operating_region) = '' THEN CONCAT_WS(
            '/',
            NULLIF(BTRIM(country), ''),
            NULLIF(BTRIM(province), ''),
            NULLIF(BTRIM(city), ''),
            NULLIF(BTRIM(district), '')
        )
        ELSE operating_region
    END;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM companies company
        WHERE NOT EXISTS (SELECT 1 FROM users local_user WHERE local_user.id = company.owner_id)
    ) THEN
        RAISE EXCEPTION 'companies contains an orphaned owner';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM companies
        WHERE BTRIM(name) = '' OR LENGTH(BTRIM(name)) > 200
           OR LOWER(BTRIM(entity_type)) NOT IN ('company', 'household')
           OR LENGTH(COALESCE(NULLIF(BTRIM(credit_code), ''), '')) > 64
           OR BTRIM(industry) = '' OR LENGTH(BTRIM(industry)) > 160
           OR BTRIM(taxpayer_type) = '' OR LENGTH(BTRIM(taxpayer_type)) > 120
           OR UPPER(BTRIM(currency)) !~ '^[A-Z]{3}$'
           OR BTRIM(country) = '' OR LENGTH(BTRIM(country)) > 100
           OR LENGTH(BTRIM(province)) > 100
           OR LENGTH(BTRIM(city)) > 100
           OR LENGTH(BTRIM(district)) > 100
           OR LENGTH(COALESCE(NULLIF(BTRIM(registered_address), ''), '')) > 500
           OR BTRIM(operating_region) = '' OR LENGTH(BTRIM(operating_region)) > 400
           OR LENGTH(COALESCE(NULLIF(BTRIM(tax_authority), ''), '')) > 200
           OR BTRIM(policy_profile_key) = '' OR LENGTH(BTRIM(policy_profile_key)) > 160
           OR fiscal_year_start_month NOT BETWEEN 1 AND 12
           OR owner_id <= 0
    ) THEN
        RAISE EXCEPTION 'companies contains invalid tenant profile attributes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM companies
        WHERE NULLIF(BTRIM(credit_code), '') IS NOT NULL
        GROUP BY UPPER(BTRIM(credit_code))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'companies contains duplicate normalized credit codes';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM companies
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
           OR NOT pg_input_is_valid(BTRIM(updated_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'companies contains an invalid lifecycle timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM companies
        WHERE BTRIM(updated_at)::TIMESTAMPTZ < BTRIM(created_at)::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'companies contains an invalid lifecycle sequence';
    END IF;
END $$;

UPDATE companies
SET name = BTRIM(name),
    entity_type = LOWER(BTRIM(entity_type)),
    credit_code = UPPER(NULLIF(BTRIM(credit_code), '')),
    industry = BTRIM(industry),
    taxpayer_type = BTRIM(taxpayer_type),
    currency = UPPER(BTRIM(currency)),
    country = BTRIM(country),
    province = BTRIM(province),
    city = BTRIM(city),
    district = BTRIM(district),
    registered_address = NULLIF(BTRIM(registered_address), ''),
    operating_region = BTRIM(operating_region),
    tax_authority = NULLIF(BTRIM(tax_authority), ''),
    policy_profile_key = BTRIM(policy_profile_key),
    created_at = BTRIM(created_at),
    updated_at = BTRIM(updated_at);

ALTER TABLE companies
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ALTER COLUMN name TYPE VARCHAR(200),
    ALTER COLUMN entity_type TYPE VARCHAR(32),
    ALTER COLUMN credit_code TYPE VARCHAR(64),
    ALTER COLUMN industry TYPE VARCHAR(160),
    ALTER COLUMN taxpayer_type TYPE VARCHAR(120),
    ALTER COLUMN currency TYPE VARCHAR(3),
    ALTER COLUMN country TYPE VARCHAR(100),
    ALTER COLUMN province TYPE VARCHAR(100),
    ALTER COLUMN city TYPE VARCHAR(100),
    ALTER COLUMN district TYPE VARCHAR(100),
    ALTER COLUMN registered_address TYPE VARCHAR(500),
    ALTER COLUMN operating_region TYPE VARCHAR(400),
    ALTER COLUMN tax_authority TYPE VARCHAR(200),
    ALTER COLUMN policy_profile_key TYPE VARCHAR(160),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at::TIMESTAMPTZ;

ALTER TABLE companies
    ADD CONSTRAINT fk_companies_owner
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_companies_version CHECK (version >= 0),
    ADD CONSTRAINT ck_companies_name CHECK (name = BTRIM(name) AND name <> ''),
    ADD CONSTRAINT ck_companies_entity_type CHECK (entity_type IN ('company', 'household')),
    ADD CONSTRAINT ck_companies_credit_code CHECK (
        credit_code IS NULL OR (credit_code = UPPER(BTRIM(credit_code)) AND credit_code <> '')
    ),
    ADD CONSTRAINT ck_companies_industry CHECK (industry = BTRIM(industry) AND industry <> ''),
    ADD CONSTRAINT ck_companies_taxpayer_type CHECK (taxpayer_type = BTRIM(taxpayer_type) AND taxpayer_type <> ''),
    ADD CONSTRAINT ck_companies_currency CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_companies_country CHECK (country = BTRIM(country) AND country <> ''),
    ADD CONSTRAINT ck_companies_regions CHECK (
        province = BTRIM(province) AND city = BTRIM(city) AND district = BTRIM(district)
    ),
    ADD CONSTRAINT ck_companies_registered_address CHECK (
        registered_address IS NULL OR (registered_address = BTRIM(registered_address) AND registered_address <> '')
    ),
    ADD CONSTRAINT ck_companies_operating_region CHECK (
        operating_region = BTRIM(operating_region) AND operating_region <> ''
    ),
    ADD CONSTRAINT ck_companies_tax_authority CHECK (
        tax_authority IS NULL OR (tax_authority = BTRIM(tax_authority) AND tax_authority <> '')
    ),
    ADD CONSTRAINT ck_companies_policy_profile CHECK (
        policy_profile_key = BTRIM(policy_profile_key) AND policy_profile_key <> ''
    ),
    ADD CONSTRAINT ck_companies_fiscal_year CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
    ADD CONSTRAINT ck_companies_owner_positive CHECK (owner_id > 0),
    ADD CONSTRAINT ck_companies_lifecycle CHECK (updated_at >= created_at);

CREATE UNIQUE INDEX uq_companies_normalized_credit_code
    ON companies(UPPER(credit_code)) WHERE credit_code IS NOT NULL;

CREATE FUNCTION prevent_company_owner_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.owner_id <> OLD.owner_id THEN
        RAISE EXCEPTION 'company owner cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_company_owner_immutable
BEFORE UPDATE ON companies
FOR EACH ROW
EXECUTE FUNCTION prevent_company_owner_change();

COMMENT ON TABLE companies IS
    'Authoritative tenant subjects with immutable ownership and optimistic profile updates.';
