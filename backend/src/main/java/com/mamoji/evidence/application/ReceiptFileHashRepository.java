package com.mamoji.evidence.application;

import java.util.OptionalLong;

/** Persistence boundary for company-scoped receipt attachment deduplication. */
public interface ReceiptFileHashRepository {
    void lock(long companyId, String sha256);

    OptionalLong findVoucherId(long companyId, String sha256);

    void register(
        long companyId,
        long voucherId,
        String sha256,
        String fileName,
        long fileSize,
        String createdAt
    );
}
