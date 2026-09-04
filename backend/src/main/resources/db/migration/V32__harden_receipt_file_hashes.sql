DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM receipt_file_hashes file_hash
        LEFT JOIN companies company ON company.id = file_hash.company_id
        LEFT JOIN receipt_vouchers voucher ON voucher.id = file_hash.voucher_id
        WHERE company.id IS NULL
           OR voucher.id IS NULL
           OR voucher.company_id IS DISTINCT FROM file_hash.company_id
    ) THEN
        RAISE EXCEPTION 'receipt_file_hashes contains an orphaned or cross-company reference';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_file_hashes
        WHERE LOWER(BTRIM(sha256)) !~ '^[0-9a-f]{64}$'
    ) THEN
        RAISE EXCEPTION 'receipt_file_hashes contains an invalid SHA-256 digest';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_file_hashes
        WHERE REGEXP_REPLACE(REPLACE(BTRIM(file_name), CHR(92), '/'), '^.*/', '') = ''
           OR LENGTH(REGEXP_REPLACE(REPLACE(BTRIM(file_name), CHR(92), '/'), '^.*/', '')) > 255
           OR BTRIM(file_name) ~ '[[:cntrl:]]'
           OR file_size < 0
    ) THEN
        RAISE EXCEPTION 'receipt_file_hashes contains invalid file metadata';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_file_hashes
        WHERE NOT pg_input_is_valid(BTRIM(created_at), 'timestamp with time zone')
    ) THEN
        RAISE EXCEPTION 'receipt_file_hashes contains an invalid creation timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_file_hashes
        WHERE NOT isfinite(BTRIM(created_at)::TIMESTAMPTZ)
    ) THEN
        RAISE EXCEPTION 'receipt_file_hashes contains a non-finite creation timestamp';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM receipt_file_hashes
        GROUP BY company_id, LOWER(BTRIM(sha256))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'receipt_file_hashes contains duplicate normalized SHA-256 digests';
    END IF;
END $$;

UPDATE receipt_file_hashes
SET sha256 = LOWER(BTRIM(sha256)),
    file_name = REGEXP_REPLACE(REPLACE(BTRIM(file_name), CHR(92), '/'), '^.*/', ''),
    created_at = BTRIM(created_at);

ALTER TABLE receipt_file_hashes
    ALTER COLUMN sha256 TYPE VARCHAR(64),
    ALTER COLUMN file_name TYPE VARCHAR(255),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at::TIMESTAMPTZ;

ALTER TABLE receipt_file_hashes
    ADD CONSTRAINT ck_receipt_file_hashes_company_positive
        CHECK (company_id > 0),
    ADD CONSTRAINT ck_receipt_file_hashes_voucher_positive
        CHECK (voucher_id > 0),
    ADD CONSTRAINT ck_receipt_file_hashes_sha256
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_receipt_file_hashes_file_name
        CHECK (
            file_name = BTRIM(file_name)
            AND file_name <> ''
            AND file_name !~ '[[:cntrl:]]'
            AND POSITION('/' IN file_name) = 0
            AND POSITION(CHR(92) IN file_name) = 0
        ),
    ADD CONSTRAINT ck_receipt_file_hashes_file_size
        CHECK (file_size >= 0),
    ADD CONSTRAINT ck_receipt_file_hashes_created_at
        CHECK (isfinite(created_at));

CREATE FUNCTION prevent_receipt_file_hash_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'receipt file hash metadata cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_receipt_file_hash_immutable
BEFORE UPDATE ON receipt_file_hashes
FOR EACH ROW
EXECUTE FUNCTION prevent_receipt_file_hash_change();

DROP INDEX idx_receipt_file_hashes_voucher;

CREATE INDEX idx_receipt_file_hashes_company_voucher
    ON receipt_file_hashes(company_id, voucher_id);

COMMENT ON TABLE receipt_file_hashes IS
    'Immutable company-scoped SHA-256 identities and typed metadata for receipt attachments.';
