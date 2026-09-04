package com.mamoji.evidence.application;

import com.mamoji.evidence.domain.ReceiptVoucher;
import java.util.List;

/** Transport-independent result of processing a receipt upload batch. */
public record ReceiptBatchUploadResult(
    List<ReceiptVoucher> vouchers,
    List<ReceiptUploadFailure> failures
) {
    public ReceiptBatchUploadResult {
        vouchers = List.copyOf(vouchers);
        failures = List.copyOf(failures);
    }

    public int successCount() {
        return vouchers.size();
    }

    public int failureCount() {
        return failures.size();
    }
}
