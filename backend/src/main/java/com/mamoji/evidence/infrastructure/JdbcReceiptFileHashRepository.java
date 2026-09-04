package com.mamoji.evidence.infrastructure;

import com.mamoji.evidence.application.ReceiptFileHashRepository;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for company-scoped receipt attachment deduplication. */
@Repository
public class JdbcReceiptFileHashRepository implements ReceiptFileHashRepository {
    private final JdbcTemplate jdbc;

    public JdbcReceiptFileHashRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(long companyId, String sha256) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (org.springframework.jdbc.core.RowCallbackHandler) row -> { },
            "receipt-file:" + companyId + ":" + sha256
        );
    }

    @Override
    public OptionalLong findVoucherId(long companyId, String sha256) {
        List<Long> voucherIds = jdbc.queryForList(
            "SELECT voucher_id FROM receipt_file_hashes WHERE company_id = ? AND sha256 = ? LIMIT 1",
            Long.class,
            companyId,
            sha256
        );
        return voucherIds.isEmpty() ? OptionalLong.empty() : OptionalLong.of(voucherIds.getFirst());
    }

    @Override
    public void register(
        long companyId,
        long voucherId,
        String sha256,
        String fileName,
        long fileSize,
        String createdAt
    ) {
        jdbc.update("""
            INSERT INTO receipt_file_hashes (company_id, voucher_id, sha256, file_name, file_size, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, companyId, voucherId, sha256, fileName, fileSize, createdAt);
    }
}
