package com.mamoji.evidence.infrastructure;

import com.mamoji.evidence.application.ReceiptFileHashRepository;
import com.mamoji.evidence.application.ReceiptFileRegistration;
import com.mamoji.evidence.domain.ReceiptFileDigest;
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
    public void lock(long companyId, ReceiptFileDigest digest) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (org.springframework.jdbc.core.RowCallbackHandler) row -> { },
            "receipt-file:" + companyId + ":" + digest.value()
        );
    }

    @Override
    public OptionalLong findVoucherId(long companyId, ReceiptFileDigest digest) {
        List<Long> voucherIds = jdbc.queryForList(
            "SELECT voucher_id FROM receipt_file_hashes WHERE company_id = ? AND sha256 = ? LIMIT 1",
            Long.class,
            companyId,
            digest.value()
        );
        return voucherIds.isEmpty() ? OptionalLong.empty() : OptionalLong.of(voucherIds.getFirst());
    }

    @Override
    public void register(ReceiptFileRegistration registration) {
        jdbc.update("""
            INSERT INTO receipt_file_hashes (company_id, voucher_id, sha256, file_name, file_size, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            registration.companyId(),
            registration.voucherId(),
            registration.digest().value(),
            registration.fileName(),
            registration.fileSize(),
            registration.createdAt()
        );
    }
}
