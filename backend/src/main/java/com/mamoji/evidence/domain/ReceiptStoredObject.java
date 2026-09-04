package com.mamoji.evidence.domain;

import java.time.Instant;
import java.util.Objects;

public record ReceiptStoredObject(ReceiptObjectLocation location, long size, Instant lastModified) {
    public ReceiptStoredObject {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(lastModified, "lastModified");
        if (size < 0) {
            throw new IllegalArgumentException("Receipt object size must not be negative");
        }
    }
}
