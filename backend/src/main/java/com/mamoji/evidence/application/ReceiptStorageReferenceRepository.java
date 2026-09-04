package com.mamoji.evidence.application;

public interface ReceiptStorageReferenceRepository {
    ReceiptStorageReferenceSnapshot findAll(String defaultBucket, int maximumReferences);
}
