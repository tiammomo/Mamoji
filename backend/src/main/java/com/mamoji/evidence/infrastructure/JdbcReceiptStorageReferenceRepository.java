package com.mamoji.evidence.infrastructure;

import com.mamoji.evidence.application.ReceiptStorageReferenceRepository;
import com.mamoji.evidence.application.ReceiptStorageReferenceSnapshot;
import com.mamoji.evidence.domain.ReceiptObjectLocation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the durable database side of the MinIO receipt object relationship. */
@Repository
public class JdbcReceiptStorageReferenceRepository implements ReceiptStorageReferenceRepository {
    private final JdbcTemplate jdbc;

    public JdbcReceiptStorageReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReceiptStorageReferenceSnapshot findAll(String defaultBucket, int maximumReferences) {
        String normalizedDefaultBucket = requireBucket(defaultBucket);
        if (maximumReferences <= 0 || maximumReferences >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Maximum receipt references must be positive and bounded");
        }
        List<ReferenceRow> rows = jdbc.query("""
            SELECT file_bucket, file_object_key
            FROM receipt_vouchers
            WHERE file_storage_provider = 'minio'
            ORDER BY id
            LIMIT ?
            """, (rs, rowNum) -> new ReferenceRow(
                rs.getString("file_bucket"),
                rs.getString("file_object_key")
            ), maximumReferences + 1);
        if (rows.size() > maximumReferences) {
            throw new IllegalStateException("Receipt reference inventory exceeded configured maximum");
        }

        List<ReceiptObjectLocation> references = new ArrayList<>(rows.size());
        long invalidReferenceCount = 0L;
        for (ReferenceRow row : rows) {
            String bucket = row.bucket() == null || row.bucket().isBlank()
                ? normalizedDefaultBucket
                : row.bucket().strip();
            try {
                references.add(new ReceiptObjectLocation(bucket, row.objectKey()));
            } catch (IllegalArgumentException ex) {
                invalidReferenceCount++;
            }
        }
        return new ReceiptStorageReferenceSnapshot(references, invalidReferenceCount);
    }

    private String requireBucket(String value) {
        String bucket = value == null ? "" : value.strip();
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("Default receipt bucket is required");
        }
        return bucket;
    }

    private record ReferenceRow(String bucket, String objectKey) {
    }
}
