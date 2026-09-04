package com.mamoji.evidence.api;

import com.mamoji.evidence.application.ReceiptBatchUploadResult;
import com.mamoji.evidence.application.ReceiptUploadFailure;
import com.mamoji.evidence.domain.ReceiptVoucher;
import java.util.List;

/** Batch upload response with the same JSON shape as the legacy map response. */
public record ReceiptBatchUploadResponse(
    int successCount,
    int failureCount,
    List<ReceiptVoucher> vouchers,
    List<ReceiptUploadFailure> failures
) {
    public static ReceiptBatchUploadResponse from(ReceiptBatchUploadResult result) {
        return new ReceiptBatchUploadResponse(
            result.successCount(),
            result.failureCount(),
            result.vouchers(),
            result.failures()
        );
    }
}
