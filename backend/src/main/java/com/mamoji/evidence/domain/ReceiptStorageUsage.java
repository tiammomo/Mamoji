package com.mamoji.evidence.domain;

public record ReceiptStorageUsage(long objectCount, long usedBytes) {
    public ReceiptStorageUsage {
        if (objectCount < 0 || usedBytes < 0) {
            throw new IllegalArgumentException("Receipt storage usage must not be negative");
        }
    }
}
