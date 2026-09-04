package com.mamoji.evidence.application;

import com.mamoji.evidence.domain.ReceiptFileDigest;
import java.util.OptionalLong;

/** Persistence boundary for company-scoped receipt attachment deduplication. */
public interface ReceiptFileHashRepository {
    void lock(long companyId, ReceiptFileDigest digest);

    OptionalLong findVoucherId(long companyId, ReceiptFileDigest digest);

    void register(ReceiptFileRegistration registration);
}
