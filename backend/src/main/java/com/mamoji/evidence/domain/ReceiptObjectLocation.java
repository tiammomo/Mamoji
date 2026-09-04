package com.mamoji.evidence.domain;

public record ReceiptObjectLocation(String bucket, String objectKey) {
    public ReceiptObjectLocation {
        bucket = bucket == null ? "" : bucket.strip();
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("Receipt object bucket is required");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("Receipt object key is required");
        }
    }

    public String displayName() {
        return bucket + "/" + objectKey;
    }
}
