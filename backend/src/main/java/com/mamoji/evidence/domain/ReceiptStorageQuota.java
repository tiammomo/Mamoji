package com.mamoji.evidence.domain;

import java.math.BigInteger;

/** Company-scoped capacity policy for durable receipt objects. */
public record ReceiptStorageQuota(long maximumBytes, int warningPercent) {
    public ReceiptStorageQuota {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("Receipt storage maximum must be positive");
        }
        if (warningPercent <= 0 || warningPercent >= 100) {
            throw new IllegalArgumentException("Receipt storage warning percent must be between 1 and 99");
        }
    }

    public long reserve(long usedBytes, long requestedBytes) {
        if (usedBytes < 0 || requestedBytes < 0) {
            throw new IllegalArgumentException("Receipt storage byte counts must not be negative");
        }
        if (usedBytes > maximumBytes || requestedBytes > maximumBytes - usedBytes) {
            throw new CapacityExceededException(usedBytes, requestedBytes, maximumBytes);
        }
        return usedBytes + requestedBytes;
    }

    public boolean warningReached(long projectedBytes) {
        if (projectedBytes < 0) {
            throw new IllegalArgumentException("Projected receipt storage bytes must not be negative");
        }
        return BigInteger.valueOf(projectedBytes).multiply(BigInteger.valueOf(100L)).compareTo(
            BigInteger.valueOf(maximumBytes).multiply(BigInteger.valueOf(warningPercent))
        ) >= 0;
    }

    public static final class CapacityExceededException extends RuntimeException {
        private final long usedBytes;
        private final long requestedBytes;
        private final long maximumBytes;

        public CapacityExceededException(long usedBytes, long requestedBytes, long maximumBytes) {
            super("Company receipt storage quota exceeded");
            this.usedBytes = usedBytes;
            this.requestedBytes = requestedBytes;
            this.maximumBytes = maximumBytes;
        }

        public long usedBytes() {
            return usedBytes;
        }

        public long requestedBytes() {
            return requestedBytes;
        }

        public long maximumBytes() {
            return maximumBytes;
        }
    }
}
