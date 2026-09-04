package com.mamoji.evidence.application;

import com.mamoji.evidence.domain.ReceiptFileDigest;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Typed metadata persisted after a receipt attachment and voucher are stored. */
public record ReceiptFileRegistration(
    long companyId,
    long voucherId,
    ReceiptFileDigest digest,
    String fileName,
    long fileSize,
    OffsetDateTime createdAt
) {
    public ReceiptFileRegistration {
        if (companyId <= 0 || voucherId <= 0) {
            throw new IllegalArgumentException("Receipt file registration requires positive identifiers");
        }
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(createdAt, "createdAt");
        if (fileName == null
            || fileName.isBlank()
            || !fileName.equals(fileName.strip())
            || fileName.codePointCount(0, fileName.length()) > 255
            || fileName.codePoints().anyMatch(Character::isISOControl)
            || fileName.indexOf('/') >= 0
            || fileName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Receipt file name must be a canonical basename of at most 255 characters");
        }
        if (fileSize < 0) {
            throw new IllegalArgumentException("Receipt file size must not be negative");
        }
    }
}
