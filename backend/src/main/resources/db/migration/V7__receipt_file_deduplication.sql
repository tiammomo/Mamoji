CREATE TABLE IF NOT EXISTS receipt_file_hashes (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    sha256 TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    created_at TEXT NOT NULL,
    CONSTRAINT fk_receipt_file_hashes_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_receipt_file_hashes_voucher FOREIGN KEY (voucher_id) REFERENCES receipt_vouchers(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_receipt_file_hashes_company_sha256
    ON receipt_file_hashes(company_id, sha256);
CREATE INDEX IF NOT EXISTS idx_receipt_file_hashes_voucher
    ON receipt_file_hashes(voucher_id);
