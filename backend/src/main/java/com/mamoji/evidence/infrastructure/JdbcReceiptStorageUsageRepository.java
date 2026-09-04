package com.mamoji.evidence.infrastructure;

import com.mamoji.evidence.application.ReceiptStorageUsageRepository;
import com.mamoji.evidence.domain.ReceiptStorageUsage;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for serialized, company-scoped receipt capacity checks. */
@Repository
public class JdbcReceiptStorageUsageRepository implements ReceiptStorageUsageRepository {
    private final JdbcTemplate jdbc;

    public JdbcReceiptStorageUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockCompany(long companyId) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (org.springframework.jdbc.core.RowCallbackHandler) row -> { },
            "receipt-storage:" + companyId
        );
    }

    @Override
    public ReceiptStorageUsage findByCompany(long companyId) {
        return Objects.requireNonNull(jdbc.queryForObject("""
            SELECT COUNT(*) AS object_count,
                   COALESCE(SUM(COALESCE((
                       SELECT MAX(file_hash.file_size)
                       FROM receipt_file_hashes file_hash
                       WHERE file_hash.company_id = voucher.company_id
                         AND file_hash.voucher_id = voucher.id
                   ), voucher.file_size)), 0) AS used_bytes
            FROM receipt_vouchers voucher
            WHERE voucher.company_id = ? AND voucher.file_storage_provider = 'minio'
            """, (rs, rowNum) -> new ReceiptStorageUsage(
                rs.getLong("object_count"),
                rs.getLong("used_bytes")
            ), companyId));
    }
}
